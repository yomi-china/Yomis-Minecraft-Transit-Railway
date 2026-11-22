package mtr.webdashboard.servlet;

import mtr.data.TransportMode;
import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTrackService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only viewport query for the track overlay.
 *
 * <p>Only one build may be in flight across the whole server: the build itself runs on the game
 * thread under a hard deadline, and queuing several of them would multiply the tick cost. Requests
 * that arrive while a build is running are told to retry instead of being queued.
 */
public class TracksServletHandler extends HttpServlet {

	public static final String PATH = "/api/tracks";

	private static final long ASYNC_TIMEOUT_MILLIS = 5000L;
	private static final String DEFAULT_DIMENSION = "minecraft:overworld";
	private static final AtomicBoolean BUILD_IN_PROGRESS = new AtomicBoolean(false);

	/** Clears the single-flight gate when the dashboard service stops; a queued build may never run. */
	public static void reset() {
		BUILD_IN_PROGRESS.set(false);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final WebDashboardSession session = WebDashboardSession.fromRequest(request);
		if (!session.isAuthenticated()) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "not_authenticated");
			return;
		}

		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "no_server");
			return;
		}

		final WebDashboardTrackService.Query query = parseQuery(request, response);
		if (query == null) {
			return;
		}

		if (!BUILD_IN_PROGRESS.compareAndSet(false, true)) {
			response.setHeader("Retry-After", "1");
			WebDashboardServletHandler.sendError(response, 429, "tracks_busy");
			return;
		}

		final AsyncContext asyncContext;
		try {
			asyncContext = request.startAsync();
		} catch (Exception e) {
			BUILD_IN_PROGRESS.set(false);
			System.out.println("[MTR-WebDashboard] Could not start an async context for " + PATH + ": " + e);
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "async_unavailable");
			return;
		}

		final AtomicBoolean cancelled = new AtomicBoolean(false);
		asyncContext.setTimeout(ASYNC_TIMEOUT_MILLIS);
		asyncContext.addListener(new AsyncListener() {
			@Override
			public void onTimeout(AsyncEvent event) {
				cancelled.set(true);
				WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_GATEWAY_TIMEOUT, "tracks_timeout");
			}

			@Override
			public void onComplete(AsyncEvent event) {
				cancelled.set(true);
			}

			@Override
			public void onError(AsyncEvent event) {
				cancelled.set(true);
				try {
					asyncContext.complete();
				} catch (Exception ignored) {
				}
			}

			@Override
			public void onStartAsync(AsyncEvent event) {
			}
		});

		try {
			server.execute(() -> {
				try {
					final WebDashboardTrackService.Result result = WebDashboardTrackService.build(server, query, cancelled::get);
					try {
						asyncContext.start(() -> WebDashboardServletHandler.completeWith(asyncContext, result.status(), result.body()));
					} catch (Exception e) {
						WebDashboardServletHandler.completeWith(asyncContext, result.status(), result.body());
					}
				} catch (Throwable t) {
					System.out.println("[MTR-WebDashboard] Failed to build the track response for " + query.dimension() + ": " + t);
					WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "build_failed");
				} finally {
					BUILD_IN_PROGRESS.set(false);
				}
			});
		} catch (Exception e) {
			BUILD_IN_PROGRESS.set(false);
			System.out.println("[MTR-WebDashboard] Could not schedule the track build on the game thread: " + e);
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
		}
	}

	private static WebDashboardTrackService.Query parseQuery(HttpServletRequest request, HttpServletResponse response) {
		final String rawWorld = request.getParameter("world");
		final String dimension;
		if (rawWorld == null || rawWorld.trim().isEmpty()) {
			dimension = DEFAULT_DIMENSION;
		} else {
			final ResourceLocation location = ResourceLocation.tryParse(rawWorld.trim());
			if (location == null) {
				WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_world");
				return null;
			}
			dimension = location.toString();
		}

		final Double minX = parseDouble(request, "minX");
		final Double minZ = parseDouble(request, "minZ");
		final Double maxX = parseDouble(request, "maxX");
		final Double maxZ = parseDouble(request, "maxZ");
		if (minX == null || minZ == null || maxX == null || maxZ == null
				|| !WebDashboardTrackService.isValidCoordinate(minX) || !WebDashboardTrackService.isValidCoordinate(minZ)
				|| !WebDashboardTrackService.isValidCoordinate(maxX) || !WebDashboardTrackService.isValidCoordinate(maxZ)
				|| minX >= maxX || minZ >= maxZ) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_bounds");
			return null;
		}
		if (maxX - minX > WebDashboardTrackService.MAX_SPAN || maxZ - minZ > WebDashboardTrackService.MAX_SPAN) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "bounds_too_large");
			return null;
		}

		final String rawDetail = request.getParameter("detail");
		final int detail;
		try {
			detail = rawDetail == null || rawDetail.trim().isEmpty()
					? WebDashboardTrackService.DEFAULT_DETAIL : Integer.parseInt(rawDetail.trim());
		} catch (NumberFormatException e) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_detail");
			return null;
		}
		if (detail < 0 || detail > 2) {
			WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_detail");
			return null;
		}

		final String rawMode = request.getParameter("mode");
		final String mode;
		if (rawMode == null || rawMode.trim().isEmpty()) {
			mode = null;
		} else {
			final String trimmed = rawMode.trim();
			boolean found = false;
			for (final TransportMode transportMode : TransportMode.values()) {
				if (transportMode.name().equals(trimmed)) {
					found = true;
					break;
				}
			}
			if (!found) {
				WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_mode");
				return null;
			}
			mode = trimmed;
		}

		return new WebDashboardTrackService.Query(dimension, minX, minZ, maxX, maxZ, detail, mode);
	}

	private static Double parseDouble(HttpServletRequest request, String name) {
		final String raw = request.getParameter(name);
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		try {
			final double value = Double.parseDouble(raw.trim());
			return Double.isFinite(value) ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
