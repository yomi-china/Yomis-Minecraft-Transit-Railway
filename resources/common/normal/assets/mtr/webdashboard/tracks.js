import { DataErrorKind, DataFailure, fetchTracks } from './api.js?v=49';
import { argbToRgba, visibleWorldBounds } from './mapview.js?v=49';
import { getOverlaySettings, setOverlaySetting, OVERLAY_EVENT, OVERLAY_DEFAULTS } from './overlay-settings.js?v=49';

/**
 * Track overlay: viewport-scoped rail geometry.
 *
 * The server is the authority for geometry and for the tick-cost budget. This module only decides
 * when to ask, how much it is willing to keep, and how to draw it without turning the canvas into a
 * per-segment stroke loop. Two hard limits live here:
 *
 *  - more than MAX_RENDER_SEGMENTS rails in one response: do not build Path2D at all, draw route
 *    trunks instead (low-end browsers must not be asked to allocate hundreds of thousands of path
 *    commands),
 *  - more than MAX_RENDER_POINTS points: ask once for the coarser detail, then fall back to trunks.
 */

export const MIN_TRACKS_SCALE = OVERLAY_DEFAULTS.minScale;
export const MAX_RENDER_SEGMENTS = 5000;
export const MAX_RENDER_POINTS = 40000;
export const MAX_SINGLE_RESPONSE_BYTES = 2 * 1024 * 1024;
export const MAX_CACHE_BYTES = 8 * 1024 * 1024;
export const MAX_CACHE_ENTRIES = 6;
export const CACHE_TTL_MS = 30000;
export const VIEW_DEBOUNCE_MS = 180;

const BOUNDS_GRID = 256;
const DIRECT_DRAW_MAX_SEGMENTS = 2000;
const BOUNDS_MARGIN_RATIO = 0.25;
const FALLBACK_COLOR = '#90caf9';

let callbacks = {};
let initialized = false;
let overlaySettings = getOverlaySettings();
let enabled = overlaySettings.enabled;
let available = false;
let world = null;
let mode = 'all';
let view = null;
let cache = new Map();
let current = null;
let inFlight = null;
let debounceTimer = null;
let requestSequence = 0;
let lastError = null;
let degraded = false;
let busy = false;
let routeTrunks = null;
let trunkPaths = null;
let scheduledRedraw = false;
let lastLayer = null;

/** @returns {0|1|2|null} the detail tier for a zoom scale, or null when tracks should not load. */
export function detailForScale(scale, minScale = OVERLAY_DEFAULTS.minScale) {
	if (!Number.isFinite(scale) || !Number.isFinite(minScale) || scale < minScale) {
		return null;
	}
	if (scale < 3) {
		return 0;
	}
	if (scale < 12) {
		return 1;
	}
	return 2;
}

/** @returns {{minX:number,minZ:number,maxX:number,maxZ:number}|null} viewport bounds plus pan margin. */
export function expandBounds(mapView, marginRatio = BOUNDS_MARGIN_RATIO) {
	if (!mapView || !Number.isFinite(mapView.width) || !Number.isFinite(mapView.height)
		|| mapView.width <= 0 || mapView.height <= 0) {
		return null;
	}
	const marginPx = Math.max(64, Math.max(mapView.width, mapView.height) * marginRatio);
	const bounds = visibleWorldBounds(mapView, marginPx);
	if (![bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ].every(Number.isFinite)) {
		return null;
	}
	return bounds;
}

/** Snaps bounds outwards to a grid so small pans reuse the same server answer. */
export function snapBounds(bounds, grid = BOUNDS_GRID) {
	if (!bounds || ![bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ].every(Number.isFinite)) {
		return null;
	}
	const size = Number.isFinite(grid) && grid > 0 ? grid : BOUNDS_GRID;
	return {
		minX: Math.floor(bounds.minX / size) * size,
		minZ: Math.floor(bounds.minZ / size) * size,
		maxX: Math.ceil(bounds.maxX / size) * size,
		maxZ: Math.ceil(bounds.maxZ / size) * size
	};
}

export function boundsKey(bounds, dimension, modeFilter, detail) {
	if (!bounds) {
		return '';
	}
	return [dimension || '', modeFilter || 'all', detail, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ].join('|');
}

/**
 * Decides whether a response is drawable, should be retried coarser, or should show trunks only.
 * Exported so the regression suite can exercise the 5000-segment / 40000-point limits without a DOM.
 * @returns {'render'|'retry-coarser'|'trunk'}
 */
