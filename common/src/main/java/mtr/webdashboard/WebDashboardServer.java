package mtr.webdashboard;

import mtr.MTR;
import mtr.mappings.Text;
import mtr.webdashboard.servlet.HealthServletHandler;
import mtr.webdashboard.servlet.RootIndexFilter;
import mtr.webdashboard.servlet.StatusServletHandler;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.net.URL;
import java.nio.file.Path;

/**
 * The HTTP service behind the in-game "Web Dashboard" button.
 * <p>
 * Deliberately separate from {@code mtr.servlet.Webserver}: that class keeps its {@link Server} and
 * {@link ServerConnector} in private statics tied to the railway map's port file, and both the
 * client and the integrated server call its {@code init()}, so whichever runs last silently
 * replaces the earlier reference and the first server can never be stopped. Here the state lives in
 * {@link WebDashboardRuntime} and every entry point is synchronized and idempotent.
 * <p>
 * This service is a different port, a different document root and a different security posture from
 * the railway map: loopback-only by default, no directory listing, and no
 * {@code Access-Control-Allow-Origin} header.
 */
public final class WebDashboardServer {

	private static final String RESOURCE_ROOT = "/assets/mtr/webdashboard/";
	private static final String INDEX_PAGE = "index.html";
	private static final int THREAD_POOL_MAX = 40;
	private static final int THREAD_POOL_MIN = 4;
	private static final int THREAD_POOL_IDLE_TIMEOUT_MS = 120;

	private static Server server = null;
	private static ServerConnector serverConnector = null;

	/**
	 * One-line summary of how the document root resolved, surfaced through {@code /api/health}.
	 * <p>
	 * Kept because a 404 on the root path has two completely different causes - the page never
	 * reached the jar, or it is present but the handler could not see it - and a browser can read
	 * this without anyone digging through the game log.
	 */
	private static String resourceDiagnostic = "not initialised";

	public static synchronized String getResourceDiagnostic() {
		return resourceDiagnostic;
	}

	private WebDashboardServer() {
	}

	/**
	 * Binds and starts the service, or does nothing when it is already listening.
	 * <p>
	 * Idempotent on purpose. In single player the client and the integrated server share one JVM and
	 * both try to start this service; whichever gets here first wins and the other becomes a no-op.
	 * Should they race anyway, the loser's bind fails and is reported and swallowed below, so a
	 * duplicate start can never take the game down.
	 */
	public static synchronized void start() {
		if (server != null && server.isRunning()) {
			return;
		}

		final WebDashboardSettings settings = WebDashboardSettings.get();
		final Server newServer = new Server(new QueuedThreadPool(THREAD_POOL_MAX, THREAD_POOL_MIN, THREAD_POOL_IDLE_TIMEOUT_MS));
		final ServerConnector newConnector = new ServerConnector(newServer);
		newConnector.setHost(settings.getBindHost());
		newConnector.setPort(settings.getPort());
		newServer.setConnectors(new Connector[]{newConnector});

		final ServletContextHandler context = new ServletContextHandler();
		newServer.setHandler(context);

		// Guarded: on a misconfigured resource pipeline the lookup fails rather than throwing, and the
		// JSON endpoints below stay reachable so the problem is diagnosable from a browser.
		final URL resourceRoot = MTR.class.getResource(RESOURCE_ROOT);
		if (resourceRoot == null) {
			resourceDiagnostic = RESOURCE_ROOT + " is NOT on the classpath - the web assets did not reach the jar";
			System.out.println("[MTR-WebDashboard] WARNING: " + resourceDiagnostic + ". Only the API endpoints will respond.");
		} else {
			try {
				final Resource baseResource = Resource.newResource(resourceRoot.toURI());
				context.setBaseResource(baseResource);
				final Resource indexResource = baseResource.addPath(INDEX_PAGE);
				resourceDiagnostic = baseResource + " (exists=" + baseResource.exists() + ", directory=" + baseResource.isDirectory() + ", " + INDEX_PAGE + "=" + indexResource.exists() + ")";
				System.out.println("[MTR-WebDashboard] Resource root " + resourceDiagnostic);
			} catch (Exception e) {
				resourceDiagnostic = "could not resolve " + resourceRoot + ": " + e;
				System.out.println("[MTR-WebDashboard] WARNING: " + resourceDiagnostic);
			}
		}

		// Belt and braces for the bare address. A directory request needs a welcome file, and setting it
		// on the context is the documented way; the "welcomeFiles" init parameter below duplicates it so
		// the static handler still behaves if this context setting is ever dropped.
		context.setWelcomeFiles(new String[]{INDEX_PAGE});

		final ServletHolder staticHolder = new ServletHolder("webdashboard-static", DefaultServlet.class);
		// Unlike the railway map's DefaultServlet, listing directories would expose the jar layout and
		// any file dropped into the folder, so it stays off.
		staticHolder.setInitParameter("dirAllowed", "false");
		staticHolder.setInitParameter("cacheControl", "max-age=0,public");
		// Inert while the root filter intercepts "/", but kept so the static handler would still pick the
		// right page if the filter were ever removed.
		staticHolder.setInitParameter("welcomeFiles", INDEX_PAGE);

		// The exact API paths are registered before the catch-all, so they win over the static handler.
		context.addServlet(new ServletHolder(new StatusServletHandler()), StatusServletHandler.PATH);
		context.addServlet(new ServletHolder(new HealthServletHandler()), HealthServletHandler.PATH);
		// "/" must NOT be claimed by a servlet: in Jetty that is the default servlet mapping, and a second
		// servlet there fails the entire context with "Multiple servlets map to path /" - which took the
		// service down instead of merely 404ing. A filter handles the root instead, rewriting it to
		// index.html before the static handler resolves it.
		context.addServlet(staticHolder, "/");
		context.addFilter(new FilterHolder(new RootIndexFilter()), "/*", RootIndexFilter.dispatcherTypes());

		try {
			newServer.start();
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not start on " + settings.getBindHost() + ":" + settings.getPort() + " (is the port already in use?): " + e);
			try {
				newServer.stop();
			} catch (Exception ignored) {
			}
			WebDashboardRuntime.clear();
			return;
		}

		server = newServer;
		serverConnector = newConnector;
		WebDashboardRuntime.set(settings.getPort());
		System.out.println("[MTR-WebDashboard] Listening on " + getUrl());
	}

