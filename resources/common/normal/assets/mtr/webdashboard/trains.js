import { fetchTrains, DataErrorKind } from './api.js?v=65';
import { getTrainSettings, TRAIN_EVENT } from './train-settings.js?v=65';
import { argbToRgba } from './mapview.js?v=65';
import { isShowingTracks } from './tracks.js?v=65';
import { centerOf, clamp, currentCenters, mergeFrame, mergeTrains } from './train-records.js?v=65';
import { openTrainStream } from './train-stream.js?v=65';

const RETRY_BUSY_MS = 1200;
const RETRY_OFFLINE_MS = 5000;
const MIN_POLL_MS = 400;
const FRAME_INTERVAL_MS = 16;
const TRACKING_SCALE = 4;
const FLY_MS = 520;
const VISIBLE_MARGIN_PX = 160;
const STREAM_RESTART_DEBOUNCE_MS = 300;
const POLL_INTERVAL_MS = 1000;
const STATUS_TICK_MS = 1000;
const STREAM_STALE_MS = 8000;
const POLL_STALE_MS = 3000;

let initialized = false;
let available = false;
let world = null;
let mapView = null;
let settings = getTrainSettings();
let records = new Map();
let selectedId = null;
let trackingId = null;
let total = 0;
let truncated = false;
let abortController = null;
let pollTimer = null;
let pollGeneration = 0;
let inFlight = false;
let frame = null;
let lastFrameAt = 0;
let lastPayloadAt = 0;
let lastDataAt = 0;
let lastBuildMs = 0;
let animUntil = 0;
let trackingMissing = 0;
let viewActive = false;
let visibleCount = 0;
let streamSupported = false;
let streamFailed = false;
let stream = null;
let streamMode = false;
let streamError = null;
let streamRestartTimer = null;
let statusTimer = null;

let screenX = new Float64Array(0);
let screenY = new Float64Array(0);

const handlers = {
	redraw: () => {},
	redrawTrains: null,
	onState: () => {},
	onSelect: () => {},
	onTracking: () => {},
	onTrackingLost: () => {},
	onTrackingMove: null,
	focus: null,
	follow: null,
	onAuthFailure: null
};

function drawTrains() {
	const handler = handlers.redrawTrains || handlers.redraw;
	handler();
}

function canRun() {
	return initialized
		&& settings.enabled
		&& available
		&& world != null
		&& mapView != null
		&& mapView.scale >= settings.minScale
		&& (typeof document === 'undefined' || document.visibilityState !== 'hidden')
		&& isShowingTracks();
}

function streamWanted() {
	return canRun() && streamSupported && !streamFailed;
}

function buildStreamUrl() {
	const params = new URLSearchParams();
	params.set('world', world.dimension);
	params.set('limit', String(settings.max));
	params.set('hz', String(settings.rateHz));
	if (settings.route && settings.route !== 'all') {
		params.set('route', settings.route);
	}
	if (settings.depot && settings.depot !== 'all') {
		params.set('depot', settings.depot);
	}
	return '/api/trains/stream?' + params.toString();
}

function connectStream() {
	if (stream || !streamWanted()) {
		return;
	}
	const handle = openTrainStream({
		url: buildStreamUrl(),
		onOpen: () => {
			streamMode = true;
			lastPayloadAt = 0;
			stopPolling();
			markState();
		},
		onFull: payload => applyPayload(payload, false),
		onFrame: payload => applyPayload(payload, true),
		onAuth: () => {
			closeStream();
			if (handlers.onAuthFailure) {
				handlers.onAuthFailure();
			}
		},
		onError: reason => {
			streamError = reason;
			streamFailed = true;
			closeStream();
			schedulePoll(0);
			markState();
		}
	});
	stream = handle;
	if (streamFailed) {
		handle.close();
		stream = null;
	}
}

function closeStream() {
	if (stream) {
		stream.close();
		stream = null;
	}
	streamMode = false;
	lastPayloadAt = 0;
}

function restartStreamSoon() {
	clearTimeout(streamRestartTimer);
	streamRestartTimer = setTimeout(() => {
		streamRestartTimer = null;
		const wasStream = streamMode;
		closeStream();
		if (streamWanted()) {
			connectStream();
		} else if (wasStream) {
			schedulePoll(0);
		}
	}, STREAM_RESTART_DEBOUNCE_MS);
}

export function setStreamAvailable(nextAvailable) {
	streamSupported = Boolean(nextAvailable);
	if (!streamSupported) {
		const wasStream = streamMode;
		closeStream();
		if (wasStream) {
			schedulePoll(0);
		}
	} else if (!streamFailed && !stream && canRun()) {
		connectStream();
	}
	markState();
}

