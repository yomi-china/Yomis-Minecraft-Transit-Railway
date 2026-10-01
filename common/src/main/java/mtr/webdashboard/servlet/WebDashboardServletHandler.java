package mtr.webdashboard.servlet;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * Shared JSON response helper for the web dashboard endpoints.
 * <p>
 * {@code mtr.servlet.IServletHandler} sets {@code Access-Control-Allow-Origin: *} on every response,
 * which lets any page in any browser read the railway data cross-origin. The dashboard handles
 * server state and, from stage 2, authenticated edits, so no CORS header is emitted here at all -
 * the page is same-origin and needs none.
 */
public interface WebDashboardServletHandler {

	String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

	/**
	 * Writes a UTF-8 JSON body with HTTP 200. The length is set explicitly so the container does not
	 * fall back to chunked encoding for a small fixed-size payload.
	 */
	static void sendJson(HttpServletResponse response, String json) {
		writeBody(response, HttpServletResponse.SC_OK, json);
	}

	/**
	 * Writes a JSON error body with the given status.
	 * <p>
	 * Errors are JSON rather than HTML so the page can tell "you are not signed in" apart from "the
	 * server is broken" without parsing a Jetty error page, and so a wrong password never renders a
	 * stack trace to the visitor.
	 *
	 * @param error a short machine-readable code, e.g. {@code "bad_token"}.
	 */
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
			// Authenticated responses must never be cached by the browser or an intermediary, or a
			// signed-out visitor could be shown a stale "signed in" answer.
			response.setHeader("Cache-Control", "no-store");
			response.getOutputStream().write(body);
			response.getOutputStream().flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
