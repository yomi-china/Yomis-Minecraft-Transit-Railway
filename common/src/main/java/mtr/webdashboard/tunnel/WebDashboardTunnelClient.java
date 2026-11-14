package mtr.webdashboard.tunnel;

import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import mtr.packet.IPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the API tunnel.
 * <p>
 * Owns the stream table, allocates ids, parses incoming packets and closes everything when the game
 * connection changes. All packet handlers run on the network thread and only touch in-memory state;
 * all blocking waits happen on the local proxy's connection threads.
 */
public final class WebDashboardTunnelClient {

	private static final Map<Integer, ClientApiStream> STREAMS = new ConcurrentHashMap<>();
	private static final Object LOCK = new Object();

	private static Object connectionIdentity = null;
	private static int nextStreamId = 1;

	private WebDashboardTunnelClient() {
	}

	/**
	 * Opens a stream and waits for the server to confirm it, with credit for the response.
	 *
	 * @throws TunnelException when not connected, refused by the server, or timed out.
	 */
	public static ClientApiStream openApiStream() throws TunnelException {
		if (!isConnected()) {
			throw new TunnelException("not_connected", -1);
		}

		final ClientApiStream stream;
		synchronized (LOCK) {
			if (STREAMS.size() >= WebDashboardTunnelProtocol.MAX_STREAMS_PER_PLAYER) {
				throw new TunnelException("busy", -1);
			}
			final int id = allocateStreamId();
			stream = new ClientApiStream(id);
			STREAMS.put(id, stream);
		}

		try {
			sendOpen(stream.getId());
		} catch (IOException e) {
			STREAMS.remove(stream.getId());
			stream.abort(WebDashboardTunnelProtocol.CLOSE_RESET);
			throw new TunnelException("not_connected", -1);
		}

		final int status = stream.awaitOpen(WebDashboardTunnelProtocol.OPEN_TIMEOUT_MS);
		if (status != WebDashboardTunnelProtocol.STATUS_OK) {
			// Tell the server too: a stream may already be reserved (connect in progress), and this
			// releases its loopback socket instead of leaving it for the idle timeout.
			stream.closeWithPacket(WebDashboardTunnelProtocol.CLOSE_RESET);
			if (status < 0) {
				throw new TunnelException("open_timeout", -1);
			}
			throw new TunnelException(codeForStatus(status), status);
		}
		return stream;
	}

	/**
	 * Network-thread entry point. Never throws: a malformed packet must not reach the connection.
	 */
	public static void handle(FriendlyByteBuf packet) {
		try {
			final WebDashboardTunnelProtocol.Message message = WebDashboardTunnelProtocol.read(packet);
			if (message == null) {
				return;
			}

			final ClientApiStream stream = STREAMS.get(message.streamId());
			if (stream == null) {
				if (message.op() != WebDashboardTunnelProtocol.OP_OPEN_ACK) {
					sendClose(message.streamId(), WebDashboardTunnelProtocol.CLOSE_RESET);
				}
				return;
			}

			switch (message.op()) {
				case WebDashboardTunnelProtocol.OP_OPEN_ACK -> stream.onOpenAck(message.status(), message.window());
				case WebDashboardTunnelProtocol.OP_DATA -> {
					if (!stream.offerData(message.data())) {
						stream.closeWithPacket(WebDashboardTunnelProtocol.CLOSE_OVERRUN);
					}
				}
				case WebDashboardTunnelProtocol.OP_WINDOW -> stream.addCredit(message.window());
				case WebDashboardTunnelProtocol.OP_CLOSE -> stream.onRemoteClose(message.reason());
				default -> {
				}
			}
		} catch (Throwable t) {
			System.out.println("[MTR-WebDashboard] Ignored a malformed tunnel packet: " + t);
		}
	}

