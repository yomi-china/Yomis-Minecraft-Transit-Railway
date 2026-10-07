package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardEdits;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Locale;

public class WebDashboardWriteServlet extends HttpServlet {

	private static final String METHOD_PATCH = "PATCH";

	private final String kind;
	private final ResourceLocation packetId;

	public WebDashboardWriteServlet(String kind, ResourceLocation packetId) {
		this.kind = kind;
		this.packetId = packetId;
	}

	@Override
	public void service(HttpServletRequest request, HttpServletResponse response) {
		if (METHOD_PATCH.equalsIgnoreCase(request.getMethod())) {
			applyPatchRequest(request, response);
			return;
		}

		response.setHeader("Allow", METHOD_PATCH);
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
	}

	protected void applyPatchRequest(HttpServletRequest request, HttpServletResponse response) {
		if (!WebDashboardSession.isSameOrigin(request)) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_FORBIDDEN, "cross_origin");
			return;
		}

		final String contentType = request.getContentType();
		if (contentType == null || !contentType.toLowerCase(Locale.ENGLISH).contains("application/json")) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type");
			return;
		}

		final long objectId = parseId(request);
		if (objectId == Long.MIN_VALUE) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "bad_id");
			return;
		}

		final JsonObject body = WebDashboardSession.readJsonBody(request);
		if (body == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "bad_request");
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

		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		asyncContext.setTimeout(WebDashboardServletHandler.ASYNC_TIMEOUT_MILLIS);
		asyncContext.addListener(new WebDashboardServletHandler.TimeoutListener(asyncContext));

		try {
			server.execute(() -> applyAndWrite(asyncContext, session, objectId, body));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not schedule an edit on the game thread: " + e);
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
		}
	}

	private void applyAndWrite(AsyncContext asyncContext, WebDashboardSession session, long objectId, JsonObject body) {
		if (!session.canEdit()) {
			WebDashboardServletHandler.completeWithError(asyncContext,
					session.isAuthenticated() ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED,
					session.isAuthenticated() ? "not_permitted" : "not_authenticated");
			return;
		}

		try {
			final JsonObject result = WebDashboardEdits.applyPatch(kind, packetId, session.getUuid(), objectId, body);
			WebDashboardServletHandler.completeWith(asyncContext, HttpServletResponse.SC_OK, result.toString());
		} catch (WebDashboardEdits.EditFailure e) {
			WebDashboardServletHandler.completeWith(asyncContext, e.getStatus(), e.toJson().toString());
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Failed to apply a " + kind + " edit to " + objectId + ": " + e);
			e.printStackTrace();
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "edit_failed");
		}
	}

	private static long parseId(HttpServletRequest request) {
		final String path = request.getPathInfo() == null ? request.getRequestURI() : request.getPathInfo();
		if (path == null) {
			return Long.MIN_VALUE;
		}
		final int lastSlash = path.lastIndexOf('/');
		if (lastSlash < 0 || lastSlash == path.length() - 1) {
			return Long.MIN_VALUE;
		}
		try {
			return Long.parseLong(path.substring(lastSlash + 1));
		} catch (NumberFormatException e) {
			return Long.MIN_VALUE;
		}
	}
}
