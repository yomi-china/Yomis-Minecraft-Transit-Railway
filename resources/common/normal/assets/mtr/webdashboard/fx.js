/**
 * Pointer effects shared by every button-like control on the page.
 *
 * Ripple: one soft circle per press, started at the pointer, clipped to the control's own radius.
 * Magnetic: hold the control down and drag away; it follows the pointer up to 24px and springs back on
 * release. Hovering alone never moves it.
 *
 * Both are deliberately scoped to action controls (`.button`, `.icon-button`, `.row__edit`, `.welcome-help`):
 * tabs, chips, list rows and form fields keep their own behaviour. The select trigger is a field, not a button.
 *
 * The magnetic part uses the CSS `translate` property instead of `transform`, so it composes with the
 * existing `:active { transform: scale(...) }` instead of overwriting it.
 */

const SELECTOR = '.button, .icon-button, .row__edit, .welcome-help';
const MAGNET_REACH = 120;
const MAGNET_LIMIT = 10;
const MAGNET_EASE = 0.18;
const FRAME_LINGER = 260;

const reducedMotion = typeof window !== 'undefined'
	&& typeof window.matchMedia === 'function'
	&& window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const supportsTranslate = typeof document !== 'undefined'
	&& document.documentElement
	&& document.documentElement.style
	&& 'translate' in document.documentElement.style;

let pointerX = 0;
let pointerY = 0;
let lastMove = 0;
let frame = 0;
let pressed = null;
let pressPointerId = null;
const states = new Map();

if (typeof document !== 'undefined') {
	if (!reducedMotion && supportsTranslate) {
		document.addEventListener('pointermove', onPointerMove, { passive: true, capture: true });
		document.addEventListener('pointerup', onPointerEnd, true);
		document.addEventListener('pointercancel', onPointerEnd, true);
		if (document.documentElement && typeof document.documentElement.addEventListener === 'function') {
			document.documentElement.addEventListener('pointerleave', onPointerLeaveDocument);
		}
		if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
			window.addEventListener('blur', onWindowBlur);
		}
	}
	if (!reducedMotion) {
		// Capture, because the edit card stops pointerdown propagation on itself.
		document.addEventListener('pointerdown', onPointerDown, true);
	}
}

function onPointerMove(event) {
	if (event.pointerType === 'touch') {
		return;
	}
	pointerX = event.clientX;
	pointerY = event.clientY;
	lastMove = performance.now();
	if (pressed) {
		requestFrame();
	}
}

function onPointerDown(event) {
	if (event.button !== 0 || !event.target || typeof event.target.closest !== 'function') {
		return;
	}
	const button = event.target.closest(SELECTOR);
	if (!button || button.disabled) {
		return;
	}

	spawnRipple(button, event.clientX, event.clientY);

	if (event.pointerType === 'touch' || !supportsTranslate || reducedMotion) {
		return;
	}
	pressed = button;
	pressPointerId = event.pointerId;
	pointerX = event.clientX;
	pointerY = event.clientY;
	lastMove = performance.now();
	ensureState(button);
	requestFrame();
}

function onPointerEnd(event) {
	if (!pressed) {
		return;
	}
	if (event && event.pointerId !== pressPointerId) {
		return;
	}
	pressed = null;
	pressPointerId = null;
	lastMove = 0;
	requestFrame();
}

function onWindowBlur() {
	pressed = null;
	pressPointerId = null;
	lastMove = 0;
	states.forEach(state => {
		state.targetX = 0;
		state.targetY = 0;
	});
	requestFrame();
}

function onPointerLeaveDocument() {
	if (pressed) {
		onPointerEnd(null);
	}
}

function requestFrame() {
	if (!frame) {
		frame = requestAnimationFrame(tick);
	}
}

function ensureState(button) {
	let state = states.get(button);
	if (!state) {
		state = { x: 0, y: 0, targetX: 0, targetY: 0 };
		states.set(button, state);
	}
	return state;
}

function tick(now) {
	frame = 0;
	const held = pressed;

	if (held) {
		const state = ensureState(held);
		if (typeof held.getBoundingClientRect === 'function') {
			const rect = held.getBoundingClientRect();
			if (rect.width > 0 && rect.height > 0) {
				const dx = pointerX - (rect.left + rect.width / 2);
				const dy = pointerY - (rect.top + rect.height / 2);
				const limit = magnetLimit(held);
				state.targetX = clamp((dx / MAGNET_REACH) * limit, -limit, limit);
				state.targetY = clamp((dy / MAGNET_REACH) * limit, -limit, limit);
			} else {
				state.targetX = 0;
				state.targetY = 0;
			}
		}
	}

	let moving = false;
	states.forEach((state, button) => {
		if (button !== held) {
			state.targetX = 0;
			state.targetY = 0;
		}
		state.x += (state.targetX - state.x) * MAGNET_EASE;
		state.y += (state.targetY - state.y) * MAGNET_EASE;
		if (Math.abs(state.targetX - state.x) < 0.05 && Math.abs(state.targetY - state.y) < 0.05) {
			state.x = state.targetX;
			state.y = state.targetY;
		} else {
			moving = true;
		}
		applyOffset(button, state.x, state.y);
	});

	states.forEach((state, button) => {
		if (button !== held && button.isConnected === false && state.x === 0 && state.y === 0) {
			states.delete(button);
		}
	});

	if (moving || (held && now - lastMove < FRAME_LINGER)) {
		requestFrame();
	}
}

function applyOffset(button, x, y) {
	if (Math.abs(x) < 0.01 && Math.abs(y) < 0.01) {
		if (button.style.translate) {
			button.style.translate = '';
		}
		return;
	}
	button.style.translate = x.toFixed(2) + 'px ' + y.toFixed(2) + 'px';
}

function spawnRipple(button, clientX, clientY) {
	if (typeof button.getBoundingClientRect !== 'function') {
		return;
	}
	const rect = button.getBoundingClientRect();
	if (rect.width === 0 || rect.height === 0) {
		return;
	}

	Array.from(button.querySelectorAll('.ui-ripple')).forEach(node => {
		const parent = node.parentNode || node.parent;
		if (parent && typeof parent.removeChild === 'function') {
			parent.removeChild(node);
		}
	});

	const diameter = Math.max(rect.width, rect.height) * 2;
	const ripple = document.createElement('span');
	ripple.className = 'ui-ripple';
	ripple.setAttribute('aria-hidden', 'true');
	ripple.style.width = diameter + 'px';
	ripple.style.height = diameter + 'px';
	ripple.style.left = (clientX - rect.left - diameter / 2) + 'px';
	ripple.style.top = (clientY - rect.top - diameter / 2) + 'px';
	button.appendChild(ripple);
	ripple.addEventListener('animationend', () => {
		const parent = ripple.parentNode || ripple.parent;
		if (parent && typeof parent.removeChild === 'function') {
			parent.removeChild(ripple);
		}
	});
}

function magnetLimit(button) {
	if (typeof button.closest !== 'function') {
		return MAGNET_LIMIT;
	}
	// Compact controls live in containers that clip (list rows, child rows, the map editing pill). Their
	// already-small drift is reduced further so it never slicks against the parent's rounded edge.
	if (button.closest('.row')) {
		return 3;
	}
	if (button.closest('.child, .map-editing-bar')) {
		return 4;
	}
	return MAGNET_LIMIT;
}

function clamp(value, min, max) {
	return Math.min(max, Math.max(min, value));
}