	/**
	 * Client-thread housekeeping. Closes streams when the connection object changes, and stops the
	 * local proxy when no server is connected or a single-player world is running, so the integrated
	 * server can own the fixed dashboard port.
	 */
	public static void tick() {
		try {
			final Minecraft minecraft = Minecraft.getInstance();
			final Object current = minecraft.getConnection();

			if (current != connectionIdentity) {
				connectionIdentity = current;
				if (!STREAMS.isEmpty()) {
					closeAll(current == null ? WebDashboardTunnelProtocol.CLOSE_PLAYER_DISCONNECTED : WebDashboardTunnelProtocol.CLOSE_RESET);
				}
			}

			if (minecraft.hasSingleplayerServer() && !STREAMS.isEmpty()) {
				// Single player talks to the integrated server directly; any stream left over from a
				// previous multiplayer session has no business surviving into this mode.
				closeAll(WebDashboardTunnelProtocol.CLOSE_RESET);
			}

			if (current == null || minecraft.hasSingleplayerServer()) {
				WebDashboardLocalProxy.stop();
			}
		} catch (Throwable t) {
			System.out.println("[MTR-WebDashboard] Tunnel housekeeping failed: " + t);
		}
	}

	public static boolean isConnected() {
		try {
			final Minecraft minecraft = Minecraft.getInstance();
			return minecraft.getConnection() != null && minecraft.player != null;
		} catch (Throwable t) {
			return false;
		}
	}

	public static void closeAll(int reason) {
		final ArrayList<ClientApiStream> streams;
		synchronized (LOCK) {
			streams = new ArrayList<>(STREAMS.values());
			STREAMS.clear();
		}
		for (final ClientApiStream stream : streams) {
			stream.abort(reason);
		}
	}

	public static int getActiveStreamCount() {
		return STREAMS.size();
	}

	public static String getLoginUrl(String token) {
		return WebDashboardLocalProxy.getLoginUrl(token);
	}

	static void remove(int streamId) {
		STREAMS.remove(streamId);
	}

	static void sendData(int streamId, byte[] data, int offset, int length) throws IOException {
		final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		WebDashboardTunnelProtocol.writeData(buffer, streamId, data, offset, length);
		send(buffer);
	}

	static void sendWindow(int streamId, int credit) {
		if (credit <= 0) {
			return;
		}
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeWindow(buffer, streamId, credit);
			send(buffer);
		} catch (Throwable t) {
			final ClientApiStream stream = STREAMS.get(streamId);
			if (stream != null) {
				stream.abort(WebDashboardTunnelProtocol.CLOSE_RESET);
			}
		}
	}

	static void sendClose(int streamId, int reason) {
		try {
			final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			WebDashboardTunnelProtocol.writeClose(buffer, streamId, reason);
			send(buffer);
		} catch (Throwable ignored) {
		}
	}

	private static void sendOpen(int streamId) throws IOException {
		final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		WebDashboardTunnelProtocol.writeOpen(buffer, WebDashboardTunnelProtocol.VERSION, streamId);
		send(buffer);
	}

	private static void send(FriendlyByteBuf buffer) throws IOException {
		try {
			RegistryClient.sendToServer(IPacket.PACKET_WEB_DASHBOARD_TUNNEL_C2S, buffer);
		} catch (Throwable t) {
			throw new IOException("Could not send a tunnel packet", t);
		}
	}

	private static int allocateStreamId() {
		for (int attempt = 0; attempt < 1024; attempt++) {
			final int id = nextStreamId;
			nextStreamId = nextStreamId == Integer.MAX_VALUE ? 1 : nextStreamId + 1;
			if (!STREAMS.containsKey(id)) {
				return id;
			}
		}
		throw new IllegalStateException("No free tunnel stream id");
	}

	private static String codeForStatus(int status) {
		return switch (status) {
			case WebDashboardTunnelProtocol.STATUS_DASHBOARD_OFFLINE -> "dashboard_offline";
			case WebDashboardTunnelProtocol.STATUS_TOO_MANY_STREAMS -> "busy";
			case WebDashboardTunnelProtocol.STATUS_UNSUPPORTED_VERSION -> "unsupported_version";
			default -> "internal_error";
		};
	}

	/**
	 * Carries a refusal or timeout from {@link #openApiStream()} to the local HTTP layer, which maps
	 * it to a proper status code for the page.
	 */
	public static final class TunnelException extends Exception {

		private final String code;
		private final int status;

		TunnelException(String code, int status) {
			super(code);
			this.code = code;
			this.status = status;
		}

		public String getCode() {
			return code;
		}

		public int getStatus() {
			return status;
		}
	}
}
