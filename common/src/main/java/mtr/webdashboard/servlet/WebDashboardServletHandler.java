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
		try {
			final byte[] body = json.getBytes(StandardCharsets.UTF_8);
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(CONTENT_TYPE_JSON);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setContentLength(body.length);
			response.getOutputStream().write(body);
			response.getOutputStream().flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
