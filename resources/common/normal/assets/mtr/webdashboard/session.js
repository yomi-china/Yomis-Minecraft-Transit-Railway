const LOGIN_ENDPOINT = '/api/login';
const LOGOUT_ENDPOINT = '/api/logout';
const SESSION_ENDPOINT = '/api/session';
const REQUEST_TIMEOUT_MS = 5000;

const TOKEN_FRAGMENT_PREFIX = '#token=';

export const SessionError = {
	UNREACHABLE: 'unreachable',
	REJECTED: 'rejected'
};

export class SessionFailure extends Error {
	constructor(kind, detail) {
		super(kind + (detail ? ': ' + detail : ''));
		this.kind = kind;
	}
}

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

async function getJson(path) {
	const response = await request(path);
	if (!response.ok) {
		throw new SessionFailure(SessionError.UNREACHABLE, 'HTTP ' + response.status);
	}
	return await readJson(response);
}

/**
 * @returns {Promise<{authenticated: boolean, canEdit: boolean, username: string|null}>}
 * @throws {SessionFailure} with kind UNREACHABLE when the service did not answer usefully.
 */
export async function fetchSession() {
	return await getJson(SESSION_ENDPOINT);
}

/**
 * @returns {Promise<{version: string, port: number, lanAccess: boolean}>}
 * @throws {SessionFailure}
 */
export async function fetchStatus() {
	return await getJson('/api/status');
}

/**
 * @throws {SessionFailure} with kind REJECTED when the server refused the token, or UNREACHABLE when
 *         it could not be reached at all.
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
 * @returns {Promise<boolean>} true when the server confirmed the sign-out.
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
	const hash = window.location.hash;
	if (!hash.startsWith(TOKEN_FRAGMENT_PREFIX)) {
		return null;
	}
	const token = hash.substring(TOKEN_FRAGMENT_PREFIX.length).trim();
	return token.length > 0 ? token : null;
}

export function clearTokenFromUrl() {
	if (window.location.hash) {
		window.history.replaceState(null, '', window.location.pathname + window.location.search);
	}
}
