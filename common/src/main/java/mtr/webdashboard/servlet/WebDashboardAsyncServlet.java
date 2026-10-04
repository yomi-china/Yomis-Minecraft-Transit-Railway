package mtr.webdashboard.servlet;

import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Base class for the read-only data endpoints: gather the payload on the game thread and send it.
 * Subclasses implement {@link #buildResponse}, which is where the model may be touched.
 */
public abstract class WebDashboardAsyncServlet extends WebDashboardAsyncSupport {

	/**
	 * Builds the JSON body. <b>Called on the game thread</b>, so reading {@code RailwayData} here is safe.
	 *
	 * @param server the running server, never null.
	 * @throws Exception reported to the client as a 500, with the message logged.
	 */
	protected abstract String buildResponse(MinecraftServer server) throws Exception;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		// A session is required, but not edit rights: an account that may only look at the dashboard may still
		// see the data. The write endpoints add the stricter check.
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		if (!session.isAuthenticated()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "not_authenticated");
			return;
		}

		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			// Distinct from an empty result, so the front end can tell "no world is loaded" from "there is a
			// world and it has no stations".
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

		asyncContext.setTimeout(ASYNC_TIMEOUT_MILLIS);
		asyncContext.addListener(new TimeoutListener(asyncContext));

		try {
			server.execute(() -> buildAndWrite(asyncContext, server, request));
		} catch (Exception e) {
			// Thrown when the server is shutting down and refuses new tasks.
			System.out.println("[MTR-WebDashboard] Could not schedule work on the game thread: " + e);
			completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
		}
	}

	/**
	 * Runs on the game thread: builds the payload, then writes it.
	 * <p>
	 * The write happens here rather than on another thread so only one thread ever touches this response,
	 * which is what makes the {@code synchronized} block in {@code completeWith} sufficient.
	 */
	private void buildAndWrite(AsyncContext asyncContext, MinecraftServer server, HttpServletRequest request) {
		String json;
		try {
			json = buildResponse(server);
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Failed to build the response for " + request.getRequestURI() + ": " + e);
			e.printStackTrace();
			completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "build_failed");
			return;
		}

		completeWith(asyncContext, HttpServletResponse.SC_OK, json);
	}
}
