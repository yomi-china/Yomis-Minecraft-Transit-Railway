package mtr.webdashboard.servlet;

import mtr.webdashboard.WebDashboardDataService;
import net.minecraft.server.MinecraftServer;

/**
 * {@code GET /api/meta} - the vocabularies and limits the front end needs.
 * <p>
 * Split from {@code /api/data} because it does not depend on a world at all: the mode list and the
 * numeric limits are fixed by the mod, and the page can fetch and cache them before any world is
 * loaded. It also spares the front end from hard-coding {@code CABLE_CAR}'s quirks, which are easy to
 * get subtly wrong and are already expressed by the model.
 * <p>
 * Requires a session, like every other data endpoint. The information is not sensitive, but keeping
 * one rule for the whole {@code /api/data*} surface is easier to reason about than an exception.
 */
public class MetaServletHandler extends WebDashboardAsyncServlet {

	public static final String PATH = "/api/meta";

	@Override
	protected String buildResponse(MinecraftServer server) {
		return WebDashboardDataService.buildMeta(server).toString();
	}
}
