package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardPermissions;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTokenStore;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code POST /api/login} - exchanges the one-time token from the URL fragment for a session cookie.
 * <p>
 * POST only. A GET would put the token in the request line, which is exactly what moving it to the
 * fragment was meant to avoid, and it would also be cacheable and prefetchable.
 * <p>
 * The token arrives in the request <em>body</em>, never the query string, for the same reason.
 * <p>
 * Every failure returns the same bare {@code 401} with no detail about which check failed. The
 * visitor cannot act on the difference, and saying "that token exists but expired" would confirm a
 * guessed token.
 */
public class LoginServletHandler extends HttpServlet {

	public static final String PATH = "/api/login";

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) {
		// Defence in depth behind the SameSite=Strict cookie: a sign-in attempt from another origin is
		// refused outright.
		if (!WebDashboardSession.isSameOrigin(request)) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_FORBIDDEN, "cross_origin");
			return;
		}

		final JsonObject body = WebDashboardSession.readJsonBody(request);
		if (body == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "bad_request");
			return;
		}

		final String token = WebDashboardSession.getString(body, "token");
		if (token == null || token.isEmpty()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "missing_token");
			return;
		}

		// Single use, and permission is re-checked inside: a grant withdrawn between the in-game click
		// and the browser loading still refuses the sign-in.
		final String sessionToken = WebDashboardTokenStore.redeemLoginToken(token);
		if (sessionToken == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token");
			return;
		}

		WebDashboardSession.writeSessionCookie(request, response, sessionToken);

		// Answer with the identity directly so the page can render in one round trip instead of
		// following up with /api/session. Built from the token, not re-read from the request: the
		// cookie has only just been set on the response, so the request's cookie jar does not have it.
		final WebDashboardTokenStore.Session session = WebDashboardTokenStore.validateSession(sessionToken);
		final JsonObject result = new JsonObject();
		result.addProperty("authenticated", session != null);
		result.addProperty("canEdit", session != null && WebDashboardPermissions.canEdit(session.uuid));
		result.addProperty("username", session == null ? null : session.username);
		WebDashboardServletHandler.sendJson(response, result.toString());
	}

	/**
	 * Explicitly refused rather than falling through to a 405, so the reason is visible in a browser
	 * and in the log if someone hand-crafts the URL.
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
	}
}
