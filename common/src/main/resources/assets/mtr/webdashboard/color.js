/*
 * Colour maths for the picker. Pure functions: no DOM, no canvas, no state.
 *
 * HSV, not HSL. The picker's vertical axis is *value* - the brightest channel - so a fully saturated colour
 * sits at the top right and white is only at the very top. The two spaces are easy to confuse and produce a
 * picker that looks plausible and is wrong, so test-color.mjs pins the difference down explicitly.
 *
 * Split out from the picker for the usual reason: this is the part that is easy to get subtly wrong and
 * impossible to check by eye.
 */

/** The largest colour, 24 bits of RGB with no alpha. */
export const MAX_COLOR = 0xFFFFFF;

/** Hue is an integer degree in [0, 360). 360 is the same colour as 0 but a different CSS string. */
export const HUE_MAX = 360;

/**
 * Splits a 24-bit colour into channels.
 *
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
 * Converts a colour to HSV.
 *
 * <b>{@code h} is null for greys, and that is the important part.</b> Black, white and every grey have no
 * hue - the formula divides by zero - and returning 0 instead would mean that dragging the value slider to
 * the bottom and back up turns the colour red. The caller is expected to hold on to the last real hue and
 * reuse it; see the picker, which does exactly that.
 *
 * <b>{@code h} is continuous, not an integer degree.</b> Rounding it here was a real bug: a colour whose
 * exact hue is 240.5 became 241, and converting back moved the red channel by 1 - enough to fail an exact
 * round trip, and enough to make a colour drift by a step every time it was loaded into the picker and saved
 * again. The CSS string is the only place a hue needs to be whole, and {@link hueCss} rounds it there.
 *
 * @returns {{h: number|null, s: number, v: number}} h in 0..360 or null, s and v in 0..1.
 */
export function rgbToHsv(color) {
	const { r, g, b } = toComponents(color);
	const max = Math.max(r, g, b);
	const min = Math.min(r, g, b);
	const delta = max - min;

	// Value is the brightest channel, which is what separates HSV from HSL: in HSL a fully saturated colour
	// has lightness 0.5, here it has value 1.
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
 * Converts HSV to a colour.
 *
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

	// Rounded AFTER scaling by 255, not before. Rounding the fractional parts first collapses the whole
	// lower half of the value axis: hsvToRgb(0, 1, 0.5) has c = 0.5, and Math.round(0.5 + 0) * 255 is 255,
	// so half-bright red came back as full red. Every colour then round tripped to a vertex, which is what
	// the round-trip assertion caught.
	return fromComponents(
		Math.round((r + m) * 255),
		Math.round((g + m) * 255),
		Math.round((b + m) * 255)
	);
}

/**
 * Wraps a hue into [0, 360) <b>as a whole number of degrees</b>.
 *
 * 360 and 0 are the same colour but different CSS strings, and a hue that drifted to 360 would make the
 * picker's background-colour comparison never equal, so it would rewrite the style on every frame.
 *
 * Rounds, so this is for display and CSS only - never for carrying a hue between conversions. Use
 * {@link wrapHue} for that, or the colour will drift by a step each time it is round tripped.
 */
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

/**
 * The fully saturated colour of a hue, as a CSS colour.
 *
 * This is the base layer of the picker's square: white is painted in horizontally and black vertically, and
 * what shows through is the pure hue.
 */
export function hueCss(hue) {
	return 'hsl(' + normaliseHue(hue) + ', 100%, 50%)';
}

/** @returns {string} the colour as `#rrggbb`, lowercase. */
export function formatHex(color) {
	const value = Math.max(0, Math.min(MAX_COLOR, Math.round(Number(color) || 0)));
	return '#' + value.toString(16).padStart(6, '0');
}

/**
 * Parses a hex colour.
 *
 * Tolerant of a missing `#` and of upper case, because the field shows the `#` and a visitor retyping a
 * colour from elsewhere should not have to match the format exactly. Deliberately intolerant of everything
 * else: a half-typed value must be rejected so the caller can leave the picker where it is rather than
 * jumping it to a wrong colour.
 *
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

/**
 * The closest colour to a hue/saturation/value triplet that the picker can actually show.
 *
 * Used when the caller has a hue from the picker and needs to know whether the value it just produced is
 * still that hue - greys and black are not, and the picker must not snap its cursor back to a corner while
 * the visitor is dragging through them.
 */
export function isChromatic(color) {
	return rgbToHsv(color).h != null;
}

export function clamp01(value) {
	if (!Number.isFinite(value)) {
		return 0;
	}
	return Math.max(0, Math.min(1, value));
}
