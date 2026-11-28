/**
 * Pure train state: parsing, merging and interpolation.
 *
 * No DOM, no timers, no fetch — the maths that is easy to get subtly wrong and cheap to test in Node.
 * Positions are flat Float64Arrays ([x0, z0, x1, z1, ...]) to avoid allocating one object per car per frame.
 */

const PREDICT_LIMIT_MS = 900;
const MIN_DURATION_MS = 80;
const MAX_DURATION_MS = 15000;
const CENTER_EPSILON = 1e-6;

function number(value, fallback) {
	const parsed = Number(value);
	return Number.isFinite(parsed) ? parsed : fallback;
}

function toId(value) {
	return value == null ? null : String(value);
}

function finiteArray(raw) {
	if (!Array.isArray(raw) || raw.length === 0 || raw.length % 2 !== 0) {
		return null;
	}
	const out = new Float64Array(raw.length);
	for (let i = 0; i < raw.length; i++) {
		const value = Number(raw[i]);
		if (!Number.isFinite(value)) {
			return null;
		}
		out[i] = value;
	}
	return out;
}

/**
 * @returns {object|null} a validated record, or null when the payload cannot be drawn safely.
 */
export function parseTrain(raw) {
	if (!raw || raw.id == null) {
		return null;
	}
	const centers = finiteArray(raw.centers);
	if (!centers) {
		return null;
	}
	const cars = Number(raw.cars);
	if (!Number.isInteger(cars) || cars < 1 || centers.length !== cars * 2) {
		return null;
	}
	const spacing = Math.max(1, Math.round(number(raw.spacing, 1)));
	const width = Math.max(1, Math.round(number(raw.width, spacing * 0.08)));
	const kind = raw.nextStopKind;
	const nextStopId = toId(raw.nextStopId != null ? raw.nextStopId : raw.nextPlatformId);
	return {
		id: String(raw.id),
		sidingId: toId(raw.sidingId),
		routeId: toId(raw.routeId),
		depotId: toId(raw.depotId),
		nextStopId,
		nextStopKind: kind === 'platform' || kind === 'siding' || kind === 'none'
			? kind
			: nextStopId == null ? 'none' : 'platform',
		cars,
		spacing,
		width,
		color: Math.trunc(number(raw.color, 0)),
		speed: number(raw.speed, 0),
		doors: number(raw.doors, 0),
		onRoute: Boolean(raw.onRoute),
		manual: Boolean(raw.manual),
		reversed: Boolean(raw.reversed),
		heading: number(raw.heading, 0),
		centers,
		toCenters: centers,
		fromCenters: centers,
		centerVelocity: null,
		start: 0,
		duration: 0,
		receivedAt: 0
	};
}

export function sameCenters(a, b) {
	if (!a || !b || a.length !== b.length) {
		return false;
	}
	for (let i = 0; i < a.length; i++) {
		if (Math.abs(a[i] - b[i]) > CENTER_EPSILON) {
			return false;
		}
	}
	return true;
}

/**
 * Merge a full payload into the previous record map.
 *
 * @param {Map<string, object>} previous records from the last payload
 * @param {Array<object>} incoming raw trains
 * @param {number} now performance.now() at arrival
 * @param {number} frameDurationMs expected time until the next payload
 * @returns {{records: Map<string, object>, moved: boolean}} the new map and whether anything moved
 */
function applyOne(records, lookup, raw, now, duration) {
	const next = parseTrain(raw);
	if (!next) {
		return false;
	}
	const before = lookup ? lookup.get(next.id) : null;
	const compatible = before != null
		&& before.cars === next.cars
		&& before.reversed === next.reversed
		&& before.toCenters.length === next.centers.length;

	if (compatible) {
		const changed = !sameCenters(before.toCenters, next.centers);
		const elapsed = Math.max(1, now - (before.receivedAt || now - duration));
		next.fromCenters = changed ? currentCenters(before, now) : new Float64Array(next.centers);
		next.centerVelocity = changed ? estimateVelocity(before.toCenters, next.centers, elapsed, next.speed) : null;
		next.start = now;
		next.duration = changed ? duration : 0;
		next.receivedAt = now;
		records.set(next.id, next);
		return changed;
	}

	next.fromCenters = new Float64Array(next.centers);
	next.start = now;
	next.duration = 0;
	next.receivedAt = now;
	records.set(next.id, next);
	return false;
}

