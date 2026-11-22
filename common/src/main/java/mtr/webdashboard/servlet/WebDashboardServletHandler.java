package mtr.webdashboard.servlet;

import com.google.gson.JsonObject;
import mtr.webdashboard.WebDashboardDataService;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public interface WebDashboardServletHandler {

	String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
	long ASYNC_TIMEOUT_MILLIS = WebDashboardDataService.REQUEST_TIMEOUT_MILLIS + 2000L;

	static void sendJson(HttpServletResponse response, String json) {
		writeBody(response, HttpServletResponse.SC_OK, json);
	}

	static void sendError(HttpServletResponse response, int status, String error) {
		writeBody(response, status, "{\"error\":\"" + error + "\"}");
	}

	static void writeBody(HttpServletResponse response, int status, String json) {
		try {
			final byte[] body = json.getBytes(StandardCharsets.UTF_8);
			response.setStatus(status);
			response.setContentType(CONTENT_TYPE_JSON);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setContentLength(body.length);
			response.setHeader("Cache-Control", "no-store");
			response.getOutputStream().write(body);
			response.getOutputStream().flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static void completeWith(AsyncContext asyncContext, int status, String body) {
		synchronized (asyncContext) {
			if (asyncContext.getResponse().isCommitted()) {
				return;
			}
			try {
				final HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
				response.setStatus(status);
				response.setContentType(CONTENT_TYPE_JSON);
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

	static void completeWithError(AsyncContext asyncContext, int status, String error) {
		final JsonObject json = new JsonObject();
		json.addProperty("error", error);
		completeWith(asyncContext, status, json.toString());
	}

	private static void complete(AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		} catch (Exception ignored) {
		}
	}

	final class TimeoutListener implements AsyncListener {

		private final AsyncContext asyncContext;

		public TimeoutListener(AsyncContext asyncContext) {
			this.asyncContext = asyncContext;
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			System.out.println("[MTR-WebDashboard] Timed out waiting for the game thread to answer a data request.");
			WebDashboardServletHandler.completeWithError(asyncContext, HttpServletResponse.SC_GATEWAY_TIMEOUT, "game_thread_timeout");
		}

		@Override
		public void onComplete(AsyncEvent event) {
		}

		@Override
		public void onError(AsyncEvent event) {
			WebDashboardServletHandler.complete(asyncContext);
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
		}
	}
}