export function planResponseUse(payload, requestedDetail) {
	if (!payload || payload.tooDense === true) {
		return 'trunk';
	}
	const counts = payload.counts && typeof payload.counts === 'object' ? payload.counts : {};
	const totalRails = Number(counts.totalRails);
	const renderedRails = Number(counts.rails);
	const points = Number(counts.points);
	if ((Number.isFinite(totalRails) && totalRails > MAX_RENDER_SEGMENTS)
		|| (Number.isFinite(renderedRails) && renderedRails > MAX_RENDER_SEGMENTS)) {
		return 'trunk';
	}
	if (Number.isFinite(points) && points > MAX_RENDER_POINTS) {
		return requestedDetail > 0 ? 'retry-coarser' : 'trunk';
	}
	if (!Array.isArray(payload.rails)) {
		return 'trunk';
	}
	if (payload.rails.length > MAX_RENDER_SEGMENTS) {
		return 'trunk';
	}
	return 'render';
}

/** Rough response size; used for cache/single-response limits, not for display. */
export function estimatePayloadBytes(payload) {
	const counts = payload && payload.counts && typeof payload.counts === 'object' ? payload.counts : {};
	const rails = Number.isFinite(Number(counts.rails)) ? Number(counts.rails) : Array.isArray(payload?.rails) ? payload.rails.length : 0;
	const points = Number.isFinite(Number(counts.points)) ? Number(counts.points) : rails * 8;
	return 256 + rails * 96 + points * 14;
}

/**
 * Converts the wire tuples into draw groups.
 * @returns {Array<{type:string, mode:string, platform:boolean, color:number, polylines:number[][], minX:number, minZ:number, maxX:number, maxZ:number}>}
 */
export function buildRailBatches(payload) {
	if (!payload || !Array.isArray(payload.rails)) {
		return [];
	}
	const types = payload.railTypes && typeof payload.railTypes === 'object' ? payload.railTypes : {};
	const groups = new Map();
	for (const entry of payload.rails) {
		if (!Array.isArray(entry) || entry.length < 4) {
			continue;
		}
		const type = typeof entry[0] === 'string' ? entry[0] : '';
		const transportMode = typeof entry[1] === 'string' ? entry[1] : '';
		const platformId = typeof entry[2] === 'string' ? entry[2] : '';
		const flat = entry[3];
		if (!Array.isArray(flat) || flat.length < 4 || flat.length % 2 !== 0) {
			continue;
		}
		let minX = Infinity;
		let minZ = Infinity;
		let maxX = -Infinity;
		let maxZ = -Infinity;
		for (let i = 0; i < flat.length; i += 2) {
			const x = flat[i];
			const z = flat[i + 1];
			if (!Number.isFinite(x) || !Number.isFinite(z)) {
				minX = Infinity;
				break;
			}
			minX = Math.min(minX, x);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxZ = Math.max(maxZ, z);
		}
		if (!Number.isFinite(minX) || !Number.isFinite(minZ) || !Number.isFinite(maxX) || !Number.isFinite(maxZ)) {
			continue;
		}
		const platform = platformId !== '';
		const key = type + '|' + transportMode + '|' + (platform ? 'p' : 'r');
		let group = groups.get(key);
		if (!group) {
			const color = Number(types[type]);
			group = {
				type,
				mode: transportMode,
				platform,
				color: Number.isFinite(color) && color !== 0 ? color : 0xFF90CAF9,
				polylines: [],
				minX,
				minZ,
				maxX,
				maxZ
			};
			groups.set(key, group);
		}
		group.polylines.push(flat);
		group.minX = Math.min(group.minX, minX);
		group.minZ = Math.min(group.minZ, minZ);
		group.maxX = Math.max(group.maxX, maxX);
		group.maxZ = Math.max(group.maxZ, maxZ);
	}
	return [...groups.values()];
}

/**
 * Schematic trunk lines at low zoom: platform centres in route order, closed for circular routes.
 * @returns {Array<{id:string, color:number, points:Array<{x:number,z:number}>}>}
 */
