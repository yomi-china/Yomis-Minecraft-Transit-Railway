const DATA_ENDPOINT = '/api/data';
const META_ENDPOINT = '/api/meta';

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
