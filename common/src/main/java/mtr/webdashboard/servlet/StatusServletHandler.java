package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.Keys;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSettings;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@code GET /api/status} - the first thing the web page asks for.
 * <p>
 * The response shape is fixed here so later stages only have to fill in values rather than change
 * the contract:
 * <ul>
 *     <li>{@code authenticated} / {@code canEdit} are always false for now. Stage 2 backs them with
 *         the real session and permission check. Until then the page is honest about being read-only,
 *         which matches the shipped behaviour: opening the URL by hand never grants editing.</li>
 *     <li>{@code features.terrain} is the hook for the stage 3 decision to render a top-down block
 *         map from the server side. The page reads it to decide whether to draw a terrain layer, so
 *         switching that on later needs no front-end restructuring.</li>
 *     <li>{@code features.editing} gates the editing affordances for the same reason.</li>
 * </ul>
 * This handler must not touch Minecraft state: it runs on a Jetty thread. Everything it needs is
 * already in memory.
 */
public class StatusServletHandler extends HttpServlet {

	public static final String PATH = "/api/status";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final JsonObject features = new JsonObject();
		features.addProperty("terrain", false);
		features.addProperty("editing", false);

		final JsonObject body = new JsonObject();
		body.addProperty("version", Keys.MOD_VERSION);
		body.addProperty("port", WebDashboardRuntime.getPort() > 0 ? WebDashboardRuntime.getPort() : WebDashboardSettings.get().getPort());
		body.addProperty("running", WebDashboardRuntime.isRunning());
		body.addProperty("authenticated", false);
		body.addProperty("canEdit", false);
		body.add("features", features);

		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
