package mtr.webdashboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/**
 * Issues and validates the two kinds of secret used by the dashboard.
 * <p>
 * <b>Login tokens</b> are short-lived, single-use, and per player (only one outstanding at a time,
 * so pressing the button twice does not pile them up). They are the value that appears in the
 * browser URL, which is why they are deliberately weak in power and short in life: a URL can linger
 * in history, be copied into a chat, or be caught on a screen recording.
 * <p>
 * <b>Session tokens</b> are the actual credential. They live in a cookie, last until the server
 * stops or access is revoked, and a player may hold several - one per browser or machine.
 * <p>
 * Nothing here is persisted. A restart invalidates every browser session and players sign in again
 * with one click; writing plaintext credentials to disk would be a worse trade.
 * <p>
 * Access decisions are <em>not</em> made here. Every validation calls back into
 * {@link WebDashboardPermissions} so that a revoked or de-opped player loses access on their very
 * next request rather than at some later refresh.
 */
public final class WebDashboardTokenStore {

	/** 32 random bytes, base64url without padding: 43 characters. */
	private static final int TOKEN_BYTES = 32;

	/**
	 * Above this many session tokens the registry sweeps expired entries. Sessions are only created
	 * by a deliberate in-game click, so this is a safety valve rather than an expected ceiling.
	 */
	private static final int SESSION_SWEEP_THRESHOLD = 256;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Map<String, LoginToken> LOGIN_TOKENS = new ConcurrentHashMap<>();
	private static final Map<UUID, String> LOGIN_TOKEN_BY_PLAYER = new ConcurrentHashMap<>();
	private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Set<String>> SESSIONS_BY_PLAYER = new ConcurrentHashMap<>();

	private WebDashboardTokenStore() {
	}

	// ---- login tokens -----------------------------------------------------

	/**
	 * Issues a login token for a player, replacing any token they already had.
	 * <p>
	 * The caller must have established that the player may edit; this method does not check. The
	 * permission is re-checked when the token is redeemed, so a grant that is withdrawn in the
	 * seconds between the click and the browser loading is still caught.
	 *
	 * @return the token to put in the URL.
	 */
	public static String issueLoginToken(UUID uuid, String username) {
		if (uuid == null) {
			return "";
		}

		final String token = newToken();
		final long ttlMillis = WebDashboardSettings.get().getLoginTokenTtlSeconds() * 1000L;
		final LoginToken loginToken = new LoginToken(uuid, username, System.currentTimeMillis() + ttlMillis);

		// Drop the previous token so one player never has two live URLs.
		final String previous = LOGIN_TOKEN_BY_PLAYER.put(uuid, token);
		if (previous != null) {
			LOGIN_TOKENS.remove(previous);
		}
		LOGIN_TOKENS.put(token, loginToken);

		sweepLoginTokens();
		return token;
	}

	/**
	 * Redeems a login token exactly once.
	 * <p>
	 * Removal happens before the permission re-check on purpose: if the check fails the token is
	 * still burned, so a token cannot be retried against a permission that might be restored.
	 *
	 * @return the session token to hand to the browser, or null when the token was unknown, expired,
	 *         already used, or its owner may no longer edit.
	 */
	public static String redeemLoginToken(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}

		final LoginToken loginToken = LOGIN_TOKENS.remove(token);
		LOGIN_TOKEN_BY_PLAYER.remove(loginToken == null ? null : loginToken.uuid, token);

		if (loginToken == null) {
			return null;
		}
		if (System.currentTimeMillis() > loginToken.expiresAtMillis) {
			System.out.println("[MTR-WebDashboard] Rejected an expired sign-in token");
			return null;
		}
		// Re-checked here, not just at issue time: an operator may have revoked access in between, and
		// op level may have changed.
		if (!WebDashboardPermissions.canEdit(loginToken.uuid)) {
			System.out.println("[MTR-WebDashboard] Rejected a sign-in token: access was withdrawn first");
			return null;
		}

