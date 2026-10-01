package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardServer;
import mtr.webdashboard.WebDashboardRuntime;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/health} - diagnostics for the static page, not for the game.
 * <p>
 * Exists because "the browser shows 404" has two causes that look identical from the outside: the
 * web assets never made it into the jar, or they did but the static handler cannot see them. This
 * reports which, and is reachable even when the page itself is not - so it can be checked from a
 * browser without reading the game log.
 * <p>
 * Deliberately says nothing about world or player data.
 */
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
