package mtr.webdashboard.servlet;

import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardSettings;
import mtr.webdashboard.WebDashboardTrainService;
import mtr.webdashboard.WebDashboardTrainStreams;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Live train stream (server-sent events).
 *
 * <p>Enabled by default. The page probes the stream and falls back to polling {@code /api/trains}
 * when it cannot connect, so an environment that cannot carry a long-lived response degrades on its
 * own instead of showing stale trains.
 */
public class TrainsStreamServletHandler extends HttpServlet {

	public static final String PATH = "/api/trains/stream";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		if (!session.isAuthenticated()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "not_authenticated");
			return;
		}

		if (!WebDashboardSettings.get().isTrainsStreamEnabled()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "stream_disabled");
			return;
		}

		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "no_server");
			return;
		}

		final WebDashboardTrainService.Query query = TrainsServletHandler.parseQuery(request, response);
		if (query == null) {
			return;
		}

		int hz = WebDashboardSettings.get().getTrainsPushHz();
		final String rawHz = request.getParameter("hz");
		if (rawHz != null && !rawHz.trim().isEmpty()) {
			try {
				hz = Integer.parseInt(rawHz.trim());
			} catch (NumberFormatException e) {
				WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_hz");
				return;
			}
			if (hz < 1 || hz > 10) {
				WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_hz");
				return;
			}
		}

		final AsyncContext asyncContext;
		try {
			asyncContext = request.startAsync();
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not start an async context for " + PATH + ": " + e);
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "async_unavailable");
			return;
		}

		asyncContext.setTimeout(0);
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/event-stream; charset=utf-8");
		response.setHeader("Cache-Control", "no-cache, no-store");
		response.setHeader("X-Accel-Buffering", "no");

		final WebDashboardTrainStreams.Connection connection = new WebDashboardTrainStreams.Connection(asyncContext, session, query, hz);
		asyncContext.addListener(new AsyncListener() {
			@Override
			public void onTimeout(AsyncEvent event) {
				connection.close("timeout");
			}

			@Override
			public void onComplete(AsyncEvent event) {
				connection.close("complete");
			}

			@Override
			public void onError(AsyncEvent event) {
				connection.close("error");
			}

			@Override
			public void onStartAsync(AsyncEvent event) {
			}
		});

		WebDashboardTrainStreams.register(connection);
	}
}