		return issueSession(loginToken.uuid, loginToken.username);
	}

	// ---- sessions ---------------------------------------------------------

	/**
	 * Creates a session for a player.
	 *
	 * @return the session token to put in the cookie.
	 */
	public static String issueSession(UUID uuid, String username) {
		final String token = newToken();
		final int ttlHours = WebDashboardSettings.get().getSessionTtlHours();
		final long expiresAtMillis = ttlHours <= 0 ? 0L : System.currentTimeMillis() + ttlHours * 3600_000L;

		SESSIONS.put(token, new Session(uuid, username, token, expiresAtMillis));
		SESSIONS_BY_PLAYER.computeIfAbsent(uuid, key -> ConcurrentHashMap.newKeySet()).add(token);

		if (SESSIONS.size() > SESSION_SWEEP_THRESHOLD) {
			sweepSessions();
		}
		return token;
	}

	/**
	 * Validates a session cookie.
	 * <p>
	 * Re-reads the permission every time so revocation needs no session bookkeeping to be correct;
	 * actively destroying sessions on revoke is then an optimisation, not the mechanism.
	 *
	 * @return the player behind the session, or null when the cookie is unknown, expired, or its
	 *         owner may no longer edit.
	 */
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

	/**
	 * Ends one browser session, for the sign-out button. Other sessions the player has elsewhere are
	 * left alone.
	 */
	public static void discardSession(String token) {
		if (token == null) {
			return;
		}
		final Session session = SESSIONS.remove(token);
		if (session == null) {
			// Already gone, whether by expiry, sign-out or a revoke. Returning here matters because
			// callers reach this after their own lookup already failed, and doing the bookkeeping below
			// with a null session would be a second, pointless pass.
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

	/**
	 * Drops every outstanding secret for a player: all their browser sessions and any unused login
	 * token.
	 * <p>
	 * Called when access is revoked, so a visitor is signed out immediately instead of discovering it
	 * on their next action.
	 *
	 * @return the number of sessions that were ended.
	 */
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

	/**
	 * Drops a player's unused login token but keeps their browser sessions. Used when a player leaves
	 * the game: a session belongs to a browser, and the two are not tied together.
	 */
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

	/**
	 * @return the names of players with a live session, for {@code status}.
	 */
	public static List<String> getSessionUsernames() {
		final Set<String> names = new HashSet<>();
		for (final Session session : SESSIONS.values()) {
			names.add(session.username == null || session.username.isEmpty() ? session.uuid.toString().substring(0, 8) : session.username);
		}
		final List<String> sorted = new ArrayList<>(names);
		sorted.sort(String::compareToIgnoreCase);
		return sorted;
	}

	// ---- internals --------------------------------------------------------

	private static String newToken() {
		final byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	/**
	 * Constant-time comparison, for any place a caller-supplied token is matched against a stored
	 * one. The map lookups above are already O(1) hash probes, but this is kept so that adding a
	 * comparison later does not quietly introduce a timing side channel.
	 */
	public static boolean tokensMatch(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static void sweepLoginTokens() {
		final long now = System.currentTimeMillis();
		final Iterator<Map.Entry<String, LoginToken>> iterator = LOGIN_TOKENS.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<String, LoginToken> entry = iterator.next();
			if (now > entry.getValue().expiresAtMillis) {
				iterator.remove();
				LOGIN_TOKEN_BY_PLAYER.remove(entry.getValue().uuid, entry.getKey());
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

	private static final class LoginToken {

		private final UUID uuid;
		private final String username;
		private final long expiresAtMillis;

		private LoginToken(UUID uuid, String username, long expiresAtMillis) {
			this.uuid = uuid;
			this.username = username;
			this.expiresAtMillis = expiresAtMillis;
		}
	}

	/**
	 * A signed-in browser. Immutable; the username is a snapshot for display, while permission is
	 * always re-derived.
	 */
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

		/**
		 * @return the cookie value, so a caller can sign this one browser out without re-reading the
		 *         request.
		 */
		public String token() {
			return token;
		}
	}
}