function markState() {
	const streamState = stream ? stream.getState() : null;
	const ageMs = streamMode && streamState && streamState.ageMs != null
		? streamState.ageMs
		: lastDataAt > 0 ? performance.now() - lastDataAt : null;
	const stale = ageMs != null && ageMs > (streamMode ? STREAM_STALE_MS : POLL_STALE_MS);
	handlers.onState({
		enabled: settings.enabled,
		available,
		tracking: trackingId != null,
		selected: selectedId,
		total,
		rendered: records.size,
		truncated,
		animating: frame != null,
		mode: streamMode ? 'stream' : 'poll',
		stale,
		degraded: streamSupported && streamFailed,
		dataAgeMs: ageMs
	});
}

function startStatusTimer() {
	if (statusTimer != null || typeof setInterval !== 'function') {
		return;
	}
	statusTimer = setInterval(() => {
		if (initialized && settings.enabled) {
			markState();
		}
	}, STATUS_TICK_MS);
}

function stopStatusTimer() {
	if (statusTimer != null) {
		clearInterval(statusTimer);
		statusTimer = null;
	}
}

export function init(options) {
	Object.assign(handlers, options || {});
	initialized = true;
	settings = getTrainSettings();
	if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
		window.addEventListener(TRAIN_EVENT, onSettingsChange);
		document.addEventListener('visibilitychange', onVisibilityChange);
	}
	startStatusTimer();
	markState();
}

export function dispose() {
	initialized = false;
	stopPolling();
	stopAnimation();
	closeStream();
	stopStatusTimer();
	clearTimeout(streamRestartTimer);
	if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
		window.removeEventListener(TRAIN_EVENT, onSettingsChange);
		document.removeEventListener('visibilitychange', onVisibilityChange);
	}
}

export function setWorld(nextWorld) {
	const nextDimension = nextWorld && nextWorld.dimension ? nextWorld.dimension : null;
	const currentDimension = world && world.dimension ? world.dimension : null;
	if (nextDimension === currentDimension) {
		world = nextWorld || null;
		return;
	}
	world = nextWorld || null;
	stopPolling();
	closeStream();
	records = new Map();
	total = 0;
	truncated = false;
	selectedId = null;
	animUntil = 0;
	lastPayloadAt = 0;
	lastDataAt = 0;
	stopTracking(false);
	handlers.onSelect(null);
	markState();
	schedulePoll(0);
	if (streamSupported && !streamFailed) {
		connectStream();
	}
}

export function setAvailable(nextAvailable) {
	available = Boolean(nextAvailable);
	if (!available) {
		selectedId = null;
		stopTracking(false);
		if (handlers.onSelect) {
			handlers.onSelect(null);
		}
		stopPolling();
		closeStream();
		stopAnimation();
	} else {
		schedulePoll(0);
		if (streamSupported && !streamFailed) {
			connectStream();
		}
	}
	markState();
}

export function isAvailable() {
	return available;
}

export function onViewChange(nextView) {
	mapView = nextView ? {
		centerX: nextView.centerX,
		centerZ: nextView.centerZ,
		scale: nextView.scale,
		width: nextView.width,
		height: nextView.height
	} : null;
	const active = mapView != null
		&& mapView.scale >= settings.minScale
		&& settings.enabled
		&& available
		&& world != null
		&& isShowingTracks();
	if (active !== viewActive) {
		viewActive = active;
		if (active) {
			schedulePoll(0);
			if (streamSupported && !streamFailed) {
				connectStream();
			}
			ensureAnimation();
		} else {
			stopPolling();
			closeStream();
			animUntil = 0;
			stopAnimation();
		}
	}
}

function onSettingsChange(event) {
	const previous = settings;
	settings = getTrainSettings();
	const detail = event && event.detail ? event.detail.key : null;

	if (!settings.enabled) {
		stopPolling();
		closeStream();
		stopAnimation();
		selectedId = null;
		stopTracking(false);
		handlers.onSelect(null);
	} else if (detail === 'route' || detail === 'depot' || detail === 'max' || previous.rateHz !== settings.rateHz) {
		stopPolling();
		lastPayloadAt = 0;
		restartStreamSoon();
		schedulePoll(0);
	}
	markState();
	handlers.redraw();
	if (settings.enabled && available && mapView != null && mapView.scale >= settings.minScale && isShowingTracks()) {
		if (streamSupported && !streamFailed) {
			connectStream();
		} else {
			schedulePoll(0);
		}
	}
}

