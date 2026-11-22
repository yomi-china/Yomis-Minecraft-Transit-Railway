/**
 * Theme controller for the signed-in dashboard, settings dialog and welcome/home layer.
 *
 * Public API (also exposed as window.mtrTheme):
 *   setMode('dark' | 'light')
 *   setAccent('#90caf9' | '<preset id>')
 *   get()
 *   reset()
 *
 * Preference is persisted in localStorage (not a cookie) under STORAGE_KEY.
 * Theme changes are applied to <body data-theme> and CSS custom properties, then announced via
 * THEME_EVENT so canvas renderers can repaint with the same palette.
 */

export const THEME_EVENT = 'mtr-themechange';
export const STORAGE_KEY = 'mtr-webdashboard-theme';
export const DEFAULT_MODE = 'dark';
export const DEFAULT_ACCENT_ID = 'sea-mist';

export const ACCENTS = [
	{ id: 'sea-mist', color: '#90caf9', labelKey: 'accentSeaMist' },
	{ id: 'sunset-orange', color: '#f0a36b', labelKey: 'accentSunsetOrange' },
	{ id: 'lilac', color: '#c3a6e8', labelKey: 'accentLilac' },
	{ id: 'meteor-gray', color: '#9a9da4', labelKey: 'accentMeteorGray' },
	{ id: 'moon-white', color: '#e8e8e8', labelKey: 'accentMoonWhite' },
	{ id: 'mint', color: '#a8d8cc', labelKey: 'accentMint' },
	{ id: 'amber-red', color: '#d77a6b', labelKey: 'accentAmberRed' },
	{ id: 'lime', color: '#c9e64e', labelKey: 'accentLime' },
	{ id: 'peach', color: '#f6b6a8', labelKey: 'accentPeach' },
	{ id: 'plum', color: '#6a3a4e', labelKey: 'accentPlum' },
	{ id: 'lake-blue', color: '#62c7d6', labelKey: 'accentLakeBlue' },
	{ id: 'milk-apricot', color: '#f5eee7', labelKey: 'accentMilkApricot' }
];

const MODES = new Set(['dark', 'light']);
const DEFAULT_ACCENT = ACCENTS[0];

let current = loadState();
let transitionTimer = 0;

function bodyElement() {
	return typeof document === 'undefined' ? null : document.body;
}

