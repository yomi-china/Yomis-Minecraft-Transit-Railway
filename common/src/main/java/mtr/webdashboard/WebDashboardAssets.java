package mtr.webdashboard;

/**
 * Locations shared by the server's Jetty and the client's local static server.
 * <p>
 * The browser page is served from the jar by the client in multiplayer and by the server in
 * single player. Both must agree on the root, or one mode would show a stale or missing page.
 */
public final class WebDashboardAssets {

	public static final String RESOURCE_ROOT = "/assets/mtr/webdashboard/";
	public static final String INDEX_PAGE = "index.html";

	private WebDashboardAssets() {
	}
}
