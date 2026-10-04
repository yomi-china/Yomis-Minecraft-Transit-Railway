package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardDataService;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

/**
 * The response machinery shared by the read and write endpoints.
 *
 * <p>{@code RailwayData} and its {@code DataCache} are plain collections mutated on the game thread, so a
 * Jetty thread must never walk them. The flow is therefore: do the Jetty-thread work (authenticate, parse),
 * hop to the game thread where touching the model is safe, write the bytes back under the async context's
 * lock, and fall back to an error response if the game thread never answers.
 *
 * <p>The read and write endpoints are siblings under this class rather than one extending the other: the
 * read path adds "produce this JSON" and the write path adds "apply this patch", and neither can honour the
 * other's abstract method.
 */
public abstract class WebDashboardAsyncSupport extends HttpServlet {

	/**
	 * How long the browser waits for the game thread before being told the request timed out. Slightly longer
	 * than the service's own deadline, so the service's clearer error wins the race.
	 */
	protected static final long ASYNC_TIMEOUT_MILLIS = WebDashboardDataService.REQUEST_TIMEOUT_MILLIS + 2000L;

	/** Serialises an error code the way every endpoint reports failures. */
	protected static void completeWithError(AsyncContext asyncContext, int status, String error) {
		final JsonObject json = new JsonObject();
		json.addProperty("error", error);
		completeWith(asyncContext, status, json.toString());
	}

	/**
	 * Writes a body and completes the exchange, exactly once.
	 * <p>
	 * Both the game-thread completion and the timeout listener reach this, serialised on the async context's
	 * monitor; without the "already answered" check, whichever lost the race would append a second body.
	 */
	protected static void completeWith(AsyncContext asyncContext, int status, String body) {
		synchronized (asyncContext) {
			if (asyncContext.getResponse().isCommitted()) {
				return;
			}
			try {
				final HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
				response.setStatus(status);
				response.setContentType(WebDashboardServletHandler.CONTENT_TYPE_JSON);
				response.setCharacterEncoding("UTF-8");
				response.setHeader("Cache-Control", "no-store");
				try (PrintWriter writer = response.getWriter()) {
					writer.write(body);
				}
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Failed to write a response: " + e);
			} finally {
				complete(asyncContext);
			}
		}
	}

	protected static void complete(AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		} catch (Exception ignored) {
			// Already completed, or the client went away. Nothing useful left to do.
		}
	}

	/**
	 * Ends a request the game thread never answered, so the browser is not left hanging on a socket that will
	 * never produce a body.
	 */
	protected static final class TimeoutListener implements AsyncListener {

		private final AsyncContext asyncContext;

		protected TimeoutListener(AsyncContext asyncContext) {
			this.asyncContext = asyncContext;
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			System.out.println("[MTR-WebDashboard] Timed out waiting for the game thread to answer a data request.");
			completeWithError(asyncContext, HttpServletResponse.SC_GATEWAY_TIMEOUT, "game_thread_timeout");
		}

		@Override
		public void onComplete(AsyncEvent event) {
		}

		@Override
		public void onError(AsyncEvent event) {
			complete(asyncContext);
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
		}
	}
}
