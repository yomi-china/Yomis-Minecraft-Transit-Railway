import { getOverlaySettings, setOverlaySetting, OVERLAY_EVENT } from './overlay-settings.js?v=65';
import { getTrainSettings, setTrainSetting, TRAIN_EVENT } from './train-settings.js?v=65';
import { openSettings } from './settings.js?v=65';

let button = null;
let toast = null;
let closeButton = null;
let enabledButton = null;
let colorsButton = null;
let trainsSection = null;
let trainsButton = null;
let settingsButton = null;
let lastFocus = null;
let isOpen = false;
let closeTimer = 0;

function sync() {
	const settings = getOverlaySettings();
	if (enabledButton) {
		enabledButton.setAttribute('aria-pressed', String(settings.enabled));
	}
	if (colorsButton) {
		colorsButton.setAttribute('aria-pressed', String(settings.colors));
	}
	if (trainsButton) {
		trainsButton.setAttribute('aria-pressed', String(getTrainSettings().enabled));
	}
}

export function setTrainsAvailable(available) {
	if (!trainsSection || !trainsButton) {
		return;
	}
	trainsSection.hidden = !available;
	trainsButton.disabled = !available;
	if (isOpen) {
		position();
	}
}

function position() {
	if (!button || !toast || !isOpen) {
		return;
	}
	const rect = button.getBoundingClientRect();
	const width = toast.offsetWidth || 300;
	const height = toast.offsetHeight || 220;
	let left = rect.right + 12;
	if (left + width > window.innerWidth - 12) {
		left = rect.left - width - 12;
	}
	if (left < 12) {
		left = 12;
	}
	let top = rect.top;
	if (top + height > window.innerHeight - 12) {
		top = window.innerHeight - height - 12;
	}
	if (top < 12) {
		top = 12;
	}
	toast.style.left = left + 'px';
	toast.style.top = top + 'px';
}

function open() {
	if (isOpen || !toast || !button) {
		return;
	}
	isOpen = true;
	lastFocus = document.activeElement;
	clearTimeout(closeTimer);
	sync();
	toast.hidden = false;
	button.setAttribute('aria-expanded', 'true');
	const show = () => {
		if (!isOpen) {
			return;
		}
		toast.classList.add('is-open');
		position();
		if (enabledButton) {
			enabledButton.focus({ preventScroll: true });
		}
	};
	if (typeof requestAnimationFrame === 'function') {
		requestAnimationFrame(show);
	} else {
		show();
	}
}

function close(restoreFocus = true) {
	if (!isOpen) {
		return;
	}
	isOpen = false;
	toast.classList.remove('is-open');
	button.setAttribute('aria-expanded', 'false');
	clearTimeout(closeTimer);
	closeTimer = window.setTimeout(() => {
		if (!isOpen) {
			toast.hidden = true;
		}
	}, 180);
	if (restoreFocus && lastFocus && typeof lastFocus.focus === 'function') {
		lastFocus.focus();
	}
	lastFocus = null;
}

export function initOverlayToast() {
	button = document.getElementById('map-tracks');
	toast = document.getElementById('overlay-toast');
	closeButton = document.getElementById('overlay-toast-close');
	enabledButton = document.getElementById('overlay-toast-enabled');
	colorsButton = document.getElementById('overlay-toast-colors');
	trainsSection = document.getElementById('overlay-toast-trains-section');
	trainsButton = document.getElementById('overlay-toast-trains');
	settingsButton = document.getElementById('overlay-toast-settings');
	if (!button || !toast || !closeButton || !enabledButton || !colorsButton || !trainsSection || !trainsButton || !settingsButton) {
		return;
	}

	button.setAttribute('aria-haspopup', 'dialog');
	button.setAttribute('aria-controls', 'overlay-toast');
	button.setAttribute('aria-expanded', 'false');

	button.addEventListener('click', event => {
		event.stopPropagation();
		if (isOpen) {
			close();
		} else {
			open();
		}
	});
	closeButton.addEventListener('click', () => close());
	enabledButton.addEventListener('click', () => {
		setOverlaySetting('enabled', !getOverlaySettings().enabled);
		sync();
	});
	colorsButton.addEventListener('click', () => {
		setOverlaySetting('colors', !getOverlaySettings().colors);
		sync();
	});
	trainsButton.addEventListener('click', () => {
		setTrainSetting('enabled', !getTrainSettings().enabled);
		sync();
	});
	settingsButton.addEventListener('click', () => {
		close(false);
		openSettings('tracks');
	});
	document.addEventListener('pointerdown', event => {
		if (!isOpen) {
			return;
		}
		if (toast.contains(event.target) || button.contains(event.target)) {
			return;
		}
		close();
	});
	document.addEventListener('keydown', event => {
		if (isOpen && event.key === 'Escape') {
			event.preventDefault();
			close();
		}
	});
	window.addEventListener('resize', position);
	window.addEventListener(OVERLAY_EVENT, sync);
	window.addEventListener(TRAIN_EVENT, sync);
	sync();
}
