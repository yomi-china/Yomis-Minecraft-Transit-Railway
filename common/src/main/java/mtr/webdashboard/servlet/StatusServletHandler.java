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

/**
 * {@code GET /api/status} - the first thing the web page asks for.
 * <p>
 * The response shape was fixed in stage 1 so later stages only have to fill in values rather than
 * change the contract:
 * <ul>
 *     <li>{@code authenticated} / {@code canEdit} / {@code username} now come from the session
 *         cookie. {@code canEdit} is recomputed on every request, never stored, so revoking access or
 *         running {@code /deop} takes effect on the next call.</li>
 *     <li>{@code features.terrain} is the hook for the stage 3 decision to render a top-down block
 *         map from the server side. The page reads it to decide whether to draw a terrain layer, so
 *         switching that on later needs no front-end restructuring.</li>
 *     <li>{@code features.editing} gates the editing affordances for the same reason, and is true
 *         only for a caller who may actually edit.</li>
 * </ul>
 * This handler must not touch Minecraft state: it runs on a Jetty thread. Reading a session does
 * consult the player list for the op-level fallback, which is a plain map lookup on the server
 * object rather than world access.
 */
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
		// Whether the operator opened this service to the network. The page warns about the plain-HTTP
		// consequence, because that decision is what makes the sign-in token sniffable.
		body.addProperty("lanAccess", WebDashboardSettings.get().isAllowLanAccess());
		// Enough to tell an operator "nobody can edit" without exposing the table itself and without
		// requiring them to be signed in.
		body.addProperty("permissionEntries", WebDashboardPermissions.describeTable());
		body.add("features", features);

		WebDashboardServletHandler.sendJson(response, body.toString());
	}
}
