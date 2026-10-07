package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.Keys;
import mtr.webdashboard.WebDashboardPermissions;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardSettings;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class StatusServletHandler extends HttpServlet {

	public static final String PATH = "/api/status";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		final boolean canEdit = session.canEdit();

		final JsonObject features = new JsonObject();
		features.addProperty("terrain", false);
		features.addProperty("editing", canEdit);

		final JsonObject body = new JsonObject();
		body.addProperty("version", Keys.MOD_VERSION);
		body.addProperty("port", WebDashboardRuntime.getPort() > 0 ? WebDashboardRuntime.getPort() : WebDashboardSettings.get().getPort());
		body.addProperty("running", WebDashboardRuntime.isRunning());
		body.addProperty("authenticated", session.isAuthenticated());
		body.addProperty("canEdit", canEdit);
		body.addProperty("username", session.getUsername());
		body.addProperty("lanAccess", WebDashboardSettings.get().isAllowLanAccess());
		body.addProperty("permissionEntries", WebDashboardPermissions.describeTable());
		body.add("features", features);

		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
