package mtr.webdashboard.tunnel;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire format for the web dashboard API tunnel.
 * <p>
 * One channel in each direction, with a leading opcode. DATA carries a bounded slice of the HTTP
 * request or response; WINDOW returns credit so a slow reader applies backpressure instead of
 * letting either side buffer without limit.
 * <p>
 * Every read is defensive: a malformed or truncated packet returns null instead of throwing, because
 * this is called from the network thread and an exception there can take the connection down.
 */
public final class WebDashboardTunnelProtocol {

	/** Protocol revision, independent of the mod version. */
	public static final int VERSION = 1;

	public static final int OP_OPEN = 1;
	public static final int OP_OPEN_ACK = 2;
	public static final int OP_DATA = 3;
	public static final int OP_WINDOW = 4;
	public static final int OP_CLOSE = 5;

	public static final int STATUS_OK = 0;
	public static final int STATUS_DASHBOARD_OFFLINE = 1;
	public static final int STATUS_TOO_MANY_STREAMS = 2;
	public static final int STATUS_UNSUPPORTED_VERSION = 3;
	public static final int STATUS_INTERNAL_ERROR = 4;

	public static final int CLOSE_NORMAL = 0;
	public static final int CLOSE_RESET = 1;
	public static final int CLOSE_TIMEOUT = 2;
	public static final int CLOSE_OVERRUN = 3;
	public static final int CLOSE_PLAYER_DISCONNECTED = 4;
	public static final int CLOSE_SERVER_STOPPING = 5;

	/** Same chunk size the existing data sync and server audio use. */
	public static final int CHUNK_BYTES = 16 * 1024;
	/** Bytes either side may have in flight before it must wait for a WINDOW. */
	public static final int INITIAL_WINDOW = 128 * 1024;
	/** Upper bound on accumulated credit. */
	public static final int MAX_WINDOW = 256 * 1024;

	/** About three or four browser tabs, six connections each. */
	public static final int MAX_STREAMS_PER_PLAYER = 24;
	/** Server-wide ceiling; bounds threads and buffered bytes. */
	public static final int MAX_STREAMS_GLOBAL = 128;

	/** The client waits this long for OPEN_ACK. */
	public static final int OPEN_TIMEOUT_MS = 5000;
	/** Connecting to the loopback dashboard is local; this only covers a wedged listener. */
	public static final int CONNECT_TIMEOUT_MS = 3000;
	/** No bytes in either direction for this long closes the stream. */
	public static final int IDLE_TIMEOUT_MS = 30000;
	/** Waiting for a WINDOW that never arrives. */
	public static final int CREDIT_WAIT_TIMEOUT_MS = 30000;
	/**
	 * Receive-queue ceiling. It must cover the largest credit the sender can accumulate, not just the
	 * initial window: a fast reader returns credit faster than the sender spends it, and cutting the
	 * stream off at that point would look like a random failure under load.
	 */
	public static final int MAX_PENDING_BYTES = MAX_WINDOW + CHUNK_BYTES;
	/** An HTTP body larger than this is refused by the local proxy. */
	public static final int MAX_REQUEST_BODY_BYTES = 256 * 1024;

	private WebDashboardTunnelProtocol() {
	}

	public static void writeOpen(FriendlyByteBuf buffer, int version, int streamId) {
		buffer.writeByte(OP_OPEN);
		buffer.writeVarInt(version);
		buffer.writeVarInt(streamId);
	}

	public static void writeOpenAck(FriendlyByteBuf buffer, int streamId, int status, int window) {
		buffer.writeByte(OP_OPEN_ACK);
		buffer.writeVarInt(streamId);
		buffer.writeByte(status);
		buffer.writeVarInt(window);
	}

	public static void writeData(FriendlyByteBuf buffer, int streamId, byte[] data, int offset, int length) {
		buffer.writeByte(OP_DATA);
		buffer.writeVarInt(streamId);
		buffer.writeVarInt(length);
		buffer.writeBytes(data, offset, length);
	}

	public static void writeWindow(FriendlyByteBuf buffer, int streamId, int credit) {
		buffer.writeByte(OP_WINDOW);
		buffer.writeVarInt(streamId);
		buffer.writeVarInt(credit);
	}

	public static void writeClose(FriendlyByteBuf buffer, int streamId, int reason) {
		buffer.writeByte(OP_CLOSE);
		buffer.writeVarInt(streamId);
		buffer.writeByte(reason);
	}

	/**
	 * Parses one packet. Returns null for anything malformed, oversized, unknown or negative; callers
	 * treat null as "ignore this packet", never as a reason to disconnect.
	 */
	public static Message read(FriendlyByteBuf buffer) {
		try {
			if (buffer.readableBytes() < 1) {
				return null;
			}

			final int op = buffer.readByte() & 0xFF;
			switch (op) {
				case OP_OPEN: {
					if (buffer.readableBytes() < 2) {
						return null;
					}
					final int version = buffer.readVarInt();
					final int streamId = buffer.readVarInt();
					return isStreamId(streamId) && version >= 0
							? new Message(op, version, streamId, 0, 0, null, 0)
							: null;
				}
				case OP_OPEN_ACK: {
					if (buffer.readableBytes() < 3) {
						return null;
					}
					final int streamId = buffer.readVarInt();
					final int status = buffer.readByte() & 0xFF;
					final int window = buffer.readVarInt();
					return isStreamId(streamId) && status >= STATUS_OK && status <= STATUS_INTERNAL_ERROR
							&& window >= 0 && window <= MAX_WINDOW
							? new Message(op, 0, streamId, status, window, null, 0)
							: null;
				}
				case OP_DATA: {
					if (buffer.readableBytes() < 2) {
						return null;
					}
					final int streamId = buffer.readVarInt();
					final int length = buffer.readVarInt();
					if (!isStreamId(streamId) || length <= 0 || length > CHUNK_BYTES || buffer.readableBytes() < length) {
						return null;
					}
					final byte[] data = new byte[length];
					buffer.readBytes(data);
					return new Message(op, 0, streamId, 0, 0, data, 0);
				}
				case OP_WINDOW: {
					if (buffer.readableBytes() < 2) {
						return null;
					}
					final int streamId = buffer.readVarInt();
					final int credit = buffer.readVarInt();
					return isStreamId(streamId) && credit > 0 && credit <= MAX_WINDOW
							? new Message(op, 0, streamId, 0, credit, null, 0)
							: null;
				}
				case OP_CLOSE: {
					if (buffer.readableBytes() < 2) {
						return null;
					}
					final int streamId = buffer.readVarInt();
					final int reason = buffer.readByte() & 0xFF;
					return isStreamId(streamId) && reason <= CLOSE_SERVER_STOPPING
							? new Message(op, 0, streamId, 0, 0, null, reason)
							: null;
				}
				default:
					return null;
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isStreamId(int streamId) {
		return streamId >= 0;
	}

	/**
	 * One decoded message. Only the fields named by {@link #op()} are meaningful; the rest are zero.
	 */
	public record Message(int op, int version, int streamId, int status, int window, byte[] data, int reason) {
	}
}
