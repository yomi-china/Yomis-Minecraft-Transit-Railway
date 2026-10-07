/*
 * Data access for the dashboard's API: the read-only snapshot, and the write endpoints.
 *
 * Every failure is normalised into a DataFailure whose `kind` says what went wrong, because the cases need
 * different messages and all of them look like "fetch failed" otherwise:
 *
 *   AUTH            the session expired or was revoked (401)
 *   FORBIDDEN       signed in but not allowed to edit (403)
 *   NO_SERVER       no world is loaded yet (503 no_server)
 *   EDITOR_OFFLINE  the account that signed in left the game (503 web_editor_offline)
 *   NOT_FOUND       the object was deleted in game (404 not_found)
 *   INVALID_FIELD   the server rejected a field (400 invalid_field / unsupported_field)
 *   BAD_REQUEST     the service answered something unexpected (404/500/malformed body)
 *   UNREACHABLE     the service could not be reached at all
 */

const DATA_ENDPOINT = '/api/data';
const META_ENDPOINT = '/api/meta';

/**
 * Longer than the session calls' timeout: the server builds this payload on the game thread, and a busy tick
 * can legitimately take a while.
 */
const REQUEST_TIMEOUT_MS = 12000;

export const DataErrorKind = {
	AUTH: 'auth',
	FORBIDDEN: 'forbidden',
	NO_SERVER: 'no_server',
	EDITOR_OFFLINE: 'editor_offline',
	NOT_FOUND: 'not_found',
	INVALID_FIELD: 'invalid_field',
	BAD_REQUEST: 'bad_request',
	UNREACHABLE: 'unreachable'
};

export class DataFailure extends Error {
	constructor(kind, detail, body) {
		super(kind + (detail ? ': ' + detail : ''));
		this.kind = kind;
		/** The server's parsed error body, when it sent one. */
		this.body = body || null;
	}

	/** @returns {string|null} the field the server objected to. */
	get field() {
		return this.body && typeof this.body.field === 'string' ? this.body.field : null;
	}

	/** @returns {string[]} the fields the server said it accepts. */
	get acceptedFields() {
		return this.body && Array.isArray(this.body.acceptedFields) ? this.body.acceptedFields : [];
	}
}

/**
 * @param {string} path
 * @param {object} [init] extra fetch options. Writes pass method, headers and body here.
 */
async function fetchJson(path, init) {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

	let response;
	try {
		response = await fetch(path, {
			cache: 'no-store',
			credentials: 'same-origin',
			signal: controller.signal,
			...(init || {})
		});
	} catch (error) {
		throw new DataFailure(DataErrorKind.UNREACHABLE, error && error.message);
	} finally {
		clearTimeout(timer);
	}

	// The error body is read before the status is turned into a kind, because a 400 and a 503 both carry the
	// detail that decides which kind they are.
	let errorBody = null;
	if (!response.ok) {
		try {
			const parsed = await response.json();
			if (parsed && typeof parsed === 'object' && typeof parsed.error === 'string') {
				errorBody = parsed;
			}
		} catch (error) {
			// Not JSON, or empty. The status alone still says enough.
		}
	}

	if (response.status === 401) {
		throw new DataFailure(DataErrorKind.AUTH, 'HTTP 401', errorBody);
	}
	if (response.status === 403) {
		throw new DataFailure(DataErrorKind.FORBIDDEN, 'HTTP 403', errorBody);
	}
	if (response.status === 503 && errorBody && errorBody.error === 'web_editor_offline') {
		// Its own kind because the remedy is nothing like "no world is open".
		throw new DataFailure(DataErrorKind.EDITOR_OFFLINE, 'HTTP 503', errorBody);
	}
	if (response.status === 503) {
		// no_server: the service is up but nothing is loaded to read from.
		throw new DataFailure(DataErrorKind.NO_SERVER, 'HTTP 503', errorBody);
	}
	if (response.status === 404 && errorBody && errorBody.error === 'not_found') {
		throw new DataFailure(DataErrorKind.NOT_FOUND, 'HTTP 404', errorBody);
	}
	if (response.status === 400 && errorBody && (errorBody.error === 'invalid_field' || errorBody.error === 'unsupported_field')) {
		throw new DataFailure(DataErrorKind.INVALID_FIELD, errorBody.message || 'HTTP 400', errorBody);
	}
	if (!response.ok) {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'HTTP ' + response.status, errorBody);
	}

	try {
		return await response.json();
	} catch (error) {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'malformed JSON');
	}
}

/**
 * @returns {Promise<object>} the full snapshot: every loaded world with its stations, platforms, routes,
 *          depots and sidings.
 * @throws {DataFailure}
 */
export async function fetchData() {
	return await fetchJson(DATA_ENDPOINT);
}

/**
 * The mode and limit vocabulary. Loaded separately and treated as optional by the caller - it only supplies
 * behaviour flags and the untitled fallback, so the lists still work when it fails.
 *
 * @throws {DataFailure}
 */
export async function fetchMeta() {
	return await fetchJson(META_ENDPOINT);
}

/**
 * Edits one object, sending only the fields being changed.
 *
 * @param {string} kind 'station' | 'route' | 'depot'; also the URL segment.
 * @param {string} id   the object's id as published, a string because it can exceed 2^53.
 * @returns {Promise<{changed: string[], warnings: string[], object: object}>} the object as saved, which
 *          may differ from what was sent if a value was clamped.
 * @throws {DataFailure}
 */
export async function patch(kind, id, fields) {
	return await fetchJson('/api/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id), {
		method: 'PATCH',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(fields)
	});
}

/**
 * @returns {object|null} the world to display: the overworld if loaded, otherwise the first one reported.
 *          No world switcher yet, so the choice is mechanical.
 */
export function selectWorld(data) {
	const worlds = data && Array.isArray(data.worlds) ? data.worlds : [];
	if (worlds.length === 0) {
		return null;
	}
	return worlds.find(world => world.dimension === 'minecraft:overworld') || worlds[0];
}
