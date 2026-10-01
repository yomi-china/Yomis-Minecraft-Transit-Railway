package mtr.webdashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.webdashboard.WebDashboardTokenStore.Session;
import mtr.webdashboard.servlet.WebDashboardServletHandler;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads the signed-in identity off an incoming request.
 * <p>
 * Every servlet goes through here rather than through {@link WebDashboardTokenStore} directly, so
 * that the "is this visitor signed in, and may they edit" question has exactly one answer in one
 * place. Stage 3's write endpoints call {@link #requireEdit(HttpServletRequest)} and get a
 * ready-made yes/no instead of re-deriving it.
 */
public final class WebDashboardSession {

	public static final String COOKIE_NAME = "mtr_dashboard_session";

	/**
	 * A year, used as the sign-out value. The cookie is cleared by setting the same name with an
	 * expiry in the past; there is no separate delete call in the servlet API.
	 */
	private static final int COOKIE_MAX_AGE_SECONDS = 365 * 24 * 3600;

	/** Refused rather than truncated: no legitimate sign-in body comes close to this. */
	private static final int MAX_REQUEST_BODY_CHARS = 4096;

	private final Session session;

	private WebDashboardSession(Session session) {
		this.session = session;
	}

	/**
	 * Resolves the visitor from the session cookie.
	 * <p>
	 * {@link WebDashboardTokenStore#validateSession} deliberately re-checks permission on every call,
	 * so this is cheap but never stale: a player whose access was revoked is anonymous from their
	 * next request onwards.
	 *
	 * @return a session view that is never null; {@link #isAuthenticated()} reports whether it is a
	 *         real one.
	 */
	public static WebDashboardSession fromRequest(HttpServletRequest request) {
		return new WebDashboardSession(WebDashboardTokenStore.validateSession(readCookie(request, COOKIE_NAME)));
	}

	// ---- identity ---------------------------------------------------------

	public boolean isAuthenticated() {
		return session != null;
	}

	/**
	 * @return the signed-in player's UUID, or null when anonymous.
	 */
	public UUID getUuid() {
		return session == null ? null : session.uuid;
	}

	/**
	 * @return the signed-in player's name, or null when anonymous. This is the name captured when the
	 *         token was issued, not a live lookup.
	 */
	public String getUsername() {
		return session == null ? null : session.username;
	}

	/**
	 * @return true when this session may edit. Always false for an anonymous visitor, which is what
	 *         makes the read-only page the default rather than a special case.
	 */
	public boolean canEdit() {
		return session != null && WebDashboardPermissions.canEdit(session.uuid);
	}

	/**
	 * @return the raw session token, needed only to sign this one browser out.
	 */
	public String getToken() {
		return session == null ? null : session.token();
	}

	// ---- helpers for servlets --------------------------------------------

	/**
	 * Guard for the stage 3 write endpoints.
	 *
	 * @return true when the caller may edit; when false the response has already been sent with 401
	 *         and the caller must return without writing anything else.
	 */
	public boolean requireEdit(HttpServletRequest request, HttpServletResponse response) {
		if (canEdit()) {
			return true;
		}
		WebDashboardServletHandler.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, isAuthenticated() ? "not_permitted" : "not_authenticated");
		return false;
	}

	/**
	 * Rejects a state-changing request that did not come from the dashboard page.
	 * <p>
	 * Defence in depth behind {@code SameSite=Strict}. A same-origin {@code fetch} always sends an
	 * {@code Origin} header, so a request carrying a foreign origin is either a cross-site attempt or
	 * something that is not the page. A missing header is allowed through: some same-origin form
	 * submissions omit it, and {@code SameSite} already blocks the cross-site case.
	 */
	public static boolean isSameOrigin(HttpServletRequest request) {
		final String origin = request.getHeader("Origin");
		if (origin == null || origin.isEmpty() || "null".equals(origin)) {
			return true;
		}

		final String host = request.getHeader("Host");
		if (host == null || host.isEmpty()) {
			return false;
		}

		// Compare only scheme://host:port, and case-insensitively, because the visitor may have typed
		// localhost while the Origin header carries 127.0.0.1 - both reach the same listener but they
		// are genuinely different origins, so the port-and-host text is what has to line up.
		final String expectedSuffix = "://" + host.toLowerCase(Locale.ENGLISH);
		return origin.toLowerCase(Locale.ENGLISH).endsWith(expectedSuffix);
	}

	/**
	 * Reads a small JSON object from the request body.
	 *
	 * @return the parsed object, or null when the body is absent, too large, or not a JSON object.
	 *         Callers treat null as a 400.
	 */
	public static JsonObject readJsonBody(HttpServletRequest request) {
		try {
			final StringBuilder builder = new StringBuilder();
			try (BufferedReader reader = request.getReader()) {
				final char[] buffer = new char[512];
				int read;
				while ((read = reader.read(buffer)) != -1) {
					builder.append(buffer, 0, read);
					if (builder.length() > MAX_REQUEST_BODY_CHARS) {
						System.out.println("[MTR-WebDashboard] Refused an oversized request body");
						return null;
					}
				}
			}

			final JsonElement parsed = JsonParser.parseString(builder.toString());
			return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not read a JSON request body: " + e);
			return null;
		}
	}

	/**
	 * @return the string value of a field, or null when absent or not a string.
	 */
	public static String getString(JsonObject object, String key) {
		if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
			return null;
		}
		try {
			return object.get(key).getAsString();
		} catch (Exception ignored) {
			return null;
		}
	}

	// ---- cookie handling --------------------------------------------------

	/**
	 * Issues the session cookie.
	 * <p>
	 * {@code HttpOnly} keeps it away from JavaScript, and {@code SameSite=Strict} stops any other
	 * site from riding on it.
	 * <p>
	 * {@code Secure} is added only when the service is bound beyond loopback. It cannot be set for
	 * the normal case: browsers do not treat {@code http://127.0.0.1} as a secure context, so a
	 * {@code Secure} cookie would simply never be stored and sign-in would silently fail. Over a LAN
	 * the whole exchange is plain HTTP, so this flag is a partial mitigation at best - see the
	 * warning in {@link WebDashboardSettings#getBindHost()}.
	 */
	public static void writeSessionCookie(HttpServletRequest request, HttpServletResponse response, String token) {
		final StringBuilder cookie = new StringBuilder()
				.append(COOKIE_NAME).append("=").append(token)
				.append("; Path=/")
				.append("; HttpOnly")
				.append("; SameSite=Strict")
				.append("; Max-Age=").append(COOKIE_MAX_AGE_SECONDS);

		if (WebDashboardSettings.get().isAllowLanAccess()) {
			cookie.append("; Secure");
		}

		// addHeader rather than addCookie: the servlet API's Cookie class cannot express SameSite in
		// the javax.servlet version this project compiles against.
		response.addHeader("Set-Cookie", cookie.toString());
	}

	/**
	 * Clears the session cookie by re-issuing it with an expiry in the past.
	 */
	public static void clearSessionCookie(HttpServletResponse response) {
		response.addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
	}

	/**
	 * @return the value of a cookie, or null. Compares the name exactly, since cookie names are
	 *         case sensitive.
	 */
	public static String readCookie(HttpServletRequest request, String name) {
		final Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (final Cookie cookie : cookies) {
			if (cookie != null && name.equals(cookie.getName())) {
				final String value = cookie.getValue();
				return value == null || value.isEmpty() ? null : value;
			}
		}
		return null;
	}
}
