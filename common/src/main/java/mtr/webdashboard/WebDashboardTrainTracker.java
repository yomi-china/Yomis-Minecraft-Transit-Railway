package mtr.webdashboard;

import mtr.data.DataCache;
import mtr.data.RailwayData;
import mtr.data.Siding;
import mtr.data.TrainServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples train positions for the SSE stream.
 *
 * <p>The sampler runs on the game thread and only recomputes geometry for trains whose cheap state
 * changed, so a stationary fleet costs a few field comparisons per sample. The resulting frames are
 * immutable and are read by the stream writer threads without locking.
 */
public final class WebDashboardTrainTracker {

	private static final long SCHEDULE_INTERVAL_MS = 100L;
	private static final int MAX_DIRTY_PER_SAMPLE = 256;
	private static final long LOG_INTERVAL_MS = 60000L;

	private static final AtomicBoolean SAMPLING = new AtomicBoolean(false);
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final Map<String, DimensionState> STATES = new ConcurrentHashMap<>();

	private static ScheduledExecutorService scheduler;
	private static volatile long lastLogAt;
	private static long samples;
	private static long totalMicros;

	private WebDashboardTrainTracker() {
	}

	public record Sample(String dimension, long seq, long atMillis, long buildMicros, List<TrainFrame> changed, Set<Long> gone, boolean partial) {
	}

