package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTokenStore;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
