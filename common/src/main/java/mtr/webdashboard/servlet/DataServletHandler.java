package mtr.webdashboard.servlet;

import mtr.webdashboard.WebDashboardDataService;
import net.minecraft.server.MinecraftServer;

/**
 * {@code GET /api/data} - the full read-only snapshot of every loaded world.
 * <p>
 * <b>Name collision worth knowing about:</b> {@code mtr.servlet.DataServletHandler} already exists and
 * serves {@code /data} for the railway map. Different package, different port, different purpose -
 * this one carries stations, platforms, routes, depots and sidings, and requires a session; that one
 * carries the route diagram's geometry and is open to anyone. They are not interchangeable.
 * <p>
 * No query parameters. Multiple worlds are all returned and the front end picks the dimension it
 * cares about, which keeps the endpoint free of parsing and error branches. {@code dataRevision} in the
 * response lets the page notice that nothing changed without diffing the payload.
 */
public class DataServletHandler extends WebDashboardAsyncServlet {

	public static final String PATH = "/api/data";

	@Override
	protected String buildResponse(MinecraftServer server) {
		return WebDashboardDataService.buildData(server).toString();
	}
}
