const DATA_ENDPOINT = '/api/data';
const META_ENDPOINT = '/api/meta';
const TRACKS_ENDPOINT = '/api/tracks';
const TRAINS_ENDPOINT = '/api/trains';

const REQUEST_TIMEOUT_MS = 12000;
const TRACKS_TIMEOUT_MS = 6000;
const TRAINS_TIMEOUT_MS = 6000;

export const DataErrorKind = {
	AUTH: 'auth',
	FORBIDDEN: 'forbidden',
	NO_SERVER: 'no_server',
	EDITOR_OFFLINE: 'editor_offline',
	NOT_FOUND: 'not_found',
	INVALID_FIELD: 'invalid_field',
	BAD_REQUEST: 'bad_request',
	UNREACHABLE: 'unreachable',
	ABORTED: 'aborted',
	TIMEOUT: 'timeout',
	BUSY: 'busy'
};

export class DataFailure extends Error {
	constructor(kind, detail, body) {
		super(kind + (detail ? ': ' + detail : ''));
		this.kind = kind;
		this.body = body || null;
	}

	/** @returns {string|null} the field the server objected to. */
	get field() {
		return this.body && typeof this.body.field === 'string' ? this.body.field : null;
	}
}

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
		throw new DataFailure(DataErrorKind.EDITOR_OFFLINE, 'HTTP 503', errorBody);
	}
	if (response.status === 503) {
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

/** @throws {DataFailure} */
export async function fetchData() {
	return await fetchJson(DATA_ENDPOINT);
}

/** @throws {DataFailure} */
export async function fetchMeta() {
	return await fetchJson(META_ENDPOINT);
}

/**
 * @param {string} kind 'station' | 'route' | 'depot'; also the URL segment.
 * @param {string} id   the object's id as published, a string because it can exceed 2^53.
 * @returns {Promise<{changed: string[], warnings: string[], object: object}>}
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
 */
export function selectWorld(data) {
	const worlds = data && Array.isArray(data.worlds) ? data.worlds : [];
	if (worlds.length === 0) {
		return null;
	}
	return worlds.find(world => world.dimension === 'minecraft:overworld') || worlds[0];
}

function trackCoordinate(value) {
	return String(Math.round(Number(value) * 10) / 10);
}

/**
 * Viewport-scoped rail geometry. The caller owns cancellation through its own AbortController; a
 * separate timer covers a server/game-thread stall. Errors keep their kind so the overlay can tell
 * "too busy to try now" from "this server does not implement the endpoint".
 *
 * @throws {DataFailure}
 */
export async function fetchTracks(options) {
	const params = new URLSearchParams();
	params.set('world', options.world);
	params.set('minX', trackCoordinate(options.minX));
	params.set('minZ', trackCoordinate(options.minZ));
	params.set('maxX', trackCoordinate(options.maxX));
	params.set('maxZ', trackCoordinate(options.maxZ));
	params.set('detail', String(options.detail));
	if (options.mode && options.mode !== 'all') {
		params.set('mode', options.mode);
	}

	const controller = new AbortController();
	let timedOut = false;
	const timer = setTimeout(() => {
		timedOut = true;
		controller.abort();
	}, TRACKS_TIMEOUT_MS);
	const externalSignal = options.signal;
	const relayAbort = () => controller.abort();
	if (externalSignal) {
		if (externalSignal.aborted) {
			controller.abort();
		} else {
			externalSignal.addEventListener('abort', relayAbort, { once: true });
		}
	}

	let response;
	try {
		response = await fetch(TRACKS_ENDPOINT + '?' + params.toString(), {
			cache: 'no-store',
			credentials: 'same-origin',
			signal: controller.signal
		});
	} catch (error) {
		if (externalSignal && externalSignal.aborted) {
			throw new DataFailure(DataErrorKind.ABORTED, 'aborted');
		}
		if (timedOut) {
			throw new DataFailure(DataErrorKind.TIMEOUT, 'the tracks request timed out');
		}
		throw new DataFailure(DataErrorKind.UNREACHABLE, error && error.message);
	} finally {
		clearTimeout(timer);
		if (externalSignal) {
			externalSignal.removeEventListener('abort', relayAbort);
		}
	}

	let body = null;
	try {
		body = await response.json();
	} catch (error) {
		body = null;
	}

	if (response.status === 401) {
		throw new DataFailure(DataErrorKind.AUTH, 'HTTP 401', body);
	}
	if (response.status === 403) {
		throw new DataFailure(DataErrorKind.FORBIDDEN, 'HTTP 403', body);
	}
	if (response.status === 404 && body && body.error === 'world_not_found') {
		throw new DataFailure(DataErrorKind.NOT_FOUND, 'world_not_found', body);
	}
	if (response.status === 404 && !body) {
		throw new DataFailure(DataErrorKind.NOT_FOUND, 'track endpoint not found', body);
	}
	if (response.status === 429 || (body && body.error === 'tracks_busy')) {
		throw new DataFailure(DataErrorKind.BUSY, 'tracks_busy', body);
	}
	if (response.status === 504 || (body && body.error === 'tracks_timeout')) {
		throw new DataFailure(DataErrorKind.TIMEOUT, 'tracks_timeout', body);
	}
	if (response.status === 503) {
		throw new DataFailure(DataErrorKind.NO_SERVER, 'HTTP 503', body);
	}
	if (!response.ok) {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'HTTP ' + response.status, body);
	}
	if (!body || typeof body !== 'object') {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'malformed tracks JSON', body);
	}
	return body;
}