export function buildRouteTrunks(worldData, modeFilter = 'all') {
	if (!worldData || !Array.isArray(worldData.routes) || !Array.isArray(worldData.platforms)) {
		return [];
	}
	const platforms = new Map();
	for (const platform of worldData.platforms) {
		if (platform && platform.id != null) {
			platforms.set(String(platform.id), platform);
		}
	}
	const trunks = [];
	for (const route of worldData.routes) {
		if (!route || route.isHidden) {
			continue;
		}
		if (modeFilter && modeFilter !== 'all' && route.transportMode !== modeFilter) {
			continue;
		}
		const stops = Array.isArray(route.platformIds) ? route.platformIds : [];
		const points = [];
		for (const stop of stops) {
			const platformId = stop && stop.platformId != null ? String(stop.platformId) : null;
			const platform = platformId == null ? null : platforms.get(platformId);
			if (!platform || !Number.isFinite(platform.midX) || !Number.isFinite(platform.midZ)) {
				continue;
			}
			const point = { x: platform.midX, z: platform.midZ };
			const last = points[points.length - 1];
			if (!last || last.x !== point.x || last.z !== point.z) {
				points.push(point);
			}
		}
		if (points.length < 2) {
			continue;
		}
		if (route.circularState && route.circularState !== 'NONE') {
			const first = points[0];
			const last = points[points.length - 1];
			if (first.x !== last.x || first.z !== last.z) {
				points.push({ x: first.x, z: first.z });
			}
		}
		trunks.push({ id: String(route.id), color: Number(route.color) || 0, points });
	}
	return trunks;
}

function makePath2D(groups) {
	if (typeof Path2D === 'undefined') {
		return { groups, usesPath2D: false };
	}
	const withPaths = groups.map(group => {
		const path = new Path2D();
		for (const flat of group.polylines) {
			path.moveTo(flat[0], flat[1]);
			for (let i = 2; i < flat.length; i += 2) {
				path.lineTo(flat[i], flat[i + 1]);
			}
		}
		return { ...group, path };
	});
	return { groups: withPaths, usesPath2D: true };
}

function makeTrunkPaths(trunks) {
	if (typeof Path2D === 'undefined') {
		return trunks.map(trunk => ({ ...trunk, path: null }));
	}
	return trunks.map(trunk => {
		const path = new Path2D();
		if (trunk.points.length) {
			path.moveTo(trunk.points[0].x, trunk.points[0].z);
			for (let i = 1; i < trunk.points.length; i++) {
				path.lineTo(trunk.points[i].x, trunk.points[i].z);
			}
		}
		return { ...trunk, path };
	});
}

function notifyState() {
	if (callbacks.onState) {
		callbacks.onState({
			enabled,
			available,
			busy,
			degraded,
			error: lastError
		});
	}
}

function setBusy(next) {
	if (busy === next) {
		return;
	}
	busy = next;
	notifyState();
}

function queueRedraw() {
	if (scheduledRedraw) {
		return;
	}
	scheduledRedraw = true;
	const run = () => {
		scheduledRedraw = false;
		if (callbacks.redraw) {
			callbacks.redraw();
		}
	};
	if (typeof requestAnimationFrame === 'function') {
		requestAnimationFrame(run);
	} else {
		setTimeout(run, 0);
	}
}

function abortInFlight() {
	if (inFlight) {
		requestSequence++;
		inFlight.controller.abort();
		inFlight = null;
	}
}

function clearCache() {
	cache = new Map();
	current = null;
	degraded = false;
	lastError = null;
}

function cacheByteTotal() {
	let total = 0;
	for (const entry of cache.values()) {
		total += entry.bytes;
	}
	return total;
}

function putCache(entry) {
	cache.set(entry.key, entry);
	while (cache.size > MAX_CACHE_ENTRIES || cacheByteTotal() > MAX_CACHE_BYTES) {
		const oldest = cache.keys().next().value;
		if (oldest === undefined) {
			break;
		}
		cache.delete(oldest);
	}
}

function emptyEntry(key, bounds, detail, payload, degraded) {
	return {
		key,
		bounds,
		detail,
		counts: payload && payload.counts ? payload.counts : {},
		groups: [],
		usesPath2D: false,
		bytes: 0,
		fetchedAt: Date.now(),
		degraded,
		timeMs: payload && Number(payload.timeMs) ? Number(payload.timeMs) : 0
	};
}

function isStillRelevant(bounds, wantedDetail) {
	if (!view || !world) {
		return false;
	}
	const currentBounds = snapBounds(expandBounds(view));
	return boundsKey(currentBounds, world.dimension, mode, wantedDetail) === boundsKey(bounds, world.dimension, mode, wantedDetail);
}