function parseHex(value) {
	if (typeof value !== 'string') {
		return null;
	}
	let hex = value.trim().replace(/^#/, '');
	if (hex.length === 3) {
		hex = hex.split('').map(ch => ch + ch).join('');
	}
	if (!/^[0-9a-f]{6}$/i.test(hex)) {
		return null;
	}
	return {
		hex: '#' + hex.toLowerCase(),
		r: parseInt(hex.slice(0, 2), 16),
		g: parseInt(hex.slice(2, 4), 16),
		b: parseInt(hex.slice(4, 6), 16)
	};
}

function toHex({ r, g, b }) {
	return '#' + [r, g, b].map(value => Math.round(Math.max(0, Math.min(255, value))).toString(16).padStart(2, '0')).join('');
}

function luminance({ r, g, b }) {
	const channels = [r, g, b].map(value => {
		const c = value / 255;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	});
	return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

function contrast(a, b) {
	const la = luminance(a);
	const lb = luminance(b);
	return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

function mix(a, b, amount) {
	return {
		r: a.r + (b.r - a.r) * amount,
		g: a.g + (b.g - a.g) * amount,
		b: a.b + (b.b - a.b) * amount
	};
}

function readableColor(color, background, towards, target = 4.5) {
	const bg = parseHex(background);
	const targetColor = parseHex(towards);
	if (!color || !bg || !targetColor) {
		return color ? color.hex : towards;
	}
	if (contrast(color, bg) >= target) {
		return color.hex;
	}
	let result = color;
	for (let step = 1; step <= 20; step++) {
		result = mix(color, targetColor, step / 20);
		if (contrast(result, bg) >= target) {
			return toHex(result);
		}
	}
	return towards;
}

function bestInk(color) {
	const white = parseHex('#ffffff');
	const dark = parseHex('#07131f');
	if (!color) {
		return '#07131f';
	}
	return contrast(color, white) >= contrast(color, dark) ? '#ffffff' : '#07131f';
}

function accentById(id) {
	return ACCENTS.find(accent => accent.id === id) || null;
}

function accentByColor(color) {
	const parsed = parseHex(color);
	if (!parsed) {
		return null;
	}
	return ACCENTS.find(accent => parseHex(accent.color).hex === parsed.hex) || null;
}

function resolveAccent(value) {
	if (value === undefined || value === null || value === '') {
		return DEFAULT_ACCENT;
	}
	const preset = accentById(value) || accentByColor(value);
	if (preset) {
		return preset;
	}
	const parsed = parseHex(value);
	return parsed ? { id: null, color: parsed.hex, labelKey: null } : DEFAULT_ACCENT;
}

function loadState() {
	const fallback = { mode: DEFAULT_MODE, accentId: DEFAULT_ACCENT_ID };
	if (typeof localStorage === 'undefined') {
		return fallback;
	}
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) {
			return fallback;
		}
		const parsed = JSON.parse(raw);
		const mode = MODES.has(parsed.mode) ? parsed.mode : DEFAULT_MODE;
		const accent = resolveAccent(parsed.accentId || parsed.accent);
		return { mode, accentId: accent.id || DEFAULT_ACCENT_ID, customAccent: accent.id ? null : accent.color };
	} catch (error) {
		return fallback;
	}
}

function currentAccent() {
	if (current.customAccent) {
		return resolveAccent(current.customAccent);
	}
	return accentById(current.accentId) || DEFAULT_ACCENT;
}

function themeValues() {
	const accent = currentAccent();
	const parsed = parseHex(accent.color) || parseHex(DEFAULT_ACCENT.color);
	const isLight = current.mode === 'light';
	const background = isLight ? '#f2f4f6' : '#121212';
	const accentText = readableColor(parsed, background, isLight ? '#07131f' : '#ffffff', 4.5);
	const backgroundRgb = parseHex(background);
	const needsActionShade = backgroundRgb && contrast(parsed, backgroundRgb) < (isLight ? 3 : 2.4);
	const action = needsActionShade ? accentText : parsed.hex;
	const actionParsed = parseHex(action) || parsed;
	return {
		mode: current.mode,
		accentId: accent.id,
		accentNameKey: accent.labelKey,
		accent: parsed.hex,
		accentText,
		accentAction: action,
		accentInk: bestInk(actionParsed)
	};
}

function persist() {
	if (typeof localStorage === 'undefined') {
		return;
	}
	try {
		const values = themeValues();
		localStorage.setItem(STORAGE_KEY, JSON.stringify({
			mode: values.mode,
			accentId: values.accentId,
			accent: values.accent
		}));
	} catch (error) {
		// Storage may be unavailable in a locked-down WebView; theme still applies for this page.
	}
}

function applyTheme({ animate = false, persist: save = false } = {}) {
	const body = bodyElement();
	if (!body) {
		return themeValues();
	}
	const values = themeValues();
	body.dataset.theme = values.mode;
	if (document.documentElement) {
		document.documentElement.dataset.theme = values.mode;
	}
	body.style.setProperty('--ui-accent', values.accent);
	body.style.setProperty('--ui-accent-text', values.accentText);
	body.style.setProperty('--ui-accent-action', values.accentAction);
	body.style.setProperty('--ui-accent-ink', values.accentInk);
	body.style.setProperty('--welcome-primary', values.accentText);

	if (animate) {
		body.classList.add('theme-transition');
		clearTimeout(transitionTimer);
		transitionTimer = window.setTimeout(() => body.classList.remove('theme-transition'), 380);
	}
	if (save) {
		persist();
	}

	if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
		window.dispatchEvent(new CustomEvent(THEME_EVENT, { detail: values }));
	}
	return values;
}

/** @returns {boolean} true when the mode name is known and was applied. */
export function setMode(mode) {
	if (!MODES.has(mode)) {
		return false;
	}
	current = { ...current, mode };
	applyTheme({ animate: true, persist: true });
	return true;
}

/** @param {string} value preset id or CSS hex colour. */
export function setAccent(value) {
	const accent = resolveAccent(value);
	current = { ...current, accentId: accent.id || DEFAULT_ACCENT_ID, customAccent: accent.id ? null : accent.color };
	applyTheme({ animate: true, persist: true });
	return true;
}

/** @returns {{mode: string, accent: string, accentId: string, accentText: string, accentInk: string}} */
export function getTheme() {
	return themeValues();
}

/** Restores the default dark mode and 海雾蓝 accent. */
export function resetTheme() {
	current = { mode: DEFAULT_MODE, accentId: DEFAULT_ACCENT_ID, customAccent: null };
	applyTheme({ animate: true, persist: true });
}

const api = {
	ACCENTS,
	setMode,
	setAccent,
	get: getTheme,
	reset: resetTheme
};

if (typeof window !== 'undefined') {
	window.mtrTheme = api;
}

if (typeof document !== 'undefined') {
	if (document.body) {
		applyTheme();
	} else {
		document.addEventListener('DOMContentLoaded', () => applyTheme(), { once: true });
	}
}

export default api;