/**
 * Live train snapshot. Polling is driven by the caller; this helper only owns the request lifetime
 * and the busy/timeout mapping so the overlay can back off instead of retrying immediately.
 *
 * @throws {DataFailure}
 */
export async function fetchTrains(options) {
	const params = new URLSearchParams();
	params.set('world', options.world);
	params.set('limit', String(options.limit));
	if (options.route && options.route !== 'all') {
		params.set('route', options.route);
	}
	if (options.depot && options.depot !== 'all') {
		params.set('depot', options.depot);
	}

	const controller = new AbortController();
	let timedOut = false;
	const timer = setTimeout(() => {
		timedOut = true;
		controller.abort();
	}, TRAINS_TIMEOUT_MS);
	const externalSignal = options.signal;
	const relayAbort = () => controller.abort();
	if (externalSignal) {
		if (externalSignal.aborted) {
			controller.abort();
		} else {
			externalSignal.addEventListener('abort', relayAbort, { once: true });
		}
	}

	let response;
	try {
		response = await fetch(TRAINS_ENDPOINT + '?' + params.toString(), {
			cache: 'no-store',
			credentials: 'same-origin',
			signal: controller.signal
		});
	} catch (error) {
		if (externalSignal && externalSignal.aborted) {
			throw new DataFailure(DataErrorKind.ABORTED, 'aborted');
		}
		if (timedOut) {
			throw new DataFailure(DataErrorKind.TIMEOUT, 'the trains request timed out');
		}
		throw new DataFailure(DataErrorKind.UNREACHABLE, error && error.message);
	} finally {
		clearTimeout(timer);
		if (externalSignal) {
			externalSignal.removeEventListener('abort', relayAbort);
		}
	}

	let body = null;
	try {
		body = await response.json();
	} catch (error) {
		body = null;
	}

	if (response.status === 401) {
		throw new DataFailure(DataErrorKind.AUTH, 'HTTP 401', body);
	}
	if (response.status === 403) {
		throw new DataFailure(DataErrorKind.FORBIDDEN, 'HTTP 403', body);
	}
	if (response.status === 404 && body && body.error === 'world_not_found') {
		throw new DataFailure(DataErrorKind.NOT_FOUND, 'world_not_found', body);
	}
	if (response.status === 429 || (body && body.error === 'trains_busy')) {
		throw new DataFailure(DataErrorKind.BUSY, 'trains_busy', body);
	}
	if (response.status === 504 || (body && body.error === 'trains_timeout')) {
		throw new DataFailure(DataErrorKind.TIMEOUT, 'trains_timeout', body);
	}
	if (response.status === 503) {
		throw new DataFailure(DataErrorKind.NO_SERVER, 'HTTP 503', body);
	}
	if (!response.ok) {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'HTTP ' + response.status, body);
	}
	if (!body || typeof body !== 'object' || !Array.isArray(body.trains)) {
		throw new DataFailure(DataErrorKind.BAD_REQUEST, 'malformed trains JSON', body);
	}
	return body;
}
