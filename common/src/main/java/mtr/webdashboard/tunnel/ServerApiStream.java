package mtr.webdashboard.tunnel;

import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;

/**
 * One API request/response on the server side, backed by a loopback connection to the dashboard
 * Jetty.
 * <p>
 * The network thread only queues request bytes here. Two pump threads own the socket: one forwards
 * request bytes to Jetty, the other reads the response back. Credit windows bound both queues, so a
 * stalled browser or a slow game thread never turns into unbounded memory or a blocked netty thread.
 */
public final class ServerApiStream {

	private final ServerPlayer player;
	private final int id;

	private Socket socket;
	private InputStream input;
	private OutputStream output;

	private final ArrayDeque<byte[]> requests = new ArrayDeque<>();
	private int requestBytes;
	private int sendCredit = WebDashboardTunnelProtocol.INITIAL_WINDOW;
	private boolean closed;
	private int closeReason = WebDashboardTunnelProtocol.CLOSE_NORMAL;

	ServerApiStream(ServerPlayer player, int id) {
		this.player = player;
		this.id = id;
	}

	public int getId() {
		return id;
	}

	public synchronized boolean isClosed() {
		return closed;
	}

	/**
	 * Installs the connected socket. Returns false when the stream was closed while connecting, in
	 * which case the caller closes the socket and calls {@link #abort(int)} so the slot is released.
	 */
	synchronized boolean attach(Socket newSocket) {
		if (closed) {
			return false;
		}
		try {
			newSocket.setTcpNoDelay(true);
			socket = newSocket;
			input = newSocket.getInputStream();
			output = newSocket.getOutputStream();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Network-thread entry point for request bytes. Returns false when the queue limit was exceeded;
	 * the caller must then call {@link #closeForOverrun()} after leaving the network thread's
	 * critical section.
	 */
	public synchronized boolean offerData(byte[] data) {
		if (closed) {
			return true;
		}
		if (requestBytes + data.length > WebDashboardTunnelProtocol.MAX_PENDING_BYTES) {
			return false;
		}
		requests.add(data);
		requestBytes += data.length;
		notifyAll();
		return true;
	}

	public void closeForOverrun() {
		finish(WebDashboardTunnelProtocol.CLOSE_OVERRUN, true);
	}

	public synchronized void addCredit(int credit) {
		if (closed) {
			return;
		}
		sendCredit = Math.min(WebDashboardTunnelProtocol.MAX_WINDOW, sendCredit + credit);
		notifyAll();
	}

	void start(ExecutorService executor) {
		executor.execute(this::pumpRequest);
		executor.execute(this::pumpResponse);
	}

	private void pumpRequest() {
		try {
			while (true) {
				final byte[] chunk = takeRequest();
				if (chunk == null) {
					// Closed by the response pump, the player quitting, or the server stopping.
					finish(WebDashboardTunnelProtocol.CLOSE_RESET, false);
					return;
				}
				output.write(chunk);
				output.flush();
				WebDashboardTunnelServer.sendWindow(player, id, chunk.length);
			}
		} catch (Exception e) {
			finish(WebDashboardTunnelProtocol.CLOSE_RESET, true);
		}
	}

	private void pumpResponse() {
		try {
			socket.setSoTimeout(WebDashboardTunnelProtocol.IDLE_TIMEOUT_MS);
			final byte[] buffer = new byte[WebDashboardTunnelProtocol.CHUNK_BYTES];
			while (true) {
				final int read = input.read(buffer);
				if (read < 0) {
					finish(WebDashboardTunnelProtocol.CLOSE_NORMAL, true);
					return;
				}
				if (read == 0) {
					continue;
				}
				if (!waitForCredit(read)) {
					finish(WebDashboardTunnelProtocol.CLOSE_TIMEOUT, true);
					return;
				}
				if (!WebDashboardTunnelServer.sendData(player, id, buffer, 0, read)) {
					finish(WebDashboardTunnelProtocol.CLOSE_RESET, false);
					return;
				}
			}
		} catch (Exception e) {
			finish(WebDashboardTunnelProtocol.CLOSE_RESET, true);
		}
	}

	/**
	 * Waits until the client sends more request bytes or the stream closes. There is deliberately no
	 * timeout: after the request has been forwarded the client is expected to stay silent while the
	 * response streams back, and the response pump owns the idle timeout.
	 */
	private synchronized byte[] takeRequest() {
		while (requests.isEmpty() && !closed) {
			try {
				wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		final byte[] data = requests.poll();
		if (data != null) {
			requestBytes -= data.length;
		}
		return data;
	}

	private synchronized boolean waitForCredit(int bytes) {
		final long deadline = System.currentTimeMillis() + WebDashboardTunnelProtocol.CREDIT_WAIT_TIMEOUT_MS;
		while (sendCredit < bytes && !closed) {
			final long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				return false;
			}
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		if (closed) {
			return false;
		}
		sendCredit -= bytes;
		return true;
	}

	/**
	 * Called when the client sends CLOSE, the player leaves, or the server stops. No packet is sent
	 * back: the peer either initiated this or is already gone.
	 */
	public void closeFromRemote(int reason) {
		finish(reason, false);
	}

	public void abort(int reason) {
		finish(reason, false);
	}

	private void finish(int reason, boolean notifyRemote) {
		final boolean shouldNotify;
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
			closeReason = reason;
			notifyAll();
			shouldNotify = notifyRemote;
		}

		closeQuietly(socket);
		if (shouldNotify) {
			WebDashboardTunnelServer.sendClose(player, id, reason);
		}
		WebDashboardTunnelServer.remove(player.getUUID(), id, this);
		System.out.println("[MTR-WebDashboard] Tunnel stream " + id + " closed (reason " + reason
				+ ", active " + WebDashboardTunnelServer.getActiveStreamCount() + ")");
	}

	synchronized int getCloseReason() {
		return closeReason;
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
