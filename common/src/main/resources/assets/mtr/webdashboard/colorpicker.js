import {
	formatHex,
	fromComponents,
	hsvToRgb,
	hueCss,
	parseHex,
	rgbCss,
	rgbToHsv,
	toComponents,
	wrapHue
} from './color.js?v=20';
import { t } from './i18n.js?v=20';

/*
 * A continuous HSV colour picker: a saturation/value square with a hue bar beside it, and one hex field.
 *
 * No canvas anywhere. The square is three stacked CSS backgrounds - the pure hue underneath, white faded in
 * from the left, black faded in from the bottom - and that composition is the SV plane exactly. So dragging
 * never repaints anything: the only style that changes is the position of two small cursors, and the hue
 * layer is only touched when the hue actually moves.
 *
 * The one piece of real state is `hue`. A grey has no hue at all (see color.js), so it is remembered here
 * and reused whenever the current colour has none - which is what stops a drag down to black and back up
 * from turning the colour red.
 */

/** Size of the two cursors, in pixels. Shared by the CSS and the centring arithmetic. */
const CURSOR_SIZE = 12;

export function createColorPicker(options) {
	const initial = Number.isFinite(options.color) ? options.color : 0;
	const onChange = options.onChange;

	// The picker carries its own hue, saturation and value rather than deriving them from the colour on every
	// frame, and that is not a style choice - deriving them was a real bug.
	//
	// A colour is lossy about how it was made. Black is (0, 0, 0) whatever hue and saturation produced it, so
	// reading the triplet back gives saturation 0: dragging the value down to the bottom therefore snapped the
	// cursor to the bottom-left corner and pinned it there, and dragging back up could only produce white
	// because the saturation was gone. Keeping the triplet means the gesture is reversible, which is what a
	// picker is for.
	let hue = 0;
	let saturation = 0;
	let value = 0;
	let color = initial;
	adoptColor(initial);

	// Whether the pointer is in the hex field. While it is, the field's text is left alone - writing to it
	// mid-edit would fight the visitor for the caret.
	let hexFocused = false;
	let hexInvalid = false;

	// Frames are coalesced: a high-rate pointer delivers several moves per frame and repainting each is waste.
	let frame = null;
	let pending = false;

	const root = element('div', 'color-picker');

	// ---- saturation / value square ----
	const panel = element('div', 'color-picker__panel');
	panel.tabIndex = 0;
	panel.setAttribute('role', 'slider');
	panel.setAttribute('aria-label', t('colorSaturationValue'));

	const hueLayer = element('div', 'color-picker__hue-layer');
	const whiteLayer = element('div', 'color-picker__white-layer');
	const blackLayer = element('div', 'color-picker__black-layer');
	const panelCursor = element('div', 'color-picker__cursor');
	panel.append(hueLayer, whiteLayer, blackLayer, panelCursor);

	// ---- hue bar ----
	const hueBar = element('div', 'color-picker__hue-bar');
	hueBar.tabIndex = 0;
	hueBar.setAttribute('role', 'slider');
	hueBar.setAttribute('aria-label', t('colorHue'));

	const hueCursor = element('div', 'color-picker__hue-cursor');
	hueBar.appendChild(hueCursor);

	const top = element('div', 'color-picker__top');
	top.append(panel, hueBar);

	// ---- hex field ----
	const bottom = element('div', 'color-picker__bottom');
	const preview = element('div', 'color-picker__preview');
	const hexInput = document.createElement('input');
	hexInput.type = 'text';
	hexInput.className = 'edit-field__input color-picker__hex';
	hexInput.spellcheck = false;
	hexInput.maxLength = 7;
	hexInput.autocomplete = 'off';
	hexInput.setAttribute('aria-label', t('fieldColor'));
	bottom.append(preview, hexInput);

	root.append(top, bottom);

	// ---- pointer handling, shared by both surfaces ----
	bindDrag(panel, (rect, event) => {
		const x = clamp01((event.clientX - rect.left) / rect.width);
		// Screen y grows downward and value grows upward, so the ratio is inverted.
		const y = clamp01((event.clientY - rect.top) / rect.height);
		applyHsv(hue, x, 1 - y);
	});
	bindDrag(hueBar, (rect, event) => {
		const ratio = clamp01((event.clientY - rect.top) / rect.height);
		setHue(ratio * 359);
	});

	panel.addEventListener('keydown', event => {
		// Arrow keys nudge saturation and value by 1% or, with shift, by 10%.
		const step = event.shiftKey ? 0.1 : 0.01;
		const hsv = rgbToHsv(color);
		if (event.key === 'ArrowLeft') {
			applyHsv(hue, clamp01(hsv.s - step), hsv.v);
		} else if (event.key === 'ArrowRight') {
			applyHsv(hue, clamp01(hsv.s + step), hsv.v);
		} else if (event.key === 'ArrowUp') {
			applyHsv(hue, hsv.s, clamp01(hsv.v + step));
		} else if (event.key === 'ArrowDown') {
			applyHsv(hue, hsv.s, clamp01(hsv.v - step));
		} else {
			return;
		}
		event.preventDefault();
	});

	hueBar.addEventListener('keydown', event => {
		const step = event.shiftKey ? 10 : 1;
		if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
			setHue(hue - step);
		} else if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
			setHue(hue + step);
		} else {
			return;
		}
		event.preventDefault();
	});

	hexInput.addEventListener('focus', () => {
		hexFocused = true;
	});
	hexInput.addEventListener('blur', () => {
		hexFocused = false;
		// A half-typed or wrong value is rolled back rather than left showing something that is not the
		// colour. The alternative is a field that disagrees with the swatch beside it.
		if (hexInvalid) {
			hexInvalid = false;
			hexInput.value = formatHex(color);
			setFieldError(false);
		}
	});
	hexInput.addEventListener('input', () => {
		const parsed = parseHex(hexInput.value);
		if (parsed == null) {
			// Deliberately does nothing to the picker. "3a7" is on its way to a colour and jumping the cursor
			// around on every keystroke makes the field unusable.
			hexInvalid = true;
			setFieldError(true);
			return;
		}
		hexInvalid = false;
		setFieldError(false);
		applyColor(parsed, false);
	});
	hexInput.addEventListener('keydown', event => {
		if (event.key === 'Enter') {
			event.preventDefault();
			hexInput.blur();
		}
	});

	renderAll();

	return {
		root,
		/** @returns {number} the current colour. */
		getColor: () => color,
		/** Replaces the colour without notifying, for when the caller is echoing back what it just received. */
		setColor: value => applyColor(value, false),
		/** @returns {boolean} whether the hex field currently holds something unparseable. */
		isHexInvalid: () => hexInvalid,
		/** Cancels any queued frame, for when the card closes. */
		dispose: () => {
			if (frame != null) {
				cancelAnimationFrame(frame);
				frame = null;
			}
		}
	};

	// ---- internals ----

	function element(tag, className) {
		const node = document.createElement(tag);
		node.className = className;
		return node;
	}

	/**
	 * Attaches pointer-capture dragging to a surface.
	 *
	 * Pointer capture rather than listeners on `document`: it keeps events coming when the pointer leaves the
	 * element or the window, and it is the only form that works for touch. `pointercancel` is handled as well
	 * as `pointerup`, because the browser can revoke the capture (a system gesture, for instance) and without
	 * it the drag would be left stuck on.
	 */
	function bindDrag(surface, apply) {
		surface.addEventListener('pointerdown', event => {
			if (event.button != null && event.button !== 0) {
				return;
			}
			event.preventDefault();
			surface.setPointerCapture(event.pointerId);
			apply(surface.getBoundingClientRect(), event);
			notifyNow();
		});
		surface.addEventListener('pointermove', event => {
			if (!surface.hasPointerCapture || !surface.hasPointerCapture(event.pointerId)) {
				return;
			}
			event.preventDefault();
			apply(surface.getBoundingClientRect(), event);
			schedule();
		});
		const end = event => {
			if (surface.hasPointerCapture && surface.hasPointerCapture(event.pointerId)) {
				surface.releasePointerCapture(event.pointerId);
			}
			// The heavier bookkeeping - dirty fields and the save button - is left until here rather than run
			// on every move.
			notifyNow();
		};
		surface.addEventListener('pointerup', end);
		surface.addEventListener('pointercancel', end);
	}

	/** Queues one update for the next frame, however many pointer events arrive before it. */
	function schedule() {
		pending = true;
		if (frame != null) {
			return;
		}
		frame = requestAnimationFrame(() => {
			frame = null;
			if (!pending) {
				return;
			}
			pending = false;
			renderAll();
		});
	}

	/** Reports the colour without waiting for a frame, for the end of a gesture. */
	function notifyNow() {
		pending = false;
		renderAll();
		if (onChange) {
			onChange(color);
		}
	}

	/**
	 * Takes the hue, saturation and value from a colour, for when the colour came from outside.
	 *
	 * The hue is only taken when the colour has one: a grey reports none, and overwriting the remembered hue
	 * with 0 would turn the next drag red.
	 */
	function adoptColor(next) {
		const hsv = rgbToHsv(next);
		if (hsv.h != null) {
			hue = hsv.h;
		}
		saturation = hsv.s;
		value = hsv.v;
	}

	/** Rebuilds the colour from the triplet and repaints. */
	function applyTriplet() {
		color = hsvToRgb(hue, saturation, value);
		renderAll();
	}

	function applyHsv(nextHue, nextSaturation, nextValue) {
		hue = wrapHue(nextHue);
		saturation = clamp01(nextSaturation);
		value = clamp01(nextValue);
		applyTriplet();
	}

	function setHue(nextHue) {
		// Only the hue changes. The saturation and value are kept as they are rather than read back from the
		// colour, which is what makes choosing a hue while the colour is black produce that hue at the current
		// brightness instead of white.
		hue = wrapHue(nextHue);
		applyTriplet();
	}

	function applyColor(next, notify) {
		color = next;
		adoptColor(next);
		renderAll();
		if (notify && onChange) {
			onChange(color);
		}
	}

	function renderAll() {
		hueLayer.style.backgroundColor = hueCss(hue);
		preview.style.backgroundColor = rgbCss(color);

		// From the triplet, not from the colour. Reading them back would collapse the cursor to the nearest
		// corner whenever the colour stops carrying them - at value 0 every colour is black.
		panelCursor.style.left = (saturation * 100) + '%';
		panelCursor.style.top = ((1 - value) * 100) + '%';
		hueCursor.style.top = (hue / 359 * 100) + '%';

		// Accessibility: the two surfaces report their own value, so a screen reader can announce a drag.
		panel.setAttribute('aria-valuetext', Math.round(saturation * 100) + '%, ' + Math.round(value * 100) + '%');
		hueBar.setAttribute('aria-valuenow', String(Math.round(hue)));
		hueBar.setAttribute('aria-valuemin', '0');
		hueBar.setAttribute('aria-valuemax', '359');

		// Never while the field has focus: the visitor is mid-edit and the caret is theirs.
		if (!hexFocused) {
			hexInput.value = formatHex(color);
		}
	}

	function setFieldError(invalid) {
		hexInput.dataset.invalid = invalid ? 'true' : 'false';
	}
}

function clamp01(value) {
	return Math.max(0, Math.min(1, value));
}
