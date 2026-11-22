package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardSession;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SessionServletHandler extends HttpServlet {

	public static final String PATH = "/api/session";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);

		final JsonObject body = new JsonObject();
		body.addProperty("authenticated", session.isAuthenticated());
		body.addProperty("canEdit", session.canEdit());
		body.addProperty("username", session.getUsername());

		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
