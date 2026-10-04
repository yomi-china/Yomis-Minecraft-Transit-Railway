package mtr.webdashboard;

import java.net.URL;
import java.nio.file.Path;
import mtr.MTR;
import mtr.webdashboard.servlet.DataServletHandler;
import mtr.webdashboard.servlet.DepotPatchServlet;
import mtr.webdashboard.servlet.HealthServletHandler;
import mtr.webdashboard.servlet.LoginServletHandler;
import mtr.webdashboard.servlet.LogoutServletHandler;
import mtr.webdashboard.servlet.MetaServletHandler;
import mtr.webdashboard.servlet.RootIndexFilter;
import mtr.webdashboard.servlet.RoutePatchServlet;
import mtr.webdashboard.servlet.SessionServletHandler;
import mtr.webdashboard.servlet.StationPatchServlet;
import mtr.webdashboard.servlet.StatusServletHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

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
	 * Turns a collection path into the prefix mapping Jetty needs for a sub-path endpoint.
	 *
	 * The trailing wildcard is not decoration: Jetty only populates {@code getPathInfo()} for a prefix
	 * mapping, and it is the id in that suffix that the write servlet reads. Registering the bare
	 * {@code /api/station} instead would leave {@code getPathInfo()} null, and the id would have to be
	 * parsed out of the raw URI - which is the fragile thing this avoids.
	 *
	 * Lives here, next to the registration that depends on it, rather than on the servlet. It is a fact
	 * about how this server is wired, not about what an endpoint does, and putting it on the servlet made
	 * it look like part of the servlet's API - which is how it first ended up {@code protected} and
	 * unreachable from this class.
	 */
	private static String prefixMapping(String collectionPath) {
		return collectionPath + "/*";
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
		// Caching deliberately disabled. The railway map uses "max-age=0,public", which still permits a
		// browser to reuse its copy after a revalidation that lands on an unchanged timestamp. After a
		// rebuild that leaves a stale page script in place, which presents as "the site was never
		// updated" and is far more confusing than the few kilobytes of local disk I/O saved here.
		staticHolder.setInitParameter("cacheControl", "no-store, no-cache, must-revalidate");
		// Inert while the root filter intercepts "/", but kept so the static handler would still pick the
		// right page if the filter were ever removed.
		staticHolder.setInitParameter("welcomeFiles", INDEX_PAGE);

		// The exact API paths are registered before the catch-all, so they win over the static handler.
		context.addServlet(new ServletHolder(new StatusServletHandler()), StatusServletHandler.PATH);
		context.addServlet(new ServletHolder(new HealthServletHandler()), HealthServletHandler.PATH);
		context.addServlet(new ServletHolder(new SessionServletHandler()), SessionServletHandler.PATH);
		context.addServlet(new ServletHolder(new LoginServletHandler()), LoginServletHandler.PATH);
		context.addServlet(new ServletHolder(new LogoutServletHandler()), LogoutServletHandler.PATH);
		// Read-only railway data. These hop to the game thread to build their payloads, which is what
		// WebDashboardAsyncServlet is for.
		context.addServlet(new ServletHolder(new MetaServletHandler()), MetaServletHandler.PATH);
		context.addServlet(new ServletHolder(new DataServletHandler()), DataServletHandler.PATH);

		// Write endpoints. Prefix mappings, so the object id arrives as the path suffix.
		//
		// PATCH only. The servlet container dispatches the method itself, and a request that arrives as GET
		// or POST therefore gets the 405 that HttpServlet produces - which is the honest answer, rather than
		// silently treating a GET as an edit.
		context.addServlet(new ServletHolder(new StationPatchServlet()), prefixMapping(StationPatchServlet.PATH));
		context.addServlet(new ServletHolder(new RoutePatchServlet()), prefixMapping(RoutePatchServlet.PATH));
		context.addServlet(new ServletHolder(new DepotPatchServlet()), prefixMapping(DepotPatchServlet.PATH));

		// "/" must NOT be claimed by a servlet: in Jetty that is the default servlet mapping, and a second
		// servlet there fails the entire context with "Multiple servlets map to path /" - which took the
		// service down instead of merely 404ing. A filter handles the root instead, rewriting it to
		// index.html before the static handler resolves it.
		context.addServlet(staticHolder, "/");
		context.addFilter(new FilterHolder(new RootIndexFilter()), "/*", RootIndexFilter.dispatcherTypes());

		// Listed at startup on purpose. A path that was never registered falls through to the static
		// handler and answers 404, which from the browser looks identical to a route that was registered
		// and then failed. Printing what is live turns that ambiguity into one line of log.
		System.out.println("[MTR-WebDashboard] Registered API paths: "
				+ StatusServletHandler.PATH + ", " + HealthServletHandler.PATH + ", " + SessionServletHandler.PATH
				+ ", " + LoginServletHandler.PATH + ", " + LogoutServletHandler.PATH
				+ ", " + MetaServletHandler.PATH + ", " + DataServletHandler.PATH
				+ "; writing: PATCH " + StationPatchServlet.PATH + "/<id>, " + RoutePatchServlet.PATH + "/<id>, " + DepotPatchServlet.PATH + "/<id>"
				+ "; everything else is served as a static file.");

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
	 * The address a browser should open in order to sign in: the dashboard URL with the one-time token
	 * in its fragment.
	 * <p>
	 * A fragment rather than a query parameter because fragments are never sent to the server, so the
	 * token stays out of access logs, out of {@code Referer} headers, and out of the reach of any other
	 * page. The page exchanges it for a session cookie the moment it loads and then wipes the fragment
	 * from the address bar.
	 * <p>
	 * The token is single-use and expires within a couple of minutes, so a URL that leaks through
	 * history or a screen recording is worthless shortly afterwards.
	 * <p>
	 * Nothing here launches anything: the caller decides. That is deliberate, because a browser window
	 * appearing because a button was pressed in a game is unwelcome, and there was previously no way to
	 * obtain the address without letting it open.
	 *
	 * @param token a token from {@link WebDashboardTokenStore#issueLoginToken}, or null or empty for
	 *              the plain read-only address.
	 */
	public static synchronized String getLoginUrl(String token) {
		return token == null || token.isEmpty() ? getUrl() : getUrl() + "#token=" + token;
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
