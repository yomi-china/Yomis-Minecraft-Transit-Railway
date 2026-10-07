package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardDataService;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.function.Function;

public class WebDashboardAsyncServlet extends HttpServlet {

	private final Function<MinecraftServer, String> responseBuilder;

	public WebDashboardAsyncServlet(Function<MinecraftServer, String> responseBuilder) {
		this.responseBuilder = responseBuilder;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		if (!session.isAuthenticated()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "not_authenticated");
			return;
		}

		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "no_server");
			return;
		}

		final AsyncContext asyncContext;
		try {
			asyncContext = request.startAsync();
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not start an async context for " + request.getRequestURI() + ": " + e);
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "async_unavailable");
			return;
		}

		asyncContext.setTimeout(WebDashboardServletHandler.ASYNC_TIMEOUT_MILLIS);
		asyncContext.addListener(new WebDashboardServletHandler.TimeoutListener(asyncContext));

		try {
			server.execute(() -> buildAndWrite(asyncContext, server, request));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not schedule work on the game thread: " + e);
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
		}
	}

	private void buildAndWrite(AsyncContext asyncContext, MinecraftServer server, HttpServletRequest request) {
		String json;
		try {
			json = responseBuilder.apply(server);
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Failed to build the response for " + request.getRequestURI() + ": " + e);
			e.printStackTrace();
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "build_failed");
			return;
		}

		WebDashboardServletHandler.completeWith(asyncContext, HttpServletResponse.SC_OK, json);
	}
}
