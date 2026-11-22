package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardServer;
import mtr.webdashboard.WebDashboardRuntime;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HealthServletHandler extends HttpServlet {

	public static final String PATH = "/api/health";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final JsonObject body = new JsonObject();
		body.addProperty("running", WebDashboardRuntime.isRunning());
		body.addProperty("port", WebDashboardRuntime.getPort() > 0 ? WebDashboardRuntime.getPort() : -1);
		body.addProperty("resourceRoot", WebDashboardServer.getResourceDiagnostic());
		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
