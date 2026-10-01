package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardSession;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/session} - who is this visitor, and may they edit.
 * <p>
 * Separate from {@code /api/status} on purpose: status describes the service (and is useful even to
 * an anonymous visitor), while this describes the caller. Keeping them apart means the page can
 * refresh its own identity after signing in or out without re-reading service state, and it gives
 * stage 3 a single endpoint for the front end to gate on.
 * <p>
 * Never returns 401. An anonymous caller is a normal state, not an error, so it always answers 200
 * with {@code authenticated: false} - which is also why the page has no special case for it.
 */
public class SessionServletHandler extends HttpServlet {

	public static final String PATH = "/api/session";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);

		final JsonObject body = new JsonObject();
		body.addProperty("authenticated", session.isAuthenticated());
		// canEdit is derived per request rather than stored with the cookie, so a revoked grant or a
		// /deop shows up here on the very next call.
		body.addProperty("canEdit", session.canEdit());
		body.addProperty("username", session.getUsername());

		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