	/**
	 * Stops the service and releases the port. Safe to call when it was never started, and safe to
	 * call twice.
	 */
	public static synchronized void stop() {
		if (server != null) {
			try {
				server.stop();
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Error while stopping: " + e);
			}
		}
		server = null;
		serverConnector = null;
		WebDashboardRuntime.clear();
	}

	public static synchronized boolean isRunning() {
		return server != null && server.isRunning();
	}

	/**
	 * @return the port in use, falling back to the configured port when the service is not running so
	 *         that a URL can still be built and handed to the player.
	 */
	public static synchronized int getPort() {
		final int activePort = WebDashboardRuntime.getPort();
		return activePort > 0 ? activePort : WebDashboardSettings.get().getPort();
	}

	public static synchronized String getUrl() {
		return String.format("http://%s:%s/", WebDashboardSettings.get().getClientHost(), getPort());
	}

	/**
	 * Opens the dashboard in the system browser. Called from the in-game button, so it must never
	 * throw into the screen's click handler - a machine with no browser, or a sandbox that blocks
	 * shelling out, would otherwise crash the dashboard screen.
	 * <p>
	 * Same call the mod already uses for its Patreon link in {@code ConfigScreen}.
	 */
	public static synchronized void openInBrowser() {
		final String url = getUrl();
		try {
			Util.getPlatform().openUri(url);
			// Told even on success: from a full-screen game the player may not notice the tab, and the
			// same line doubles as the manual fallback when the browser fails to appear.
			narrate(Text.translatable("gui.mtr.web_dashboard_opened", url).getString());
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not open a browser, please visit " + url + " manually: " + e);
			narrate(Text.translatable("gui.mtr.web_dashboard_open_failed", url).getString());
		}
	}

	/**
	 * Shows a message to the player when there is one. The client-side fallback may reach here before
	 * a world is joined, in which case the console line is the only output.
	 */
	private static void narrate(String message) {
		try {
			final LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.displayClientMessage(Text.literal(message), false);
			}
		} catch (Exception ignored) {
			// Minecraft.getInstance() is unavailable on a dedicated server; nothing to report to.
		}
	}

	/**
	 * Convenience for the lifecycle hooks: remembers the config directory and starts the service.
	 *
	 * @param configDirectory the {@code config} directory of the client or server whose settings
	 *                        should be used.
	 */
	public static void loadSettingsAndStart(Path configDirectory) {
		WebDashboardSettings.load(configDirectory);
		start();
	}
}