function onVisibilityChange() {
	if (document.visibilityState === 'hidden') {
		stopPolling();
		closeStream();
		stopAnimation();
	} else {
		schedulePoll(0);
		if (streamSupported && !streamFailed && canRun()) {
			connectStream();
		}
		ensureAnimation();
	}
}

function schedulePoll(delay) {
	if (!initialized || !settings.enabled || streamMode) {
		return;
	}
	clearTimeout(pollTimer);
	pollTimer = setTimeout(poll, Math.max(0, delay));
}

function stopPolling() {
	clearTimeout(pollTimer);
	pollTimer = null;
	pollGeneration++;
	if (abortController) {
		abortController.abort();
		abortController = null;
	}
	inFlight = false;
}

async function poll() {
	pollTimer = null;
	if (streamMode) {
		return;
	}
	if (inFlight || !canRun()) {
		schedulePoll(canRun() ? MIN_POLL_MS : 1000);
		return;
	}
	inFlight = true;
	const generation = ++pollGeneration;
	const controller = new AbortController();
	abortController = controller;
	let retryDelay = POLL_INTERVAL_MS;

	try {
		const payload = await fetchTrains({
			world: world.dimension,
			limit: settings.max,
			route: settings.route,
			depot: settings.depot,
			signal: controller.signal
		});
		if (generation !== pollGeneration) {
			return;
		}
		applyPayload(payload, false);
	} catch (error) {
		if (generation !== pollGeneration || (error && error.kind === DataErrorKind.ABORTED)) {
			return;
		}
		if (error && (error.kind === DataErrorKind.BUSY || error.kind === DataErrorKind.TIMEOUT)) {
			retryDelay = RETRY_BUSY_MS * (0.7 + Math.random() * 0.6);
		} else if (error && (error.kind === DataErrorKind.UNREACHABLE || error.kind === DataErrorKind.NO_SERVER
			|| error.kind === DataErrorKind.NOT_FOUND)) {
			retryDelay = RETRY_OFFLINE_MS;
		} else if (error && error.kind === DataErrorKind.AUTH && handlers.onAuthFailure) {
			handlers.onAuthFailure();
		}
	} finally {
		if (generation === pollGeneration) {
			inFlight = false;
			abortController = null;
			schedulePoll(canRun() ? retryDelay : 1000);
		}
	}
}

function applyPayload(payload, incremental) {
	if (payload && payload.v != null && payload.v !== 1) {
		console.warn('[MTR-WebDashboard] ignoring a train payload with protocol version ' + payload.v);
		return;
	}
	const now = performance.now();
	const fallback = incremental ? 200 : POLL_INTERVAL_MS;
	const measured = lastPayloadAt > 0 ? now - lastPayloadAt : fallback;
	lastPayloadAt = now;
	lastDataAt = now;
	lastBuildMs = Number(payload.buildMs) || 0;
	const duration = clamp(measured, 80, 15000);

	const merged = incremental
		? mergeFrame(records, payload.trains, payload.gone, now, duration)
		: mergeTrains(records, payload.trains, now, duration);
	records = merged.records;
	total = Number(payload.total) || records.size;
	truncated = Boolean(payload.truncated);
	if (merged.moved || trackingId != null) {
		animUntil = now + duration + 260;
	}

	if (selectedId && !records.has(selectedId)) {
		selectedId = null;
		handlers.onSelect(null);
	} else if (selectedId) {
		handlers.onSelect(records.get(selectedId));
	}

	if (trackingId && !records.has(trackingId)) {
		trackingMissing++;
		if (trackingMissing >= 2) {
			stopTracking(true);
		}
	} else if (trackingId) {
		trackingMissing = 0;
		handlers.onTracking(records.get(trackingId));
	}

	ensureAnimation();
	if (incremental) {
		drawTrains();
	} else {
		handlers.redraw();
	}
	markState();
}

function ensureBuffers(size) {
	if (screenX.length < size) {
		screenX = new Float64Array(size);
		screenY = new Float64Array(size);
	}
}

function ensureAnimation() {
	if (frame != null || !initialized || !settings.enabled || records.size === 0) {
		return;
	}
	if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
		return;
	}
	if (trackingId == null && performance.now() > animUntil) {
		return;
	}
	frame = requestAnimationFrame(step);
}

