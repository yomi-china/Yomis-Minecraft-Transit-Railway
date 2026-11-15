/**
 * One source of truth for the track overlay preferences.
 *
 * The old build stored only an on/off flag under STORAGE_KEY. That value is still honoured so an
 * existing browser keeps its choice; the new keys are separate so a malformed value cannot turn
 * every setting into its default. No DOM is touched here, which keeps the module usable from the
 * Node regression suites.
 */

export const OVERLAY_EVENT = 'mtr-overlaychange';
export const OVERLAY_DEFAULTS = Object.freeze({
	enabled: true,
	colors: true,
	minScale: 0.35,
	thickness: 1
});
export const MIN_SCALE_MIN = 0.01;
export const MIN_SCALE_MAX = 2;
export const THICKNESS_MIN = 0.5;
export const THICKNESS_MAX = 3;

const ENABLED_KEY = 'mtr-webdashboard-overlay-tracks';
const COLORS_KEY = 'mtr-webdashboard-track-colors';
const MIN_SCALE_KEY = 'mtr-webdashboard-track-min-scale';
const THICKNESS_KEY = 'mtr-webdashboard-track-thickness';

let current = load();

function readStorage(key) {
	try {
		return typeof localStorage === 'undefined' ? null : localStorage.getItem(key);
	} catch (error) {
		return null;
	}
}

function writeStorage(key, value) {
	try {
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem(key, value);
		}
	} catch (error) {
		// Private browsing / blocked storage: keep the in-memory value.
	}
}

function readBoolean(key, fallback) {
	const raw = readStorage(key);
	if (raw == null || raw === '') {
		return fallback;
	}
	return raw === '1' || raw === 'true';
}

function readNumber(key, fallback) {
	const raw = readStorage(key);
	if (raw == null || raw.trim() === '') {
		return fallback;
	}
	const value = Number(raw);
	return Number.isFinite(value) ? value : fallback;
}

function clamp(value, min, max) {
	return Math.min(max, Math.max(min, value));
}

function sanitize(raw) {
	const minScale = Number.isFinite(Number(raw.minScale)) ? clamp(Number(raw.minScale), MIN_SCALE_MIN, MIN_SCALE_MAX) : OVERLAY_DEFAULTS.minScale;
	const thickness = Number.isFinite(Number(raw.thickness)) ? clamp(Number(raw.thickness), THICKNESS_MIN, THICKNESS_MAX) : OVERLAY_DEFAULTS.thickness;
	return {
		enabled: typeof raw.enabled === 'boolean' ? raw.enabled : OVERLAY_DEFAULTS.enabled,
		colors: typeof raw.colors === 'boolean' ? raw.colors : OVERLAY_DEFAULTS.colors,
		minScale,
		thickness
	};
}

function load() {
	return sanitize({
		enabled: readBoolean(ENABLED_KEY, OVERLAY_DEFAULTS.enabled),
		colors: readBoolean(COLORS_KEY, OVERLAY_DEFAULTS.colors),
		minScale: readNumber(MIN_SCALE_KEY, OVERLAY_DEFAULTS.minScale),
		thickness: readNumber(THICKNESS_KEY, OVERLAY_DEFAULTS.thickness)
	});
}

export function getOverlaySettings() {
	return { ...current };
}

export function setOverlaySetting(key, value) {
	const next = sanitize({ ...current, [key]: value });
	if (next.enabled === current.enabled && next.colors === current.colors
		&& next.minScale === current.minScale && next.thickness === current.thickness) {
		return getOverlaySettings();
	}
	current = next;
	writeStorage(ENABLED_KEY, current.enabled ? '1' : '0');
	writeStorage(COLORS_KEY, current.colors ? '1' : '0');
	writeStorage(MIN_SCALE_KEY, String(current.minScale));
	writeStorage(THICKNESS_KEY, String(current.thickness));
	if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof CustomEvent === 'function') {
		window.dispatchEvent(new CustomEvent(OVERLAY_EVENT, { detail: { key, settings: { ...current } } }));
	}
	return getOverlaySettings();
}
