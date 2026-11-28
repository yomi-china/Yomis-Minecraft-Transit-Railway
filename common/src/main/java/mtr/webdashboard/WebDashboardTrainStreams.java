package mtr.webdashboard;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-sent-events fan-out for the train overlay.
 *
 * <p>Connections never touch game state directly: the tracker publishes immutable frames, and each
 * connection filters/merges them on one of two writer threads. The heartbeat is deliberately much
 * shorter than the 30 s idle timeouts of Jetty and of the in-game tunnel, because a long-lived
 * response is exactly the case those timeouts were written for.
 */
public final class WebDashboardTrainStreams {

	private static final long HEARTBEAT_MILLIS = 5000L;
	private static final long HEARTBEAT_TICK_MILLIS = 100L;
	private static final long FULL_RESYNC_MILLIS = 10000L;
	private static final long AUTH_RECHECK_MILLIS = 30000L;
	private static final int WRITER_THREADS = 2;

	private static final Set<Connection> CONNECTIONS = ConcurrentHashMap.newKeySet();
	private static final Map<String, AtomicInteger> SUBSCRIBERS = new ConcurrentHashMap<>();

	private static ScheduledExecutorService heartbeatScheduler;
	private static ExecutorService writers;

	private WebDashboardTrainStreams() {
	}

