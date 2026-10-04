package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardEdits;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Locale;

/**
 * Base class for the write endpoints: {@code PATCH /api/<kind>/<id>}.
 *
 * A sibling of {@link WebDashboardAsyncServlet}, both extending {@link WebDashboardAsyncSupport}: the read
 * path adds "produce this JSON" and this one adds "apply this patch".
 *
 * The policy lives in {@link WebDashboardEdits}; this class is only the HTTP shape around it. The object id
 * is the last path segment and the kind comes from the concrete subclass. Only PATCH is accepted, carrying
 * only the fields to change.
 */
public abstract class WebDashboardWriteServlet extends WebDashboardAsyncSupport {

	/** The HTTP method this endpoint answers. */
	private static final String METHOD_PATCH = "PATCH";

	/** @return the resource name this endpoint edits: 'station', 'route' or 'depot'. */
	protected abstract String getKind();

	/** @return the packet clients expect when this kind of object changes. */
	protected abstract ResourceLocation getPacketId();

	/**
	 * Applies one patch. Called on the Jetty thread; the implementation hops to the game thread before
	 * touching the model.
	 */
	protected abstract void handlePatch(HttpServletRequest request, HttpServletResponse response);

	/**
	 * Dispatches the request, answering 405 with an {@code Allow} header for anything but PATCH.
	 *
	 * Deliberately not named {@code doPatch}: overriding {@code service} instead avoids depending on which
	 * Servlet API version declares that method.
	 */
	@Override
	public void service(HttpServletRequest request, HttpServletResponse response) {
		if (METHOD_PATCH.equalsIgnoreCase(request.getMethod())) {
			handlePatch(request, response);
			return;
		}

		response.setHeader("Allow", METHOD_PATCH);
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
	}

	/**
	 * The shared body of a patch: validate the request, hop to the game thread, apply, answer. Subclasses call
	 * this from {@link #handlePatch}.
	 */
	protected void applyPatchRequest(HttpServletRequest request, HttpServletResponse response) {
		// Before anything is read or changed. SameSite=Strict on the session cookie already blocks this, so a
		// request that fails here is either a forgery or a non-browser client.
		if (!WebDashboardSession.isSameOrigin(request)) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_FORBIDDEN, "cross_origin");
			return;
		}

		// Same-origin fetch always sets a JSON content type; a form post cannot produce one. Refusing
		// anything else means a stray HTML form cannot mutate railway data by accident.
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

		// Read before the async context starts: once the request is handed to the game thread the reader may no
		// longer be valid.
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
		asyncContext.setTimeout(ASYNC_TIMEOUT_MILLIS);
		asyncContext.addListener(new TimeoutListener(asyncContext));

		try {
			server.execute(() -> applyAndWrite(asyncContext, session, objectId, body));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not schedule an edit on the game thread: " + e);
			completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
		}
	}

	/**
	 * Runs on the game thread: checks permission, applies the change, writes the answer.
	 *
	 * The permission check happens here rather than on the Jetty thread because {@code canEdit} consults the
	 * player's op level, and because the editor must be online - which can change between the two threads.
	 */
	private void applyAndWrite(AsyncContext asyncContext, WebDashboardSession session, long objectId, JsonObject body) {
		if (!session.canEdit()) {
			completeWithError(asyncContext,
					session.isAuthenticated() ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED,
					session.isAuthenticated() ? "not_permitted" : "not_authenticated");
			return;
		}

		try {
			final JsonObject result = WebDashboardEdits.applyPatch(getKind(), getPacketId(), session.getUuid(), objectId, body);
			completeWith(asyncContext, HttpServletResponse.SC_OK, result.toString());
		} catch (WebDashboardEdits.EditFailure e) {
			// The status and code come from the edit layer, where the policy lives.
			completeWith(asyncContext, e.getStatus(), e.toJson().toString());
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Failed to apply a " + getKind() + " edit to " + objectId + ": " + e);
			e.printStackTrace();
			completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "edit_failed");
		}
	}

	/**
	 * Reads the object id from the last path segment. Ids are published as JSON strings because they exceed
	 * what a double holds exactly, but the value is a long.
	 *
	 * @return the id, or {@link Long#MIN_VALUE} when the segment is missing or not a number.
	 */
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
