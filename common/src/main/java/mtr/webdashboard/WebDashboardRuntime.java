package mtr.webdashboard;

/**
 * Tracks the live state of the new web dashboard HTTP service so that any part of the mod
 * (the in-game dashboard button, the client-side fallback starter, later the servlets themselves)
 * can find out whether the service is actually listening and on which port.
 * <p>
 * This deliberately mirrors {@code mtr.servlet.Webserver}, which keeps the same kind of state in
 * mutable public statics. Here it is private and guarded so that the client and the integrated
 * server - which share a single JVM in single player - cannot observe a half-written port.
 * <p>
 * Note that {@link #isRunning()} reports whether the HTTP server was bound successfully. It says
 * nothing about whether a world is loaded; a dedicated server or a player sitting on the title
 * screen can both have the service up.
 */
public final class WebDashboardRuntime {

	private static volatile Integer activePort = null;

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
}
