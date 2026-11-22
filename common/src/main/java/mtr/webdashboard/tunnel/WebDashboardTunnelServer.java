package mtr.webdashboard.tunnel;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.packet.IPacket;
import mtr.webdashboard.WebDashboardRuntime;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server side of the API tunnel.
 * <p>
 * Bound to the dashboard's own loopback port, never to a port the client names. Each stream is one
 * short HTTP exchange; the client proxies only API paths, so the large static assets never
 * travel over the game connection.
 * <p>
 * All handlers run on the network thread and do no I/O beyond queueing. Connecting, reading, writing
 * and waiting for credit happen on dedicated daemon threads.
 */
public final class WebDashboardTunnelServer {

	private static final Map<UUID, Map<Integer, ServerApiStream>> STREAMS = new ConcurrentHashMap<>();
	private static final Object LOCK = new Object();
	private static final AtomicInteger TOTAL_STREAMS = new AtomicInteger();
	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

	private static final ExecutorService CONNECT_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
		final Thread thread = new Thread(runnable, "mtr-webdashboard-tunnel-connect-" + THREAD_COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private static final ExecutorService PUMP_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
		final Thread thread = new Thread(runnable, "mtr-webdashboard-tunnel-pump-" + THREAD_COUNTER.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private static volatile long lastRejectLogMillis = 0L;

	private WebDashboardTunnelServer() {
	}

	/**
	 * Network-thread entry point. Never throws: a malformed packet must not reach the connection.
	 */
	public static void handle(ServerPlayer player, FriendlyByteBuf packet) {
		try {
			final WebDashboardTunnelProtocol.Message message = WebDashboardTunnelProtocol.read(packet);
			if (message == null) {
				return;
			}

			switch (message.op()) {
				case WebDashboardTunnelProtocol.OP_OPEN -> open(player, message);
				case WebDashboardTunnelProtocol.OP_DATA -> {
					final ServerApiStream stream = find(player.getUUID(), message.streamId());
					if (stream == null) {
						sendClose(player, message.streamId(), WebDashboardTunnelProtocol.CLOSE_RESET);
					} else if (!stream.offerData(message.data())) {
						stream.closeForOverrun();
					}
				}
				case WebDashboardTunnelProtocol.OP_WINDOW -> {
					final ServerApiStream stream = find(player.getUUID(), message.streamId());
					if (stream != null) {
						stream.addCredit(message.window());
					}
				}
				case WebDashboardTunnelProtocol.OP_CLOSE -> {
					final ServerApiStream stream = find(player.getUUID(), message.streamId());
					if (stream != null) {
						stream.closeFromRemote(message.reason());
					}
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			System.out.println("[MTR-WebDashboard] Ignored a malformed tunnel packet from a client: " + t);
		}
	}

	private static void open(ServerPlayer player, WebDashboardTunnelProtocol.Message message) {
		if (player.hasDisconnected()) {
			return;
		}
		if (message.version() != WebDashboardTunnelProtocol.VERSION) {
			sendAck(player, message.streamId(), WebDashboardTunnelProtocol.STATUS_UNSUPPORTED_VERSION, 0);
			logReject(player, "unsupported version " + message.version());
			return;
		}

		final int dashboardPort = WebDashboardRuntime.getPort();
		if (dashboardPort <= 0) {
			sendAck(player, message.streamId(), WebDashboardTunnelProtocol.STATUS_DASHBOARD_OFFLINE, 0);
			logReject(player, "dashboard is not running");
			return;
		}

		final UUID uuid = player.getUUID();
		final ServerApiStream stream = new ServerApiStream(player, message.streamId());
		int rejectStatus = -1;

		synchronized (LOCK) {
			final Map<Integer, ServerApiStream> perPlayer = STREAMS.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
			if (perPlayer.containsKey(message.streamId())) {
				rejectStatus = WebDashboardTunnelProtocol.STATUS_INTERNAL_ERROR;
			} else if (TOTAL_STREAMS.get() >= WebDashboardTunnelProtocol.MAX_STREAMS_GLOBAL
					|| perPlayer.size() >= WebDashboardTunnelProtocol.MAX_STREAMS_PER_PLAYER) {
				rejectStatus = WebDashboardTunnelProtocol.STATUS_TOO_MANY_STREAMS;
			} else {
				perPlayer.put(message.streamId(), stream);
				TOTAL_STREAMS.incrementAndGet();
			}
		}

		if (rejectStatus >= 0) {
			synchronized (LOCK) {
				final Map<Integer, ServerApiStream> perPlayer = STREAMS.get(uuid);
				if (perPlayer != null && perPlayer.isEmpty()) {
					STREAMS.remove(uuid, perPlayer);
				}
			}
			sendAck(player, message.streamId(), rejectStatus, 0);
			logReject(player, rejectStatus == WebDashboardTunnelProtocol.STATUS_TOO_MANY_STREAMS ? "too many streams" : "duplicate stream id");
			return;
		}

		CONNECT_EXECUTOR.execute(() -> connect(player, stream, dashboardPort));
	}

	private static void connect(ServerPlayer player, ServerApiStream stream, int dashboardPort) {
		Socket socket = null;
		try {
			socket = new Socket();
			socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), dashboardPort),
					WebDashboardTunnelProtocol.CONNECT_TIMEOUT_MS);

			if (player.hasDisconnected() || stream.isClosed()) {
				closeQuietly(socket);
				stream.abort(WebDashboardTunnelProtocol.CLOSE_PLAYER_DISCONNECTED);
				return;
			}
			if (!stream.attach(socket)) {
				closeQuietly(socket);
				stream.abort(WebDashboardTunnelProtocol.CLOSE_RESET);
				return;
			}

			if (!sendAck(player, stream.getId(), WebDashboardTunnelProtocol.STATUS_OK, WebDashboardTunnelProtocol.INITIAL_WINDOW)) {
				stream.abort(WebDashboardTunnelProtocol.CLOSE_PLAYER_DISCONNECTED);
				return;
			}
			System.out.println("[MTR-WebDashboard] Tunnel stream " + stream.getId() + " opened for "
					+ player.getName().getString() + " (active " + getActiveStreamCount() + ")");
			stream.start(PUMP_EXECUTOR);
		} catch (Exception e) {
			closeQuietly(socket);
			stream.abort(WebDashboardTunnelProtocol.CLOSE_RESET);
			final int status = e instanceof ConnectException || e instanceof SocketTimeoutException
					? WebDashboardTunnelProtocol.STATUS_DASHBOARD_OFFLINE
					: WebDashboardTunnelProtocol.STATUS_INTERNAL_ERROR;
			sendAck(player, stream.getId(), status, 0);
			logReject(player, "could not connect to the loopback dashboard: " + e.getMessage());
		}
	}

	/**
	 * Closes every stream of a player who left. Called on the server thread; no packet is sent back
	 * because the connection is gone.
	 */
	public static void onPlayerQuit(UUID uuid) {
		if (uuid == null) {
			return;
		}
		final Map<Integer, ServerApiStream> perPlayer = STREAMS.get(uuid);
		if (perPlayer == null) {
			return;
		}
		for (final ServerApiStream stream : new ArrayList<>(perPlayer.values())) {
			stream.abort(WebDashboardTunnelProtocol.CLOSE_PLAYER_DISCONNECTED);
		}
	}

	/**
	 * Closes every stream. Called before the dashboard Jetty is stopped so no pump is left talking to
	 * a half-torn-down server.
	 */
	public static void stop() {
		for (final UUID uuid : new ArrayList<>(STREAMS.keySet())) {
			onPlayerQuit(uuid);
		}
		STREAMS.clear();
		TOTAL_STREAMS.set(0);
	}

	public static int getActiveStreamCount() {
		return TOTAL_STREAMS.get();
	}

	static ServerApiStream find(UUID uuid, int streamId) {
		final Map<Integer, ServerApiStream> perPlayer = STREAMS.get(uuid);
		return perPlayer == null ? null : perPlayer.get(streamId);
	}

	static void remove(UUID uuid, int streamId, ServerApiStream expected) {
		if (uuid == null) {
			return;
		}
		synchronized (LOCK) {
			final Map<Integer, ServerApiStream> perPlayer = STREAMS.get(uuid);
			if (perPlayer != null && perPlayer.remove(streamId, expected)) {
				TOTAL_STREAMS.updateAndGet(value -> Math.max(0, value - 1));
				if (perPlayer.isEmpty()) {
					STREAMS.remove(uuid, perPlayer);
				}
			}
		}
	}

	static boolean sendAck(ServerPlayer player, int streamId, int status, int window) {
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeOpenAck(buffer, streamId, status, window);
			Registry.sendToPlayer(player, IPacket.PACKET_WEB_DASHBOARD_TUNNEL_S2C, buffer);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	static boolean sendData(ServerPlayer player, int streamId, byte[] data, int offset, int length) {
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeData(buffer, streamId, data, offset, length);
			Registry.sendToPlayer(player, IPacket.PACKET_WEB_DASHBOARD_TUNNEL_S2C, buffer);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	static void sendWindow(ServerPlayer player, int streamId, int credit) {
		if (credit <= 0) {
			return;
		}
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeWindow(buffer, streamId, credit);
			Registry.sendToPlayer(player, IPacket.PACKET_WEB_DASHBOARD_TUNNEL_S2C, buffer);
		} catch (Throwable ignored) {
		}
	}

	static void sendClose(ServerPlayer player, int streamId, int reason) {
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeClose(buffer, streamId, reason);
			Registry.sendToPlayer(player, IPacket.PACKET_WEB_DASHBOARD_TUNNEL_S2C, buffer);
		} catch (Throwable ignored) {
		}
	}

	private static void logReject(ServerPlayer player, String why) {
		final long now = System.currentTimeMillis();
		if (now - lastRejectLogMillis < 2000L) {
			return;
		}
		lastRejectLogMillis = now;
		System.out.println("[MTR-WebDashboard] Tunnel OPEN from " + player.getName().getString() + " rejected: " + why);
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (Exception ignored) {
			}
		}
	}
}