	public static synchronized void start() {
		if (heartbeatScheduler != null) {
			return;
		}
		heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "mtr-webdashboard-train-heartbeat"));
		writers = Executors.newFixedThreadPool(WRITER_THREADS, runnable -> daemon(runnable, "mtr-webdashboard-train-writer"));
		heartbeatScheduler.scheduleWithFixedDelay(WebDashboardTrainStreams::tickConnections, HEARTBEAT_TICK_MILLIS, HEARTBEAT_TICK_MILLIS, TimeUnit.MILLISECONDS);
	}

	public static synchronized void stop() {
		closeAll("server_stopping");
		if (heartbeatScheduler != null) {
			heartbeatScheduler.shutdownNow();
			heartbeatScheduler = null;
		}
		if (writers != null) {
			writers.shutdownNow();
			writers = null;
		}
		SUBSCRIBERS.clear();
	}

	public static void register(Connection connection) {
		CONNECTIONS.add(connection);
		SUBSCRIBERS.compute(connection.dimension, (key, count) -> {
			final AtomicInteger next = count == null ? new AtomicInteger() : count;
			next.incrementAndGet();
			return next;
		});
		connection.begin();
	}

	public static void unregister(Connection connection) {
		if (!CONNECTIONS.remove(connection)) {
			return;
		}
		SUBSCRIBERS.computeIfPresent(connection.dimension, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
	}

	public static boolean hasAnySubscriber() {
		return !CONNECTIONS.isEmpty();
	}

	public static int subscriberCount() {
		return CONNECTIONS.size();
	}

	public static Set<String> subscribedDimensions() {
		return new LinkedHashSet<>(SUBSCRIBERS.keySet());
	}

	public static void publish(WebDashboardTrainTracker.Sample sample) {
		if (sample == null) {
			return;
		}
		for (final Connection connection : CONNECTIONS) {
			connection.offer(sample);
		}
	}

	public static void closeAll(String reason) {
		for (final Connection connection : new ArrayList<>(CONNECTIONS)) {
			connection.close(reason);
		}
	}

	private static void tickConnections() {
		for (final Connection connection : new ArrayList<>(CONNECTIONS)) {
			try {
				connection.tick();
			} catch (Throwable t) {
				connection.close("tick_failed");
			}
		}
	}

	private static ExecutorService writers() {
		return writers;
	}

	private static Thread daemon(Runnable runnable, String name) {
		final Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	/** One open SSE response. All mutable state is guarded by {@link #stateLock}. */
	public static final class Connection {

		private final AsyncContext asyncContext;
		private final WebDashboardSession session;
		private final String dimension;
		private final long routeId;
		private final long depotId;
		private final int limit;
		private final int hz;
		private final Object stateLock = new Object();
		private final Object writeLock = new Object();
		private final Map<Long, TrainFrame> pending = new LinkedHashMap<>();
		private final Set<Long> gone = new LinkedHashSet<>();
		private final Set<Long> knownIds = new LinkedHashSet<>();

		private volatile boolean closed;
		private volatile long lastWriteAt;
		private volatile long lastFlushAt;
		private boolean flushScheduled;
		private boolean heartbeatScheduled;
		private boolean needsFull = true;
		private boolean truncated;
		private int lastFilteredTotal;
		private long lastFullAt;
		private long lastAuthCheckAt;
		private long sequence;
		private long framesSent;
		private long lastBuildMicros;

		public Connection(AsyncContext asyncContext, WebDashboardSession session, WebDashboardTrainService.Query query, int hz) {
			this.asyncContext = asyncContext;
			this.session = session;
			this.dimension = query.dimension();
			this.routeId = query.routeId();
			this.depotId = query.depotId();
			this.limit = Math.max(1, Math.min(WebDashboardTrainService.MAX_LIMIT, query.limit()));
			this.hz = Math.max(1, Math.min(10, hz));
			final long now = System.currentTimeMillis();
			lastWriteAt = now;
			lastFullAt = now;
			lastAuthCheckAt = now;
			lastFlushAt = now;
		}

		public String getDimension() {
			return dimension;
		}

		void begin() {
			writeRaw("retry: 3000\n\n");
			scheduleFlush();
			System.out.println("[MTR-WebDashboard] Train stream opened for " + dimension + " (hz=" + hz + ", limit=" + limit + ")");
		}

		void offer(WebDashboardTrainTracker.Sample sample) {
			if (closed || !dimension.equals(sample.dimension())) {
				return;
			}
			synchronized (stateLock) {
				lastBuildMicros = sample.buildMicros();
				for (final Long id : sample.gone()) {
					pending.remove(id);
					if (knownIds.contains(id)) {
						gone.add(id);
					}
				}
				for (final TrainFrame frame : sample.changed()) {
					gone.remove(frame.id());
					pending.put(frame.id(), frame);
				}
			}
			if (System.currentTimeMillis() - lastFlushAt >= 1000L / hz) {
				scheduleFlush();
			}
		}

		void tick() {
			if (closed) {
				return;
			}
			final long now = System.currentTimeMillis();
			if (now - lastFlushAt >= 1000L / hz && hasPending()) {
				scheduleFlush();
			}
			if (now - lastFullAt >= FULL_RESYNC_MILLIS) {
				lastFullAt = now;
				synchronized (stateLock) {
					needsFull = true;
				}
				scheduleFlush();
			}
			if (now - lastAuthCheckAt >= AUTH_RECHECK_MILLIS) {
				lastAuthCheckAt = now;
				if (WebDashboardTokenStore.validateSession(session.getToken()) == null) {
					writeRaw("event: auth\ndata: {\"error\":\"not_authenticated\"}\n\n");
					close("auth_revoked");
					return;
				}
			}
			if (now - lastWriteAt >= HEARTBEAT_MILLIS) {
				submitHeartbeat();
			}
		}

		private void scheduleFlush() {
			synchronized (stateLock) {
				if (closed || flushScheduled) {
					return;
				}
				if (!needsFull && pending.isEmpty() && gone.isEmpty()) {
					return;
				}
				flushScheduled = true;
			}
			final ExecutorService pool = writers();
			if (pool == null) {
				synchronized (stateLock) {
					flushScheduled = false;
				}
				return;
			}
			try {
				pool.execute(this::flush);
			} catch (Exception e) {
				synchronized (stateLock) {
					flushScheduled = false;
				}
			}
		}

		private void flush() {
			final List<TrainFrame> frames;
			final Set<Long> goneOut;
			final boolean full;
			final long metaSequence;
			final long buildMicros;
			final int total;
			final boolean truncatedNow;

			synchronized (stateLock) {
				if (closed) {
					flushScheduled = false;
					return;
				}
				full = needsFull;
				buildMicros = lastBuildMicros;
				if (full) {
					needsFull = false;
					frames = select(WebDashboardTrainTracker.snapshot(dimension), true);
					goneOut = null;
				} else {
					frames = select(new ArrayList<>(pending.values()), false);
					goneOut = new LinkedHashSet<>(gone);
					pending.clear();
					gone.clear();
					knownIds.removeAll(goneOut);
				}
				total = lastFilteredTotal;
				truncatedNow = truncated;
				metaSequence = ++sequence;
				flushScheduled = false;
			}

			final WebDashboardTrainService.SnapshotMeta meta = new WebDashboardTrainService.SnapshotMeta(
					WebDashboardTrainService.PROTOCOL_VERSION,
					metaSequence,
					System.currentTimeMillis(),
					hz,
					buildMicros / 1000.0,
					total,
					truncatedNow,
					WebDashboardTrainTracker.isPartial(dimension));

			final String payload = full
					? WebDashboardTrainService.writeFull(frames, meta)
					: WebDashboardTrainService.writeFrame(frames, goneOut, meta);
			final String event = full ? "full" : "frame";
			writeRaw("event: " + event + "\nid: " + metaSequence + "\ndata: " + payload + "\n\n");
			framesSent += frames.size();
			lastFlushAt = System.currentTimeMillis();
		}

		private boolean hasPending() {
			synchronized (stateLock) {
				return needsFull || !pending.isEmpty() || !gone.isEmpty();
			}
		}

		/**
		 * Filters a candidate list by this connection's route/depot and applies its train cap. Already
		 * known trains keep their slot, so a busy network cannot push the tracked train out at random.
		 */
		private List<TrainFrame> select(List<TrainFrame> candidates, boolean full) {
			final List<TrainFrame> filtered = new ArrayList<>();
			for (final TrainFrame frame : candidates) {
				if (matches(frame)) {
					filtered.add(frame);
				}
			}
			final int filteredTotal = filtered.size();
			final List<TrainFrame> selected = new ArrayList<>(Math.min(limit, filteredTotal));
			if (full) {
				knownIds.clear();
			}
			for (final TrainFrame frame : filtered) {
				if (selected.size() >= limit) {
					break;
				}
				if (!full && !knownIds.contains(frame.id()) && knownIds.size() >= limit) {
					continue;
				}
				selected.add(frame);
				knownIds.add(frame.id());
			}
			if (full) {
				lastFilteredTotal = filteredTotal;
				truncated = filteredTotal > selected.size();
			}
			return selected;
		}

		private boolean matches(TrainFrame frame) {
			return (routeId == 0 || frame.routeId() == routeId)
					&& (depotId == 0 || frame.depotId() == depotId);
		}

		private void submitHeartbeat() {
			synchronized (stateLock) {
				if (heartbeatScheduled || closed) {
					return;
				}
				heartbeatScheduled = true;
			}
			final ExecutorService pool = writers();
			if (pool == null) {
				synchronized (stateLock) {
					heartbeatScheduled = false;
				}
				return;
			}
			try {
				pool.execute(() -> {
					try {
						writeRaw("event: ping\ndata: {\"now\":" + System.currentTimeMillis() + "}\n\n");
					} finally {
						synchronized (stateLock) {
							heartbeatScheduled = false;
						}
					}
				});
			} catch (Exception e) {
				synchronized (stateLock) {
					heartbeatScheduled = false;
				}
			}
		}

		private void writeRaw(String text) {
			synchronized (writeLock) {
				if (closed) {
					return;
				}
				try {
					final HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
					response.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
					response.flushBuffer();
					lastWriteAt = System.currentTimeMillis();
				} catch (IOException | IllegalStateException e) {
					close("write_failed");
				}
			}
		}

		public void close(String reason) {
			synchronized (stateLock) {
				if (closed) {
					return;
				}
				closed = true;
				pending.clear();
				gone.clear();
				knownIds.clear();
			}
			WebDashboardTrainStreams.unregister(this);
			try {
				asyncContext.complete();
			} catch (Exception ignored) {
			}
			System.out.println("[MTR-WebDashboard] Train stream closed (" + reason + ") after " + framesSent + " train frames");
		}
	}
}