function schedule() {
	if (!initialized || !enabled || !available || !view || !world) {
		return;
	}
	const detail = detailForScale(view.scale, overlaySettings.minScale);
	if (detail == null) {
		if (current != null) {
			current = null;
			queueRedraw();
		}
		return;
	}
	const bounds = snapBounds(expandBounds(view));
	if (!bounds) {
		return;
	}
	const key = boundsKey(bounds, world.dimension, mode, detail);
	const cached = cache.get(key);
	if (cached && Date.now() - cached.fetchedAt < CACHE_TTL_MS) {
		if (current !== cached) {
			current = cached;
			degraded = Boolean(cached.degraded);
			notifyState();
			queueRedraw();
		}
		return;
	}
	if (inFlight && inFlight.key === key) {
		return;
	}
	if (debounceTimer != null) {
		clearTimeout(debounceTimer);
	}
	debounceTimer = setTimeout(() => {
		debounceTimer = null;
		request(bounds, detail, key, { retried: false });
	}, VIEW_DEBOUNCE_MS);
}

function request(bounds, detail, key, options) {
	if (!initialized || !enabled || !available || !world) {
		return;
	}
	if (!isStillRelevant(bounds, detail)) {
		return;
	}
	if (inFlight) {
		requestSequence++;
		inFlight.controller.abort();
	}
	const controller = new AbortController();
	const generation = ++requestSequence;
	inFlight = { key, detail, controller, generation };
	lastError = null;
	setBusy(true);

	fetchTracks({
		world: world.dimension,
		minX: bounds.minX,
		minZ: bounds.minZ,
		maxX: bounds.maxX,
		maxZ: bounds.maxZ,
		detail,
		mode,
		signal: controller.signal
	}).then(payload => {
		if (generation !== requestSequence) {
			return;
		}
		inFlight = null;
		const plan = planResponseUse(payload, detail);
		if (plan === 'retry-coarser' && detail > 0 && !options.retried) {
			setBusy(false);
			const nextDetail = detail - 1;
			const nextKey = boundsKey(bounds, world.dimension, mode, nextDetail);
			request(bounds, nextDetail, nextKey, { retried: true });
			return;
		}
		if (plan === 'trunk') {
			// Deliberately do not build Path2D: a dense viewport costs more memory than it adds value.
			// Cache the negative answer too, otherwise the redraw triggered below would immediately
			// ask for the same dense bbox again and loop every debounce interval.
			const entry = emptyEntry(key, bounds, detail, payload, true);
			putCache(entry);
			current = entry;
			degraded = true;
			lastError = null;
			setBusy(false);
			queueRedraw();
			return;
		}
		const groups = buildRailBatches(payload);
		const bytes = estimatePayloadBytes(payload);
		if (groups.length === 0) {
			const entry = emptyEntry(key, bounds, detail, payload, false);
			putCache(entry);
			current = entry;
			degraded = false;
			lastError = null;
			setBusy(false);
			queueRedraw();
			return;
		}
		if (bytes > MAX_SINGLE_RESPONSE_BYTES) {
			const entry = emptyEntry(key, bounds, detail, payload, true);
			putCache(entry);
			current = entry;
			degraded = true;
			setBusy(false);
			queueRedraw();
			return;
		}
		const built = makePath2D(groups);
		if (built.usesPath2D) {
			for (const group of built.groups) {
				group.polylines = null;
			}
		}
		const entry = {
			key,
			bounds,
			detail,
			counts: payload.counts || {},
			groups: built.groups,
			usesPath2D: built.usesPath2D,
			bytes,
			fetchedAt: Date.now(),
			degraded: false,
			timeMs: Number(payload.timeMs) || 0
		};
		putCache(entry);
		current = entry;
		degraded = false;
		lastError = null;
		setBusy(false);
		queueRedraw();
	}).catch(error => {
		if (generation !== requestSequence) {
			return;
		}
		inFlight = null;
		setBusy(false);
		if (error instanceof DataFailure && error.kind === DataErrorKind.ABORTED) {
			return;
		}
		lastError = error;
		if (error instanceof DataFailure && error.kind === DataErrorKind.AUTH) {
			if (callbacks.onAuthFailure) {
				callbacks.onAuthFailure(error);
			}
			return;
		}
		if (error instanceof DataFailure && error.kind === DataErrorKind.BUSY && !options.retried) {
			setTimeout(() => {
				if (isStillRelevant(bounds, detail)) {
					request(bounds, detail, key, { retried: true });
				}
			}, 300);
			return;
		}
		if (error instanceof DataFailure && error.kind === DataErrorKind.TIMEOUT) {
			if (detail > 0 && !options.retried) {
				setTimeout(() => {
					const nextDetail = 0;
					const nextKey = boundsKey(bounds, world.dimension, mode, nextDetail);
					if (isStillRelevant(bounds, nextDetail)) {
						request(bounds, nextDetail, nextKey, { retried: true });
					}
				}, 500);
				return;
			}
			degraded = true;
			notifyState();
			queueRedraw();
			return;
		}
		notifyState();
	});
}

