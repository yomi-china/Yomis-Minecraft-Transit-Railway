package mtr.webdashboard;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebDashboardTokenStore {

	private static final int TOKEN_BYTES = 32;
	private static final int SESSION_SWEEP_THRESHOLD = 256;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Map<String, LoginToken> LOGIN_TOKENS = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LOGIN_TOKEN_BY_PLAYER = new ConcurrentHashMap<>();
	private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Set<String>> SESSIONS_BY_PLAYER = new ConcurrentHashMap<>();

	private WebDashboardTokenStore() {
	}

	public static String issueLoginToken(UUID uuid, String username) {
		if (uuid == null) {
			return "";
		}

		final String token = newToken();
		final long ttlMillis = WebDashboardSettings.get().getLoginTokenTtlSeconds() * 1000L;
		final LoginToken loginToken = new LoginToken(uuid, username, System.currentTimeMillis() + ttlMillis);

		final String previous = LOGIN_TOKEN_BY_PLAYER.put(uuid, token);
		if (previous != null) {
			LOGIN_TOKENS.remove(previous);
		}
		LOGIN_TOKENS.put(token, loginToken);

		sweepLoginTokens();
		return token;
	}

	public static Session redeemLoginToken(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}

		final LoginToken loginToken = LOGIN_TOKENS.remove(token);
		if (loginToken == null) {
			System.out.println("[MTR-WebDashboard] Rejected an unknown or already used sign-in token");
			return null;
		}
		LOGIN_TOKEN_BY_PLAYER.remove(loginToken.uuid(), token);

		if (System.currentTimeMillis() > loginToken.expiresAtMillis()) {
			System.out.println("[MTR-WebDashboard] Rejected an expired sign-in token");
			return null;
		}
		if (!WebDashboardPermissions.canEdit(loginToken.uuid())) {
			System.out.println("[MTR-WebDashboard] Rejected a sign-in token: access was withdrawn first");
			return null;
		}

		final long ttlHours = WebDashboardSettings.get().getSessionTtlHours();
		final long expiresAtMillis = ttlHours <= 0 ? 0L : System.currentTimeMillis() + ttlHours * 3600_000L;
		final Session session = new Session(loginToken.uuid(), loginToken.username(), token, expiresAtMillis);

		SESSIONS.put(token, session);
		SESSIONS_BY_PLAYER.computeIfAbsent(loginToken.uuid(), key -> ConcurrentHashMap.newKeySet()).add(token);

		if (SESSIONS.size() > SESSION_SWEEP_THRESHOLD) {
			sweepSessions();
		}
		return session;
	}

	public static Session validateSession(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}

		final Session session = SESSIONS.get(token);
		if (session == null) {
			return null;
		}
		if (session.expiresAtMillis > 0 && System.currentTimeMillis() > session.expiresAtMillis) {
			discardSession(token);
			return null;
		}
		if (!WebDashboardPermissions.canEdit(session.uuid)) {
			discardSession(token);
			return null;
		}
		return session;
	}

	public static void discardSession(String token) {
		if (token == null) {
			return;
		}
		final Session session = SESSIONS.remove(token);
		if (session == null) {
			return;
		}
		final Set<String> tokens = SESSIONS_BY_PLAYER.get(session.uuid);
		if (tokens != null) {
			tokens.remove(token);
			if (tokens.isEmpty()) {
				SESSIONS_BY_PLAYER.remove(session.uuid);
			}
		}
	}

	public static int revokePlayer(UUID uuid) {
		if (uuid == null) {
			return 0;
		}

		final String loginToken = LOGIN_TOKEN_BY_PLAYER.remove(uuid);
		if (loginToken != null) {
			LOGIN_TOKENS.remove(loginToken);
		}

		final Set<String> tokens = SESSIONS_BY_PLAYER.remove(uuid);
		if (tokens == null) {
			return 0;
		}
		int removed = 0;
		for (final String token : new ArrayList<>(tokens)) {
			if (SESSIONS.remove(token) != null) {
				removed++;
			}
		}
		return removed;
	}

	public static void revokeLoginToken(UUID uuid) {
		if (uuid == null) {
			return;
		}
		final String loginToken = LOGIN_TOKEN_BY_PLAYER.remove(uuid);
		if (loginToken != null) {
			LOGIN_TOKENS.remove(loginToken);
		}
	}

	public static int getSessionCount() {
		return SESSIONS.size();
	}

	public static int getPendingLoginTokenCount() {
		return LOGIN_TOKENS.size();
	}

	public static List<String> getSessionUsernames() {
		final Set<String> names = new HashSet<>();
		for (final Session session : SESSIONS.values()) {
			names.add(session.username == null || session.username.isEmpty() ? session.uuid.toString().substring(0, 8) : session.username);
		}
		final List<String> sorted = new ArrayList<>(names);
		sorted.sort(String::compareToIgnoreCase);
		return sorted;
	}

	private static String newToken() {
		final byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	private static void sweepLoginTokens() {
		final long now = System.currentTimeMillis();
		final Iterator<Map.Entry<String, LoginToken>> iterator = LOGIN_TOKENS.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<String, LoginToken> entry = iterator.next();
			if (now > entry.getValue().expiresAtMillis()) {
				iterator.remove();
				LOGIN_TOKEN_BY_PLAYER.remove(entry.getValue().uuid(), entry.getKey());
			}
		}
	}

	private static void sweepSessions() {
		final long now = System.currentTimeMillis();
		for (final Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
			final Session session = entry.getValue();
			if (session.expiresAtMillis > 0 && now > session.expiresAtMillis) {
				discardSession(entry.getKey());
			}
		}
	}

	private record LoginToken(UUID uuid, String username, long expiresAtMillis) {
	}

	public static final class Session {

		public final UUID uuid;
		public final String username;
		private final String token;
		private final long expiresAtMillis;

		private Session(UUID uuid, String username, String token, long expiresAtMillis) {
			this.uuid = uuid;
			this.username = username;
			this.token = token;
			this.expiresAtMillis = expiresAtMillis;
		}

		public String token() {
			return token;
		}
	}
}
