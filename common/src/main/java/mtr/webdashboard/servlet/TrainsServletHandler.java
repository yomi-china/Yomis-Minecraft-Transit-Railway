package mtr.webdashboard.servlet;

import mtr.webdashboard.WebDashboardRuntime;
import mtr.webdashboard.WebDashboardSession;
import mtr.webdashboard.WebDashboardTrainService;
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
 * Read-only live train snapshot for the map overlay.
 *
 * <p>One build at a time keeps the game-thread cost bounded; concurrent pollers get a retry hint
 * instead of a queue of builds.
 */
public class TrainsServletHandler extends HttpServlet {

    public static final String PATH = "/api/trains";

    private static final long ASYNC_TIMEOUT_MILLIS = 5000L;
    private static final String DEFAULT_DIMENSION = "minecraft:overworld";
    private static final AtomicBoolean BUILD_IN_PROGRESS = new AtomicBoolean(false);

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

        final WebDashboardTrainService.Query query = parseQuery(request, response);
        if (query == null) {
            return;
        }

        if (!BUILD_IN_PROGRESS.compareAndSet(false, true)) {
            response.setHeader("Retry-After", "1");
            WebDashboardServletHandler.sendError(response, 429, "trains_busy");
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
                WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_GATEWAY_TIMEOUT, "trains_timeout");
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
                    final WebDashboardTrainService.Result result = WebDashboardTrainService.build(server, query, cancelled::get);
                    try {
                        asyncContext.start(() -> WebDashboardServletHandler.completeWith(asyncContext, result.status(), result.body()));
                    } catch (Exception e) {
                        WebDashboardServletHandler.completeWith(asyncContext, result.status(), result.body());
                    }
                } catch (Throwable t) {
                    System.out.println("[MTR-WebDashboard] Failed to build the train response for " + query.dimension() + ": " + t);
                    WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "build_failed");
                } finally {
                    BUILD_IN_PROGRESS.set(false);
                }
            });
        } catch (Exception e) {
            BUILD_IN_PROGRESS.set(false);
            System.out.println("[MTR-WebDashboard] Could not schedule the train build on the game thread: " + e);
            WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "server_stopping");
        }
    }

    private static WebDashboardTrainService.Query parseQuery(HttpServletRequest request, HttpServletResponse response) {
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

        final String rawLimit = request.getParameter("limit");
        int limit = WebDashboardTrainService.DEFAULT_LIMIT;
        if (rawLimit != null && !rawLimit.trim().isEmpty()) {
            try {
                limit = Integer.parseInt(rawLimit.trim());
            } catch (NumberFormatException e) {
                WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_limit");
                return null;
            }
            if (limit < 1) {
                WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_limit");
                return null;
            }
            limit = Math.min(limit, WebDashboardTrainService.MAX_LIMIT);
        }

        final Long routeId = parseId(request, "route");
        final Long depotId = parseId(request, "depot");
        if (routeId == null || depotId == null) {
            WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_query");
            return null;
        }

        return new WebDashboardTrainService.Query(dimension, limit, routeId, depotId);
    }

    /**
     * Blank, "all" and zero all mean "no filter"; a malformed id is a client error, not a silent
     * empty result.
     */
    private static Long parseId(HttpServletRequest request, String name) {
        final String raw = request.getParameter(name);
        if (raw == null) {
            return 0L;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("all") || trimmed.equals("0")) {
            return 0L;
        }
        try {
            final long value = Long.parseLong(trimmed);
            return value != 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
