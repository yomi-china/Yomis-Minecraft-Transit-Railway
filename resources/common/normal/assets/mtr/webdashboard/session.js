/*
 * Session handling for the web dashboard.
 *
 * The browser is authenticated with an HttpOnly cookie, so nothing here ever sees the session
 * token - the server sets it and the browser sends it back automatically. That is why every request
 * passes `credentials: 'same-origin'`: it is the default for same-origin fetches, but stating it
 * keeps the requirement visible if a fetch is ever moved or rewritten.
 *
 * The sign-in token, by contrast, does pass through here: it arrives in the URL fragment, is
 * exchanged once, and is then erased from the address bar.
 */

const LOGIN_ENDPOINT = '/api/login';
const LOGOUT_ENDPOINT = '/api/logout';
const SESSION_ENDPOINT = '/api/session';
const REQUEST_TIMEOUT_MS = 5000;

/** The fragment key the in-game button puts the token under. */
const TOKEN_FRAGMENT_PREFIX = '#token=';

/**
 * How a request failed, so callers can tell "the game is not running" apart from "this browser is
 * not signed in". Both look like a failed fetch otherwise.
 */
export const SessionError = {
	/** The service was not reachable, or answered with something unusable. */
	UNREACHABLE: 'unreachable',
	/** The service answered 401: the token was expired, already used, or access was withdrawn. */
	REJECTED: 'rejected'
};

export class SessionFailure extends Error {
	constructor(kind, detail) {
		super(kind + (detail ? ': ' + detail : ''));
		this.kind = kind;
	}
}

/**
 * Wraps fetch with a timeout. Without one a browser will happily wait minutes on a half-open
 * connection, leaving the page stuck with no way for the visitor to tell what happened.
 */
async function request(path, options = {}) {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

	try {
		return await fetch(path, {
			cache: 'no-store',
			credentials: 'same-origin',
			signal: controller.signal,
			...options
		});
	} catch (error) {
		throw new SessionFailure(SessionError.UNREACHABLE, error && error.message);
	} finally {
		clearTimeout(timer);
	}
}

async function readJson(response) {
	try {
		return await response.json();
	} catch (error) {
		throw new SessionFailure(SessionError.UNREACHABLE, 'malformed response');
	}
}

/**
 * @returns {Promise<{authenticated: boolean, canEdit: boolean, username: string|null}>}
 * @throws {SessionFailure} with kind UNREACHABLE when the service did not answer usefully.
 */
export async function fetchSession() {
	const response = await request(SESSION_ENDPOINT);
	if (!response.ok) {
		throw new SessionFailure(SessionError.UNREACHABLE, 'HTTP ' + response.status);
	}
	return await readJson(response);
}

/**
 * @returns {Promise<{version: string, port: number, lanAccess: boolean}>}
 * @throws {SessionFailure}
 */
export async function fetchStatus() {
	// Note: this is registered as an authenticated route on the server too, but it answers
	// anonymously as well - it is what tells the page whether the service is up at all.
	const response = await request('/api/status');
	if (!response.ok) {
		throw new SessionFailure(SessionError.UNREACHABLE, 'HTTP ' + response.status);
	}
	return await readJson(response);
}

/**
 * Exchanges a one-time sign-in token for a session cookie.
 *
 * @throws {SessionFailure} with kind REJECTED when the server refused the token, or UNREACHABLE when
 *         it could not be reached at all. The two need different messages: a rejected token means
 *         "press the button again", an unreachable service means "the game is not running".
 */
export async function login(token) {
	const response = await request(LOGIN_ENDPOINT, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ token })
	});

	if (response.status === 401 || response.status === 400 || response.status === 403) {
		throw new SessionFailure(SessionError.REJECTED, 'HTTP ' + response.status);
	}
	if (!response.ok) {
		throw new SessionFailure(SessionError.UNREACHABLE, 'HTTP ' + response.status);
	}
	return await readJson(response);
}

/**
 * Ends this browser's session.
 *
 * @returns {Promise<boolean>} true when the server confirmed the sign-out. The caller reloads only on
 *          success: reloading after a failure would silently sign the visitor back in, which looks
 *          like the button simply did not work.
 */
export async function logout() {
	try {
		const response = await request(LOGOUT_ENDPOINT, { method: 'POST' });
		return response.ok;
	} catch (error) {
		console.warn('[MTR-WebDashboard] Sign-out request failed:', error);
		return false;
	}
}

/**
 * @returns {string|null} the sign-in token from the URL fragment, if there is one.
 */
export function readTokenFromUrl() {
	const hash = window.location.hash || '';
	if (!hash.startsWith(TOKEN_FRAGMENT_PREFIX)) {
		return null;
	}
	const token = hash.substring(TOKEN_FRAGMENT_PREFIX.length).trim();
	return token.length > 0 ? token : null;
}

/**
 * Removes the token from the address bar.
 *
 * Done with replaceState rather than by assigning location.hash, so the browser does not keep an
 * extra history entry and the token does not survive a back-then-forward navigation. Called as soon
 * as the exchange finishes, whether it succeeded or not: a failed token is dead anyway.
 */
export function clearTokenFromUrl() {
	if (window.location.hash) {
		window.history.replaceState(null, '', window.location.pathname + window.location.search);
	}
}
