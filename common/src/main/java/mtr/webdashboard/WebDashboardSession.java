package mtr.webdashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.webdashboard.WebDashboardTokenStore.Session;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.util.Locale;
import java.util.UUID;

public final class WebDashboardSession {

	public static final String COOKIE_NAME = "mtr_dashboard_session";

	private static final int COOKIE_MAX_AGE_SECONDS = 365 * 24 * 3600;
	private static final int MAX_REQUEST_BODY_CHARS = 4096;

	private final Session session;

	private WebDashboardSession(Session session) {
		this.session = session;
	}

	public static WebDashboardSession fromRequest(HttpServletRequest request) {
		return new WebDashboardSession(WebDashboardTokenStore.validateSession(readCookie(request, COOKIE_NAME)));
	}

	public boolean isAuthenticated() {
		return session != null;
	}

	public UUID getUuid() {
		return session == null ? null : session.uuid;
	}

	public String getUsername() {
		return session == null ? null : session.username;
	}

	public boolean canEdit() {
		return session != null && WebDashboardPermissions.canEdit(session.uuid);
	}

	public String getToken() {
		return session == null ? null : session.token();
	}

	public static boolean isSameOrigin(HttpServletRequest request) {
		final String origin = request.getHeader("Origin");
		if (origin == null || origin.isEmpty() || "null".equals(origin)) {
			return true;
		}

		final String host = request.getHeader("Host");
		if (host == null || host.isEmpty()) {
			return false;
		}

		final String expectedSuffix = "://" + host.toLowerCase(Locale.ENGLISH);
		return origin.toLowerCase(Locale.ENGLISH).endsWith(expectedSuffix);
	}

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

		response.addHeader("Set-Cookie", cookie.toString());
	}

	public static void clearSessionCookie(HttpServletResponse response) {
		response.addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
	}

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