function step(now) {
	frame = null;
	// Tracking redraws every display frame: gating the camera at a fixed 16 ms made it advance in
	// irregular steps on 120/144 Hz displays, which reads as judder. Normal interpolation keeps the
	// 60 Hz cap because it repaints the whole map for every train.
	if (trackingId != null || now - lastFrameAt >= FRAME_INTERVAL_MS) {
		lastFrameAt = now;
		let followed = false;
		if (trackingId != null) {
			const record = records.get(trackingId);
			if (record) {
				followed = followRecord(record);
			}
		}
		if (!followed) {
			drawTrains();
		}
	}
	const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
	if (initialized && settings.enabled && records.size > 0 && !hidden
		&& (trackingId != null || performance.now() <= animUntil)) {
		frame = requestAnimationFrame(step);
	}
}

function stopAnimation() {
	if (frame != null) {
		cancelAnimationFrame(frame);
		frame = null;
	}
}

export function draw(context, view, mapTheme) {
	if (!context || !view || records.size === 0 || !settings.enabled || !available
		|| view.scale < settings.minScale || !isShowingTracks()) {
		return;
	}
	const now = performance.now();
	const selected = selectedId;
	const tracking = trackingId;
	const scale = view.scale;
	const centerX = view.centerX;
	const centerZ = view.centerZ;
	const halfWidth = view.width / 2;
	const halfHeight = view.height / 2;
	visibleCount = 0;

	context.save();
	context.globalAlpha = 1;
	context.lineCap = 'round';
	context.lineJoin = 'round';

	for (const record of records.values()) {
		const centers = currentCenters(record, now);
		const count = centers.length / 2;
		if (count === 0) {
			continue;
		}
		ensureBuffers(count);
		let minX = Infinity;
		let minY = Infinity;
		let maxX = -Infinity;
		let maxY = -Infinity;
		for (let i = 0; i < count; i++) {
			const x = (centers[i * 2] - centerX) * scale + halfWidth;
			const y = (centers[i * 2 + 1] - centerZ) * scale + halfHeight;
			screenX[i] = x;
			screenY[i] = y;
			if (x < minX) minX = x;
			if (y < minY) minY = y;
			if (x > maxX) maxX = x;
			if (y > maxY) maxY = y;
		}
		const margin = Math.max(6, record.spacing * scale * 0.5) + VISIBLE_MARGIN_PX;
		if (maxX < -margin || minX > view.width + margin || maxY < -margin || minY > view.height + margin) {
			continue;
		}
		visibleCount++;
		drawRecord(context, view, mapTheme, record, screenX, screenY, count,
			record.id === selected || record.id === tracking);
	}
	context.restore();

	if (visibleCount === 0 && trackingId == null) {
		animUntil = 0;
	}
}

function drawRecord(context, view, theme, record, xs, ys, count, highlighted) {
	const argb = record.color >>> 0;
	const opaque = (argb >>> 24) === 0 ? (argb | 0xFF000000) : argb;
	const color = record.color !== 0 ? argbToRgba(opaque) : (theme.accent || 'rgba(255, 255, 255, 0.9)');
	const outline = theme.light ? 'rgba(24, 27, 31, 0.9)' : 'rgba(255, 255, 255, 0.55)';
	// World-space dimensions: the car body is (spacing - 1) blocks long and `width` blocks wide,
	// independent of the screen distance to its neighbour. That distance shrinks on curves, which is
	// what used to make cars look like they were shrinking, and it must never drive the width.
	const carLength = Math.max(0.5, (record.spacing - 1) * view.scale);
	const carWidth = Math.max(0.5, record.width * view.scale);

	if (settings.consist === 'block') {
		const centerX = average(xs, count);
		const centerY = average(ys, count);
		const angle = carAngle(record, xs, ys, count, Math.min(count - 1, Math.floor(count / 2)));
		const length = Math.max(1, (record.spacing * record.cars - 1) * view.scale);
		drawCar(context, centerX, centerY, angle, length, Math.max(0.5, carWidth), color, outline, highlighted);
		return;
	}

	for (let i = 0; i < count; i++) {
		const angle = carAngle(record, xs, ys, count, i);
		drawCar(context, xs[i], ys[i], angle, carLength, carWidth, color, outline, highlighted);
	}
}

function average(values, count) {
	let sum = 0;
	for (let i = 0; i < count; i++) {
		sum += values[i];
	}
	return count > 0 ? sum / count : 0;
}

function carAngle(record, xs, ys, count, index) {
	if (count <= 1) {
		return record.heading;
	}
	let first = Math.max(0, index - 1);
	let second = Math.min(count - 1, index + 1);
	if (first === second) {
		return record.heading;
	}
	return Math.atan2(ys[second] - ys[first], xs[second] - xs[first]);
}

