package mtr.webdashboard;

import net.minecraft.server.MinecraftServer;

/**
 * Tracks the live state of the new web dashboard HTTP service so that any part of the mod
 * (the in-game dashboard button, the client-side fallback starter, the servlets themselves)
 * can find out whether the service is actually listening, on which port, and how to reach the
 * running server.
 * <p>
 * This deliberately mirrors {@code mtr.servlet.Webserver}, which keeps the same kind of state in
 * mutable public statics. Here it is private and volatile so that the client and the integrated
 * server - which share a single JVM in single player - cannot observe a half-written value.
 * <p>
 * Note that {@link #isRunning()} reports whether the HTTP server was bound successfully. It says
 * nothing about whether a world is loaded; a dedicated server or a player sitting on the title
 * screen can both have the service up.
 */
public final class WebDashboardRuntime {

	private static volatile Integer activePort = null;

	/**
	 * How to reach the running server.
	 * <p>
	 * Needed because everything that touches {@code RailwayData} has to run on the game thread, and
	 * the HTTP layer only ever holds a session. Set from the server-starting hook and cleared on
	 * shutdown, so a request arriving outside a server's lifetime gets a clean "not available"
	 * instead of a stale player list.
	 */
	private static volatile MinecraftServer server = null;

	private WebDashboardRuntime() {
	}

	/**
	 * Records a successful bind. Called by {@link WebDashboardServer} while it holds its own lock.
	 */
	static void set(int port) {
		activePort = port;
	}

	/**
	 * Forgets the bind. Called by {@link WebDashboardServer} on stop and when a start attempt fails.
	 * <p>
	 * Deliberately does not clear the server reference: the HTTP service and the game server have
	 * separate lifetimes in both directions - the client fallback can start the service before a
	 * world exists, and a server can outlive a failed bind.
	 */
	static void clear() {
		activePort = null;
	}

	/**
	 * @return true when the service is currently accepting connections.
	 */
	public static boolean isRunning() {
		return activePort != null;
	}

	/**
	 * @return the port actually bound, or -1 when the service is not running.
	 */
	public static int getPort() {
		final Integer port = activePort;
		return port == null ? -1 : port;
	}

	/**
	 * Called on server start and with null on shutdown.
	 */
	public static void setServer(MinecraftServer newServer) {
		server = newServer;
	}

	/**
	 * @return the running server, or null when none is loaded. Callers must treat null as "no data
	 *         available" rather than as an error worth throwing.
	 */
	public static MinecraftServer getServer() {
		return server;
	}
}