function clampDuration(frameDurationMs) {
	return Math.min(MAX_DURATION_MS, Math.max(MIN_DURATION_MS, frameDurationMs));
}

/**
 * Rebuild the record map from a full payload.
 *
 * @param {Map<string, object>} previous records from the last payload
 * @param {Array<object>} incoming raw trains
 * @param {number} now performance.now() at arrival
 * @param {number} frameDurationMs expected time until the next payload
 * @returns {{records: Map<string, object>, moved: boolean}} the new map and whether anything moved
 */
export function mergeTrains(previous, incoming, now, frameDurationMs) {
	const records = new Map();
	const duration = clampDuration(frameDurationMs);
	let moved = false;

	for (const raw of Array.isArray(incoming) ? incoming : []) {
		moved = applyOne(records, previous, raw, now, duration) || moved;
	}

	return { records, moved };
}

/**
 * Merge a delta frame: listed trains are updated, {@code gone} ids are removed, everything else is kept.
 *
 * @param {Map<string, object>} previous records from the previous frame
 * @param {Array<object>} incoming changed trains
 * @param {Array<string>} gone ids that disappeared
 * @returns {{records: Map<string, object>, moved: boolean}}
 */
export function mergeFrame(previous, incoming, gone, now, frameDurationMs) {
	const records = new Map(previous || []);
	const duration = clampDuration(frameDurationMs);
	let moved = false;

	for (const id of Array.isArray(gone) ? gone : []) {
		records.delete(String(id));
	}
	for (const raw of Array.isArray(incoming) ? incoming : []) {
		moved = applyOne(records, records, raw, now, duration) || moved;
	}

	return { records, moved };
}

/**
 * @returns {Float64Array} interpolated car centres at {@code now}. Returns the stored target array when
 * the record is static, so the common stopped-train path allocates nothing.
 */
export function currentCenters(record, now) {
	if (!record || !record.toCenters) {
		return new Float64Array(0);
	}
	if (record.duration <= 0) {
		return record.toCenters;
	}
	const elapsed = now - record.start;
	if (elapsed >= record.duration) {
		const extra = Math.min(elapsed - record.duration, PREDICT_LIMIT_MS);
		const velocity = record.centerVelocity;
		if (extra <= 0 || !velocity || velocity.length !== record.toCenters.length) {
			return record.toCenters;
		}
		const out = new Float64Array(record.toCenters.length);
		for (let i = 0; i < out.length; i += 2) {
			out[i] = record.toCenters[i] + velocity[i] * extra;
			out[i + 1] = record.toCenters[i + 1] + velocity[i + 1] * extra;
		}
		return out;
	}
	const t = Math.max(0, elapsed / record.duration);
	const out = new Float64Array(record.toCenters.length);
	for (let i = 0; i < out.length; i++) {
		const from = record.fromCenters[i];
		out[i] = from + (record.toCenters[i] - from) * t;
	}
	return out;
}

/**
 * @returns {Float64Array|null} per-coordinate velocity in blocks per millisecond, clamped to a little
 * over the train's own speed so a snap in the data cannot launch it across the map.
 */
export function estimateVelocity(previousPoints, nextPoints, elapsedMs, speed) {
	const limit = Math.max(0, speed) * 20 / 1000 * 1.25;
	if (!(limit > 0) || !previousPoints || !nextPoints || previousPoints.length !== nextPoints.length) {
		return null;
	}
	const out = new Float64Array(nextPoints.length);
	const elapsed = Math.max(1, elapsedMs);
	for (let i = 0; i < out.length; i += 2) {
		let x = (nextPoints[i] - previousPoints[i]) / elapsed;
		let z = (nextPoints[i + 1] - previousPoints[i + 1]) / elapsed;
		const length = Math.hypot(x, z);
		if (length > limit) {
			const scale = limit / length;
			x *= scale;
			z *= scale;
		}
		out[i] = x;
		out[i + 1] = z;
	}
	return out;
}

export function centerOf(record, points) {
	const length = points ? points.length : 0;
	if (length < 2) {
		return { x: 0, z: 0 };
	}
	if (record && record.cars === 1) {
		return { x: points[0], z: points[1] };
	}
	let x = 0;
	let z = 0;
	for (let i = 0; i < length; i += 2) {
		x += points[i];
		z += points[i + 1];
	}
	const count = length / 2;
	return { x: x / count, z: z / count };
}

export function clamp(value, min, max) {
	return Math.min(max, Math.max(min, value));
}
