import {
	formatHex,
	hsvToRgb,
	hueCss,
	parseHex,
	rgbCss,
	rgbToHsv,
	wrapHue
} from './color.js?v=20';
import { t } from './i18n.js?v=20';

const CURSOR_SIZE = 12;

export function createColorPicker(options) {
	const initial = Number.isFinite(options.color) ? options.color : 0;
	const onChange = options.onChange;

	let hue = 0;
	let saturation = 0;
	let value = 0;
	let color = initial;
	adoptColor(initial);

	let hexFocused = false;
	let hexInvalid = false;

	let frame = null;
	let pending = false;

	const root = element('div', 'color-picker');

	const panel = element('div', 'color-picker__panel');
	panel.tabIndex = 0;
	panel.setAttribute('role', 'slider');
	panel.setAttribute('aria-label', t('colorSaturationValue'));

	const hueLayer = element('div', 'color-picker__hue-layer');
	const whiteLayer = element('div', 'color-picker__white-layer');
	const blackLayer = element('div', 'color-picker__black-layer');
	const panelCursor = element('div', 'color-picker__cursor');
	panel.append(hueLayer, whiteLayer, blackLayer, panelCursor);

	const hueBar = element('div', 'color-picker__hue-bar');
	hueBar.tabIndex = 0;
	hueBar.setAttribute('role', 'slider');
	hueBar.setAttribute('aria-label', t('colorHue'));

	const hueCursor = element('div', 'color-picker__hue-cursor');
	hueBar.appendChild(hueCursor);

	const top = element('div', 'color-picker__top');
	top.append(panel, hueBar);

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

	bindDrag(panel, (rect, event) => {
		const x = clamp01((event.clientX - rect.left) / rect.width);
		const y = clamp01((event.clientY - rect.top) / rect.height);
		applyHsv(hue, x, 1 - y);
	});
	bindDrag(hueBar, (rect, event) => {
		const ratio = clamp01((event.clientY - rect.top) / rect.height);
		setHue(ratio * 359);
	});

	panel.addEventListener('keydown', event => {
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
		if (hexInvalid) {
			hexInvalid = false;
			hexInput.value = formatHex(color);
			setFieldError(false);
		}
	});
	hexInput.addEventListener('input', () => {
		const parsed = parseHex(hexInput.value);
		if (parsed == null) {
			hexInvalid = true;
			setFieldError(true);
			return;
		}
		hexInvalid = false;
		setFieldError(false);
		applyColor(parsed);
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
		/** Replaces the colour without notifying. */
		setColor: value => applyColor(value),
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

	function element(tag, className) {
		const node = document.createElement(tag);
		node.className = className;
		return node;
	}

	function bindDrag(surface, apply) {
		surface.addEventListener('pointerdown', event => {
			if (event.button !== 0) {
				return;
			}
			event.preventDefault();
			surface.setPointerCapture(event.pointerId);
			apply(surface.getBoundingClientRect(), event);
			notifyNow();
		});
		surface.addEventListener('pointermove', event => {
			if (!surface.hasPointerCapture(event.pointerId)) {
				return;
			}
			event.preventDefault();
			apply(surface.getBoundingClientRect(), event);
			schedule();
		});
		const end = event => {
			if (surface.hasPointerCapture(event.pointerId)) {
				surface.releasePointerCapture(event.pointerId);
			}
			notifyNow();
		};
		surface.addEventListener('pointerup', end);
		surface.addEventListener('pointercancel', end);
	}

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

	function notifyNow() {
		pending = false;
		renderAll();
		if (onChange) {
			onChange(color);
		}
	}

	function adoptColor(next) {
		const hsv = rgbToHsv(next);
		if (hsv.h != null) {
			hue = hsv.h;
		}
		saturation = hsv.s;
		value = hsv.v;
	}

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
		hue = wrapHue(nextHue);
		applyTriplet();
	}

	function applyColor(next) {
		color = next;
		adoptColor(next);
		renderAll();
	}

	function renderAll() {
		hueLayer.style.backgroundColor = hueCss(hue);
		preview.style.backgroundColor = rgbCss(color);

		panelCursor.style.left = (saturation * 100) + '%';
		panelCursor.style.top = ((1 - value) * 100) + '%';
		hueCursor.style.top = (hue / 359 * 100) + '%';

		panel.setAttribute('aria-valuetext', Math.round(saturation * 100) + '%, ' + Math.round(value * 100) + '%');
		hueBar.setAttribute('aria-valuenow', String(Math.round(hue)));
		hueBar.setAttribute('aria-valuemin', '0');
		hueBar.setAttribute('aria-valuemax', '359');

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
