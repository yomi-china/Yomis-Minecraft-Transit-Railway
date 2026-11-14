package mtr.webdashboard.tunnel;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * One API request/response on the client side.
 * <p>
 * A stream is short lived: open it, write the request, read the response until the server closes it,
 * then discard it. Queues are bounded by the credit window, so a slow browser cannot make the
 * network thread buffer without limit.
 * <p>
 * The network thread only calls {@link #onOpenAck}, {@link #offerData}, {@link #addCredit} and
 * {@link #onRemoteClose}; all of those are non-blocking. Blocking happens on the local proxy's
 * connection thread, never on the network or render thread.
 */
public final class ClientApiStream {

	private final int id;

	private int status = -1;
	private int sendCredit;
	private int inboundBytes;
	private boolean closed;
	private boolean remoteClosed;
	private boolean closeNotified;
	private int closeReason = WebDashboardTunnelProtocol.CLOSE_NORMAL;
	private boolean timedOut;

	private final ArrayDeque<byte[]> inbound = new ArrayDeque<>();

	ClientApiStream(int id) {
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public synchronized int awaitOpen(long timeoutMillis) {
		final long deadline = System.currentTimeMillis() + timeoutMillis;
		while (status < 0 && !closed) {
			final long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return -1;
			}
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return -1;
			}
		}
		return status;
	}

	public synchronized void onOpenAck(int newStatus, int window) {
		if (closed) {
			return;
		}
		status = newStatus;
		sendCredit = Math.max(0, Math.min(WebDashboardTunnelProtocol.MAX_WINDOW, window));
		notifyAll();
	}

	/**
	 * Called on the network thread. Returns false when the stream had to be closed for overrun, in
	 * which case the caller sends CLOSE after leaving this method.
	 */
	public synchronized boolean offerData(byte[] data) {
		if (closed || remoteClosed) {
			return true;
		}
		if (inboundBytes + data.length > WebDashboardTunnelProtocol.MAX_PENDING_BYTES) {
			closeLocked(WebDashboardTunnelProtocol.CLOSE_OVERRUN);
			return false;
		}
		inbound.add(data);
		inboundBytes += data.length;
		notifyAll();
		return true;
	}

	public synchronized void addCredit(int credit) {
		if (closed) {
			return;
		}
		sendCredit = Math.min(WebDashboardTunnelProtocol.MAX_WINDOW, sendCredit + credit);
		notifyAll();
	}

	public synchronized void onRemoteClose(int reason) {
		if (closed) {
			return;
		}
		remoteClosed = true;
		closeReason = reason;
		notifyAll();
	}

	/**
	 * Writes the complete request, one chunk at a time, waiting for credit between chunks.
	 */
	public void write(byte[] data, int length) throws IOException {
		int offset = 0;
		while (offset < length) {
			final int chunk = Math.min(WebDashboardTunnelProtocol.CHUNK_BYTES, length - offset);

			synchronized (this) {
				final long deadline = System.currentTimeMillis() + WebDashboardTunnelProtocol.CREDIT_WAIT_TIMEOUT_MS;
				while (sendCredit < chunk && !closed && !remoteClosed) {
					final long remaining = deadline - System.currentTimeMillis();
					if (remaining <= 0) {
						closeLocked(WebDashboardTunnelProtocol.CLOSE_TIMEOUT);
						throw new IOException("Timed out waiting for tunnel credit");
					}
					try {
						wait(remaining);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						closeLocked(WebDashboardTunnelProtocol.CLOSE_RESET);
						throw new IOException("Interrupted while waiting for tunnel credit");
					}
				}
				if (closed || remoteClosed) {
					throw new IOException("Tunnel stream is closed");
				}
				sendCredit -= chunk;
			}

			WebDashboardTunnelClient.sendData(id, data, offset, chunk);
			offset += chunk;
		}
	}

	/**
	 * Returns the next response chunk, or null when the stream ended (normally, by remote close, or
	 * by the local read timeout).
	 */
	public synchronized byte[] read(long timeoutMillis) {
		final long deadline = System.currentTimeMillis() + timeoutMillis;
		while (inbound.isEmpty() && !closed && !remoteClosed) {
			final long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				closeLocked(WebDashboardTunnelProtocol.CLOSE_TIMEOUT);
				timedOut = true;
				return null;
			}
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				closeLocked(WebDashboardTunnelProtocol.CLOSE_RESET);
				return null;
			}
		}

		final byte[] data = inbound.poll();
		if (data != null) {
			inboundBytes -= data.length;
		}
		return data;
	}

	/**
	 * Returns credit for bytes already written to the browser. Called by the proxy thread only.
	 */
	public void releaseReceive(int bytes) {
		if (bytes <= 0) {
			return;
		}
		synchronized (this) {
			if (closed || remoteClosed) {
				return;
			}
		}
		WebDashboardTunnelClient.sendWindow(id, bytes);
	}

	public synchronized boolean isClosed() {
		return closed;
	}

	public synchronized int getCloseReason() {
		return closeReason;
	}

	public synchronized boolean isTimedOut() {
		return timedOut;
	}

	/**
	 * Local shutdown: no packet is sent, the caller is responsible for closing whatever socket it
	 * owns. Used on disconnect, shutdown and after the response has been consumed.
	 */
	public void abort(int reason) {
		synchronized (this) {
			closeLocked(reason);
		}
		WebDashboardTunnelClient.remove(id);
	}

	/**
	 * Graceful local shutdown that also tells the server to release its side. A stream closed by a
	 * read timeout or an overrun still needs this, otherwise the server would wait for its own idle
	 * timeout.
	 */
	public void closeWithPacket(int reason) {
		final boolean shouldNotify;
		synchronized (this) {
			shouldNotify = !closeNotified && !remoteClosed;
			closeNotified = closeNotified || shouldNotify;
			closeLocked(reason);
		}
		WebDashboardTunnelClient.remove(id);
		if (shouldNotify) {
			WebDashboardTunnelClient.sendClose(id, reason);
		}
	}

	private void closeLocked(int reason) {
		if (closed) {
			return;
		}
		closed = true;
		closeReason = reason;
		notifyAll();
	}
}
