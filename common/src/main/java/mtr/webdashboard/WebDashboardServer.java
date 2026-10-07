package mtr.webdashboard;

import java.net.URL;
import java.nio.file.Path;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import mtr.MTR;
import mtr.packet.IPacket;
import mtr.webdashboard.servlet.HealthServletHandler;
import mtr.webdashboard.servlet.LoginServletHandler;
import mtr.webdashboard.servlet.LogoutServletHandler;
import mtr.webdashboard.servlet.RootIndexFilter;
import mtr.webdashboard.servlet.SessionServletHandler;
import mtr.webdashboard.servlet.StatusServletHandler;
import mtr.webdashboard.servlet.WebDashboardAsyncServlet;
import mtr.webdashboard.servlet.WebDashboardWriteServlet;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public final class WebDashboardServer {

	private static final String RESOURCE_ROOT = "/assets/mtr/webdashboard/";
	private static final String INDEX_PAGE = "index.html";
	private static final int THREAD_POOL_MAX = 40;
	private static final int THREAD_POOL_MIN = 4;
	private static final int THREAD_POOL_IDLE_TIMEOUT_MS = 120;

	private static Server server = null;
	private static ServerConnector serverConnector = null;
	private static String resourceDiagnostic = "not initialised";

	public static synchronized String getResourceDiagnostic() {
		return resourceDiagnostic;
	}

	private WebDashboardServer() {
	}

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

		context.setWelcomeFiles(new String[]{INDEX_PAGE});

		final ServletHolder staticHolder = new ServletHolder("webdashboard-static", DefaultServlet.class);
		staticHolder.setInitParameter("dirAllowed", "false");
		staticHolder.setInitParameter("cacheControl", "no-store, no-cache, must-revalidate");
		staticHolder.setInitParameter("welcomeFiles", INDEX_PAGE);

		context.addServlet(new ServletHolder(new StatusServletHandler()), StatusServletHandler.PATH);
		context.addServlet(new ServletHolder(new HealthServletHandler()), HealthServletHandler.PATH);
		context.addServlet(new ServletHolder(new SessionServletHandler()), SessionServletHandler.PATH);
		context.addServlet(new ServletHolder(new LoginServletHandler()), LoginServletHandler.PATH);
		context.addServlet(new ServletHolder(new LogoutServletHandler()), LogoutServletHandler.PATH);
		context.addServlet(new ServletHolder(new WebDashboardAsyncServlet(serverObject -> WebDashboardDataService.buildMeta(serverObject).toString())), "/api/meta");
		context.addServlet(new ServletHolder(new WebDashboardAsyncServlet(serverObject -> WebDashboardDataService.buildData(serverObject).toString())), "/api/data");

		context.addServlet(new ServletHolder(new WebDashboardWriteServlet("station", IPacket.PACKET_UPDATE_STATION)), "/api/station/*");
		context.addServlet(new ServletHolder(new WebDashboardWriteServlet("route", IPacket.PACKET_UPDATE_ROUTE)), "/api/route/*");
		context.addServlet(new ServletHolder(new WebDashboardWriteServlet("depot", IPacket.PACKET_UPDATE_DEPOT)), "/api/depot/*");

		context.addServlet(staticHolder, "/");
		context.addFilter(new FilterHolder(new RootIndexFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));

		System.out.println("[MTR-WebDashboard] Registered API paths: "
				+ StatusServletHandler.PATH + ", " + HealthServletHandler.PATH + ", " + SessionServletHandler.PATH
				+ ", " + LoginServletHandler.PATH + ", " + LogoutServletHandler.PATH
				+ ", /api/meta, /api/data"
				+ "; writing: PATCH /api/station/<id>, /api/route/<id>, /api/depot/<id>"
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

	public static synchronized int getPort() {
		final int activePort = WebDashboardRuntime.getPort();
		return activePort > 0 ? activePort : WebDashboardSettings.get().getPort();
	}

	public static synchronized String getUrl() {
		return String.format("http://%s:%s/", WebDashboardSettings.get().getClientHost(), getPort());
	}

	public static synchronized String getLoginUrl(String token) {
		return token == null || token.isEmpty() ? getUrl() : getUrl() + "#token=" + token;
	}

	public static void loadSettingsAndStart(Path configDirectory) {
		WebDashboardSettings.load(configDirectory);
		start();
	}
}