function groupVisible(group, mapView) {
	if (!mapView) {
		return false;
	}
	const bounds = visibleWorldBounds(mapView, 0);
	return group.maxX >= bounds.minX && group.minX <= bounds.maxX && group.maxZ >= bounds.minZ && group.minZ <= bounds.maxZ;
}

/**
 * True when detailed track geometry should replace the route trunks. Keeping this decision in one
 * exported helper makes it testable and guarantees the two layers stay mutually exclusive: drawing
 * both looked like the same track rendered at two precision levels.
 */
export function hasRenderableTracks(detail, groups, mapView) {
	if (detail == null || !Array.isArray(groups) || groups.length === 0 || !mapView) {
		return false;
	}
	return groups.some(group => groupVisible(group, mapView));
}

function drawTrunks(context, scale, light, settings) {
	if (!routeTrunks || routeTrunks.length === 0) {
		return;
	}
	if (!trunkPaths) {
		trunkPaths = makeTrunkPaths(routeTrunks);
	}
	const monochrome = !settings.colors;
	const monochromeColor = light ? '#1c1c1c' : '#ffffff';
	context.lineCap = 'round';
	context.lineJoin = 'round';
	context.lineWidth = (2 * settings.thickness) / scale;
	context.setLineDash([]);
	for (const trunk of trunkPaths) {
		context.strokeStyle = monochrome ? monochromeColor : (trunk.color ? argbToRgba(trunk.color) : FALLBACK_COLOR);
		context.globalAlpha = light ? 0.72 : 0.82;
		if (trunk.path) {
			context.stroke(trunk.path);
		} else if (Array.isArray(trunk.points) && trunk.points.length > 1) {
			context.beginPath();
			context.moveTo(trunk.points[0].x, trunk.points[0].z);
			for (let i = 1; i < trunk.points.length; i++) {
				context.lineTo(trunk.points[i].x, trunk.points[i].z);
			}
			context.stroke();
		}
	}
	context.globalAlpha = 1;
}

function drawGroups(context, scale, light, mapView, settings) {
	if (!current || !Array.isArray(current.groups)) {
		return;
	}
	const monochrome = !settings.colors;
	const monochromeColor = light ? '#1c1c1c' : '#ffffff';
	context.lineCap = 'round';
	context.lineJoin = 'round';
	let directDrawn = 0;
	for (const group of current.groups) {
		if (directDrawn >= DIRECT_DRAW_MAX_SEGMENTS) {
			break;
		}
		if (!groupVisible(group, mapView)) {
			continue;
		}
		const platform = group.platform;
		const width = platform ? 3 : 1.2;
		context.lineWidth = (width * settings.thickness) / scale;
		context.strokeStyle = monochrome ? monochromeColor : argbToRgba(group.color);
		context.globalAlpha = platform ? (light ? 0.9 : 0.95) : (light ? 0.62 : 0.55);
		if (group.mode === 'CABLE_CAR') {
			context.setLineDash([4 / scale, 3 / scale]);
		} else if (group.mode === 'AIRPLANE') {
			context.setLineDash([1.5 / scale, 3 / scale]);
		} else {
			context.setLineDash([]);
		}
		if (group.path) {
			context.stroke(group.path);
		} else if (Array.isArray(group.polylines)) {
			for (const flat of group.polylines) {
				if (directDrawn >= DIRECT_DRAW_MAX_SEGMENTS) {
					break;
				}
				context.beginPath();
				context.moveTo(flat[0], flat[1]);
				for (let i = 2; i < flat.length; i += 2) {
					context.lineTo(flat[i], flat[i + 1]);
				}
				context.stroke();
				directDrawn++;
			}
		}
	}
	context.setLineDash([]);
	context.globalAlpha = 1;
}

