package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardPermissions;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTokenStore;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginServletHandler extends HttpServlet {

	public static final String PATH = "/api/login";

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) {
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

		final WebDashboardTokenStore.Session session = WebDashboardTokenStore.redeemLoginToken(token);
		if (session == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_token");
			return;
		}

		WebDashboardSession.writeSessionCookie(request, response, session.token());

		final JsonObject result = new JsonObject();
		result.addProperty("authenticated", true);
		result.addProperty("canEdit", WebDashboardPermissions.canEdit(session.uuid));
		result.addProperty("username", session.username);
		WebDashboardServletHandler.sendJson(response, result.toString());
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed");
	}
}