	public static synchronized void start() {
		if (scheduler != null) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "mtr-webdashboard-trains"));
		scheduler.scheduleWithFixedDelay(WebDashboardTrainTracker::tick, SCHEDULE_INTERVAL_MS, SCHEDULE_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public static synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
		SAMPLING.set(false);
		for (final DimensionState state : STATES.values()) {
			state.frames.clear();
			state.cheap.clear();
			state.lastSample = null;
		}
		STATES.clear();
	}

	/** @return the current frames of a dimension, sorted by id so a full snapshot is deterministic. */
	public static List<TrainFrame> snapshot(String dimension) {
		final DimensionState state = STATES.get(dimension);
		if (state == null) {
			return List.of();
		}
		final List<TrainFrame> frames = new ArrayList<>(state.frames.values());
		frames.sort(Comparator.comparingLong(TrainFrame::id));
		return frames;
	}

	public static boolean isPartial(String dimension) {
		final DimensionState state = STATES.get(dimension);
		final Sample sample = state == null ? null : state.lastSample;
		return sample != null && sample.partial();
	}

	private static void tick() {
		if (!WebDashboardSettings.get().isTrainsStreamEnabled()) {
			return;
		}
		if (!WebDashboardTrainStreams.hasAnySubscriber()) {
			return;
		}
		if (!SAMPLING.compareAndSet(false, true)) {
			return;
		}
		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			SAMPLING.set(false);
			return;
		}
		try {
			server.execute(() -> {
				try {
					sample(server);
				} catch (Throwable t) {
					System.out.println("[MTR-WebDashboard] Train sampling failed: " + t);
				} finally {
					SAMPLING.set(false);
				}
			});
		} catch (Exception e) {
			SAMPLING.set(false);
		}
	}

	private static void sample(MinecraftServer server) {
		final int hz = Math.max(1, Math.min(10, WebDashboardSettings.get().getTrainsPushHz()));
		final long minIntervalMillis = Math.max(50L, 1000L / hz);
		final long now = System.currentTimeMillis();

		for (final String dimension : WebDashboardTrainStreams.subscribedDimensions()) {
			final DimensionState state = STATES.computeIfAbsent(dimension, key -> new DimensionState());
			if (now - state.lastSampleAtMillis < minIntervalMillis) {
				continue;
			}
			state.lastSampleAtMillis = now;

			final ServerLevel level = findLevel(server, dimension);
			final RailwayData railwayData = level == null ? null : RailwayData.getInstance(level);
			final DataCache dataCache = railwayData == null ? null : railwayData.dataCache;
			if (railwayData == null) {
				if (!state.frames.isEmpty()) {
					final Set<Long> gone = new HashSet<>(state.frames.keySet());
					state.frames.clear();
					state.cheap.clear();
					WebDashboardTrainStreams.publish(new Sample(dimension, SEQUENCE.incrementAndGet(), now, 0, List.of(), gone, false));
				}
				continue;
			}

			final long started = System.nanoTime();
			final List<TrainFrame> changed = new ArrayList<>();
			final Set<Long> seen = new HashSet<>();
			boolean partial = false;

			for (final Siding siding : railwayData.sidings) {
				if (siding == null) {
					continue;
				}
				for (final TrainServer train : siding.getTrains()) {
					if (train == null) {
						continue;
					}
					seen.add(train.id);
					if (train.path == null || train.path.isEmpty() || train.spacing <= 0) {
						continue;
					}
					final CheapState before = state.cheap.get(train.id);
					if (before != null && before.matches(train)) {
						continue;
					}
					if (changed.size() >= MAX_DIRTY_PER_SAMPLE) {
						partial = true;
						continue;
					}
					final TrainFrame frame = WebDashboardTrainService.computeFrame(train, siding, dataCache);
					state.frames.put(train.id, frame);
					state.cheap.put(train.id, new CheapState(train));
					changed.add(frame);
				}
			}

			final Set<Long> gone = new HashSet<>();
			for (final Long id : state.frames.keySet()) {
				if (!seen.contains(id)) {
					state.frames.remove(id);
					state.cheap.remove(id);
					gone.add(id);
				}
			}

			final long buildMicros = (System.nanoTime() - started) / 1000L;
			final Sample sample = new Sample(dimension, SEQUENCE.incrementAndGet(), now, buildMicros, changed, gone, partial);
			state.lastSample = sample;
			if (!changed.isEmpty() || !gone.isEmpty()) {
				WebDashboardTrainStreams.publish(sample);
			}

			samples++;
			totalMicros += buildMicros;
			logIfDue(now);
		}
	}

	private static void logIfDue(long now) {
		if (now - lastLogAt < LOG_INTERVAL_MS) {
			return;
		}
		lastLogAt = now;
		if (samples == 0) {
			return;
		}
		final double averageMillis = totalMicros / 1000.0 / samples;
		System.out.println("[MTR-WebDashboard] train samples: count=" + samples + " avgMs=" + Math.round(averageMillis * 100) / 100.0
				+ " subscribers=" + WebDashboardTrainStreams.subscriberCount());
		samples = 0;
		totalMicros = 0;
	}

	private static ServerLevel findLevel(MinecraftServer server, String dimension) {
		for (final ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	private static Thread daemon(Runnable runnable, String name) {
		final Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}

	private static final class DimensionState {
		final Map<Long, TrainFrame> frames = new ConcurrentHashMap<>();
		final Map<Long, CheapState> cheap = new HashMap<>();
		volatile Sample lastSample;
		long lastSampleAtMillis;
	}

	/**
	 * Fields that are cheap to read and enough to decide whether a train's full frame changed. Only the
	 * game thread touches these; the frames map is the only structure read by other threads.
	 */
	private static final class CheapState {
		final double railProgress;
		final float speed;
		final float doors;
		final long nextStopId;
		final boolean reversed;
		final boolean onRoute;
		final boolean manual;
		final long sidingId;
		final long routeId;

		CheapState(TrainServer train) {
			railProgress = train.getRailProgress();
			speed = train.getSpeed();
			doors = train.getDoorValue();
			nextStopId = train.getNextStopId();
			reversed = train.isReversed();
			onRoute = train.getIsOnRoute();
			manual = train.isCurrentlyManual();
			sidingId = train.sidingId;
			routeId = train.getRouteId();
		}

		boolean matches(TrainServer train) {
			return railProgress == train.getRailProgress()
					&& speed == train.getSpeed()
					&& doors == train.getDoorValue()
					&& nextStopId == train.getNextStopId()
					&& reversed == train.isReversed()
					&& onRoute == train.getIsOnRoute()
					&& manual == train.isCurrentlyManual()
					&& sidingId == train.sidingId
					&& routeId == train.getRouteId();
		}
	}
}