/** Draws both layers; the caller owns the canvas state and the DPR transform. */
export function draw(context, mapView, mapTheme) {
	if (!initialized || !enabled || !available || !context || !mapView) {
		return;
	}
	if (!(mapView.width > 0) || !(mapView.height > 0) || !(mapView.scale > 0)) {
		return;
	}
	const scale = mapView.scale;
	const light = Boolean(mapTheme && mapTheme.light);
	context.save();
	context.translate(mapView.width / 2, mapView.height / 2);
	context.scale(scale, scale);
	context.translate(-mapView.centerX, -mapView.centerZ);
	// Route trunks are the fallback for "no detailed track data for this viewport". If a drawable
	// detailed response overlaps the viewport, drawing both would show the same network twice at
	// two levels of precision (and cost two stroke passes), so the two layers are mutually exclusive.
	const detail = detailForScale(scale, overlaySettings.minScale);
	const hasVisibleTracks = hasRenderableTracks(detail, current ? current.groups : null, mapView);
	if (hasVisibleTracks) {
		lastLayer = 'tracks';
		drawGroups(context, scale, light, mapView, overlaySettings);
	} else {
		lastLayer = 'trunks';
		drawTrunks(context, scale, light, overlaySettings);
	}
	context.restore();
}

export function onViewChange(nextView) {
	view = nextView ? {
		centerX: nextView.centerX,
		centerZ: nextView.centerZ,
		scale: nextView.scale,
		width: nextView.width,
		height: nextView.height
	} : null;
	schedule();
}

export function setWorld(nextWorld) {
	if (world === nextWorld) {
		return;
	}
	world = nextWorld || null;
	routeTrunks = world ? buildRouteTrunks(world, mode) : null;
	trunkPaths = null;
	clearCache();
	abortInFlight();
	schedule();
}

export function setMode(nextMode) {
	if (mode === nextMode) {
		return;
	}
	mode = nextMode || 'all';
	routeTrunks = world ? buildRouteTrunks(world, mode) : null;
	trunkPaths = null;
	clearCache();
	abortInFlight();
	schedule();
}

export function setAvailable(nextAvailable) {
	const next = Boolean(nextAvailable);
	if (available === next) {
		return;
	}
	available = next;
	if (!available) {
		abortInFlight();
		current = null;
		degraded = false;
		setBusy(false);
	}
	notifyState();
	if (available) {
		schedule();
	}
}

export function setEnabled(nextEnabled) {
	setOverlaySetting('enabled', Boolean(nextEnabled));
	syncOverlaySettings('enabled');
}

export function toggleEnabled() {
	setEnabled(!enabled);
	return enabled;
}

export function isEnabled() {
	return enabled;
}

export function isAvailable() {
	return available;
}

export function invalidate() {
	abortInFlight();
	clearCache();
	notifyState();
	schedule();
}

function syncOverlaySettings(key) {
	const next = getOverlaySettings();
	const wasEnabled = enabled;
	overlaySettings = next;
	enabled = next.enabled;
	if (!enabled) {
		abortInFlight();
		setBusy(false);
	} else if (key === 'enabled' || key === 'minScale' || !wasEnabled) {
		schedule();
	}
	notifyState();
	queueRedraw();
}

function onOverlaySettingsChange(event) {
	syncOverlaySettings(event && event.detail ? event.detail.key : null);
}

export function init(options) {
	callbacks = options || {};
	initialized = true;
	overlaySettings = getOverlaySettings();
	enabled = overlaySettings.enabled;
	if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
		window.addEventListener(OVERLAY_EVENT, onOverlaySettingsChange);
	}
	notifyState();
}

export function dispose() {
	initialized = false;
	abortInFlight();
	if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
		window.removeEventListener(OVERLAY_EVENT, onOverlaySettingsChange);
	}
	if (debounceTimer != null) {
		clearTimeout(debounceTimer);
		debounceTimer = null;
	}
	cache = new Map();
	current = null;
}

/** Small surface for the dev preview / console diagnostics. */
export function getDebugState() {
	return {
		enabled,
		available,
		busy,
		degraded,
		mode,
		settings: { ...overlaySettings },
		detail: view ? detailForScale(view.scale, overlaySettings.minScale) : null,
		cacheEntries: cache.size,
		cacheBytes: cacheByteTotal(),
		trunks: routeTrunks ? routeTrunks.length : 0,
		renderedGroups: current ? current.groups.length : 0,
		timeMs: current ? current.timeMs : null,
		lastLayer,
		lastError: lastError ? lastError.kind || String(lastError) : null
	};
}
