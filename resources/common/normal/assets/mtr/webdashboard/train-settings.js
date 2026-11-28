/**
 * Train overlay preferences, kept in one storage key so a malformed value cannot leak into the
 * other dashboard settings. No DOM access, matching the track overlay module.
 */

export const TRAIN_EVENT = 'mtr-trainchange';
export const TRAIN_DEFAULTS = Object.freeze({
    enabled: false,
    consist: 'consist',
    rateHz: 5,
    max: 16,
    minScale: 0.6,
    route: 'all',
    depot: 'all'
});
export const TRAIN_RATE_MIN = 1;
export const TRAIN_RATE_MAX = 10;
export const TRAIN_MAX_MIN = 1;
export const TRAIN_MAX_MAX = 256;
export const TRAIN_MIN_SCALE_MIN = 0.1;
export const TRAIN_MIN_SCALE_MAX = 4;

const STORAGE_KEY = 'mtr-webdashboard-trains';

let current = load();

function readStorage() {
    try {
        return typeof localStorage === 'undefined' ? null : localStorage.getItem(STORAGE_KEY);
    } catch (error) {
        return null;
    }
}

function writeStorage(value) {
    try {
        if (typeof localStorage !== 'undefined') {
            localStorage.setItem(STORAGE_KEY, value);
        }
    } catch (error) {
        // Private browsing / blocked storage: keep the in-memory value.
    }
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}

function parseId(value) {
    const text = String(value == null ? 'all' : value);
    return text === 'all' || text === '0' || text === '' ? 'all' : text;
}

/**
 * Older builds stored the polling interval in seconds. Map it onto the new push rate so an existing
 * preference keeps roughly the same cadence (1 s was the old default, which the rate-based UI shows
 * as the default 5 Hz).
 */
function rateFromLegacyInterval(value) {
    const interval = Number(value);
    return Number.isFinite(interval) && interval > 0 ? 5 / interval : TRAIN_DEFAULTS.rateHz;
}

function sanitize(raw) {
    const requestedRate = raw.rateHz != null ? Number(raw.rateHz) : rateFromLegacyInterval(raw.interval);
    const max = Math.round(Number(raw.max));
    const minScale = Number(raw.minScale);
    return {
        enabled: typeof raw.enabled === 'boolean' ? raw.enabled : TRAIN_DEFAULTS.enabled,
        consist: raw.consist === 'block' ? 'block' : 'consist',
        rateHz: Number.isFinite(requestedRate)
            ? clamp(Math.round(requestedRate), TRAIN_RATE_MIN, TRAIN_RATE_MAX)
            : TRAIN_DEFAULTS.rateHz,
        max: Number.isFinite(max) ? clamp(max, TRAIN_MAX_MIN, TRAIN_MAX_MAX) : TRAIN_DEFAULTS.max,
        minScale: Number.isFinite(minScale) ? clamp(minScale, TRAIN_MIN_SCALE_MIN, TRAIN_MIN_SCALE_MAX) : TRAIN_DEFAULTS.minScale,
        route: parseId(raw.route),
        depot: parseId(raw.depot)
    };
}

function load() {
    try {
        const raw = readStorage();
        return sanitize(raw ? JSON.parse(raw) : {});
    } catch (error) {
        return { ...TRAIN_DEFAULTS };
    }
}

export function getTrainSettings() {
    return { ...current };
}

export function setTrainSetting(key, value) {
    const next = sanitize({ ...current, [key]: value });
    if (Object.keys(next).every(item => next[item] === current[item])) {
        return getTrainSettings();
    }
    current = next;
    writeStorage(JSON.stringify(current));
    if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof CustomEvent === 'function') {
        window.dispatchEvent(new CustomEvent(TRAIN_EVENT, { detail: { key, settings: { ...current } } }));
    }
    return getTrainSettings();
}
