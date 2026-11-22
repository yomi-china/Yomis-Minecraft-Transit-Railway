/** The largest colour, 24 bits of RGB with no alpha. */
export const MAX_COLOR = 0xFFFFFF;

/** Hue is an integer degree in [0, 360). 360 is the same colour as 0 but a different CSS string. */
export const HUE_MAX = 360;

/**
 * @returns {{r: number, g: number, b: number}} each in 0..255.
 */
export function toComponents(color) {
	const value = Math.max(0, Math.min(MAX_COLOR, Math.round(Number(color) || 0)));
	return { r: (value >> 16) & 0xFF, g: (value >> 8) & 0xFF, b: value & 0xFF };
}

/** @returns {number} the 24-bit colour built from channels, each clamped to 0..255. */
export function fromComponents(r, g, b) {
	const clamp = channel => Math.max(0, Math.min(255, Math.round(Number(channel) || 0)));
	return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
}

/**
 * <b>{@code h} is null for greys.</b> <b>{@code h} is continuous, not an integer degree.</b>
 *
 * @returns {{h: number|null, s: number, v: number}} h in 0..360 or null, s and v in 0..1.
 */
export function rgbToHsv(color) {
	const { r, g, b } = toComponents(color);
	const max = Math.max(r, g, b);
	const min = Math.min(r, g, b);
	const delta = max - min;

	const v = max / 255;
	const s = max === 0 ? 0 : delta / max;

	let h = null;
	if (delta !== 0) {
		if (max === r) {
			h = 60 * (((g - b) / delta) % 6);
		} else if (max === g) {
			h = 60 * ((b - r) / delta + 2);
		} else {
			h = 60 * ((r - g) / delta + 4);
		}
	}

	return { h: h == null ? null : wrapHue(h), s, v };
}

/**
 * @param {number|null} h hue in degrees, continuous, or null for a grey.
 * @param {number} s saturation, 0..1.
 * @param {number} v value, 0..1.
 * @returns {number} the 24-bit colour.
 */
export function hsvToRgb(h, s, v) {
	const saturation = clamp01(s);
	const value = clamp01(v);
	const hue = wrapHue(h == null ? 0 : h) / 60;

	const c = value * saturation;
	const x = c * (1 - Math.abs((hue % 2) - 1));
	const m = value - c;

	let r;
	let g;
	let b;
	if (hue < 1) {
		r = c; g = x; b = 0;
	} else if (hue < 2) {
		r = x; g = c; b = 0;
	} else if (hue < 3) {
		r = 0; g = c; b = x;
	} else if (hue < 4) {
		r = 0; g = x; b = c;
	} else if (hue < 5) {
		r = x; g = 0; b = c;
	} else {
		r = c; g = 0; b = x;
	}

	return fromComponents(
		Math.round((r + m) * 255),
		Math.round((g + m) * 255),
		Math.round((b + m) * 255)
	);
}

export function normaliseHue(hue) {
	return Math.round(wrapHue(hue));
}

/** Wraps a hue into [0, 360) as a continuous value, for arithmetic. */
export function wrapHue(hue) {
	if (!Number.isFinite(hue)) {
		return 0;
	}
	return ((hue % HUE_MAX) + HUE_MAX) % HUE_MAX;
}

/** @returns {string} the colour as `rgb(r, g, b)`, for a style assignment. */
export function rgbCss(color) {
	const { r, g, b } = toComponents(color);
	return 'rgb(' + r + ', ' + g + ', ' + b + ')';
}

/** @returns {string} the fully saturated colour of a hue, as a CSS colour. */
export function hueCss(hue) {
	return 'hsl(' + normaliseHue(hue) + ', 100%, 50%)';
}

/** @returns {string} the colour as `#rrggbb`, lowercase. */
export function formatHex(color) {
	const value = Math.max(0, Math.min(MAX_COLOR, Math.round(Number(color) || 0)));
	return '#' + value.toString(16).padStart(6, '0');
}

/**
 * @returns {number|null} the colour, or null when the text is not one to six hex digits.
 */
export function parseHex(text) {
	if (typeof text !== 'string') {
		return null;
	}
	const trimmed = text.trim().replace(/^#/, '');
	if (!/^[0-9a-fA-F]{1,6}$/.test(trimmed)) {
		return null;
	}
	return parseInt(trimmed, 16);
}

export function clamp01(value) {
	if (!Number.isFinite(value)) {
		return 0;
	}
	return Math.max(0, Math.min(1, value));
}