function drawCar(context, centerX, centerY, angle, length, width, color, outline, highlighted) {
	const radius = Math.max(0.5, Math.min(length, width) * 0.2);

	context.save();
	context.translate(centerX, centerY);
	context.rotate(angle);
	if (highlighted) {
		context.beginPath();
		context.roundRect(-length / 2 - 3, -width / 2 - 3, length + 6, width + 6, radius + 2);
		context.fillStyle = 'rgba(255, 255, 255, 0.12)';
		context.fill();
		context.lineWidth = 1;
		context.strokeStyle = 'rgba(255, 255, 255, 0.8)';
		context.stroke();
	}
	context.beginPath();
	context.roundRect(-length / 2, -width / 2, length, width, radius);
	context.fillStyle = color;
	context.fill();
	context.lineWidth = 1;
	context.strokeStyle = highlighted ? 'rgba(255, 255, 255, 0.95)' : outline;
	context.stroke();
	context.restore();
}

function followRecord(record) {
	if (!record || !handlers.follow || !mapView) {
		return false;
	}
	const center = centerOf(record, currentCenters(record, performance.now()));
	const followed = Boolean(handlers.follow(center.x, center.z));
	// follow() redraws the map, which refreshes mapView through onViewChange. Project the toast
	// position *after* that, otherwise it trails the camera by one frame and visibly jitters.
	if (handlers.onTrackingMove && mapView) {
		const screen = project(mapView, center.x, center.z);
		handlers.onTrackingMove(screen.x, screen.y);
	}
	return followed;
}

function project(view, worldX, worldZ) {
	return {
		x: (worldX - view.centerX) * view.scale + view.width / 2,
		y: (worldZ - view.centerZ) * view.scale + view.height / 2
	};
}

export function trySelect(detail) {
	if (!detail || !mapView || !records.size || !canRun()) {
		return false;
	}
	const now = performance.now();
	let best = null;
	let bestDistance = Infinity;
	for (const record of records.values()) {
		const centers = currentCenters(record, now);
		const threshold = settings.consist === 'block'
			? Math.max(10, record.spacing * record.cars * mapView.scale * 0.42)
			: Math.max(6, record.spacing * mapView.scale * 0.58);
		for (let i = 0; i < centers.length; i += 2) {
			const screen = project(mapView, centers[i], centers[i + 1]);
			const distance = Math.hypot(detail.x - screen.x, detail.y - screen.y);
			if (distance <= threshold && distance < bestDistance) {
				bestDistance = distance;
				best = record;
			}
		}
	}
	if (!best) {
		return false;
	}
	const switchTracking = trackingId != null && trackingId !== best.id;
	selectTrain(best.id);
	if (switchTracking) {
		track(best.id);
	}
	return true;
}

export function selectTrain(id) {
	const record = records.get(String(id));
	if (!record) {
		return null;
	}
	selectedId = record.id;
	handlers.onSelect(record);
	handlers.redraw();
	markState();
	return record;
}

export function clearSelection() {
	if (!selectedId) {
		return false;
	}
	selectedId = null;
	handlers.redraw();
	markState();
	return true;
}

export function track(id) {
	const record = records.get(String(id));
	if (!record) {
		return false;
	}
	trackingId = record.id;
	selectedId = record.id;
	trackingMissing = 0;
	handlers.onSelect(record);
	handlers.onTracking(record);
	if (handlers.focus) {
		const center = centerOf(record, currentCenters(record, performance.now()));
		handlers.focus(center.x, center.z, Math.max(TRACKING_SCALE, mapView ? mapView.scale : TRACKING_SCALE), FLY_MS);
	}
	animUntil = performance.now() + 1000;
	ensureAnimation();
	markState();
	handlers.redraw();
	return true;
}

export function stopTracking(notify) {
	const wasTracking = trackingId != null;
	trackingId = null;
	trackingMissing = 0;
	if (wasTracking) {
		markState();
		handlers.redraw();
		if (notify) {
			selectedId = null;
			handlers.onSelect(null);
			handlers.onTrackingLost();
		}
	}
}

export function isTracking() {
	return trackingId != null;
}

export function invalidate() {
	stopPolling();
	closeStream();
	lastPayloadAt = 0;
	if (streamWanted()) {
		connectStream();
	}
	schedulePoll(0);
}

export function getDebugState() {
	return {
		enabled: settings.enabled,
		available,
		world: world ? world.dimension : null,
		mode: streamMode ? 'stream' : 'poll',
		streamSupported,
		streamFailed,
		streamError,
		stream: stream ? stream.getState() : null,
		total,
		truncated,
		rendered: records.size,
		visible: visibleCount,
		tracking: trackingId,
		selected: selectedId,
		animating: frame != null,
		inFlight,
		lastBuildMs,
		settings: { ...settings }
	};
}
