package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTokenStore;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code POST /api/logout} - ends this browser's session.
 * <p>
 * Only the one session: a player may be signed in on several machines, and signing out of one is not
 * a statement about the others. Revoking access itself is a different operation and does clear
 * everything, via {@link WebDashboardTokenStore#revokePlayer}.
 * <p>
 * Idempotent, and always reports success. There is nothing useful to tell a caller whose session was
 * already gone, and a distinguishing response would leak whether a cookie was live.
 */
public class LogoutServletHandler extends HttpServlet {

	public static final String PATH = "/api/logout";

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) {
		if (!WebDashboardSession.isSameOrigin(request)) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_FORBIDDEN, "cross_origin");
			return;
		}

		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		WebDashboardTokenStore.discardSession(session.getToken());
		// Cleared even when there was no session, so a stale or forged cookie is wiped either way.
		WebDashboardSession.clearSessionCookie(response);

		final JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		WebDashboardServletHandler.sendJson(response, result.toString());
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
	}
}
