import { t } from './i18n.js?v=35';
import { ACCENTS, THEME_EVENT, getTheme, setAccent, setMode } from './theme.js?v=35';

let backdrop = null;
let dialog = null;
let closeButton = null;
let settingsButton = null;
let modeGroup = null;
let themeGrid = null;
let lastFocus = null;
let isOpen = false;
let closeTimer = 0;

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function syncSelection() {
	const current = getTheme();
	if (modeGroup) {
		for (const button of modeGroup.querySelectorAll('[data-mode]')) {
			button.setAttribute('aria-checked', String(button.dataset.mode === current.mode));
		}
	}
	if (themeGrid) {
		for (const button of themeGrid.querySelectorAll('[data-accent]')) {
			button.setAttribute('aria-checked', String(button.dataset.accent === current.accentId));
		}
	}
}

function buildThemeChoices() {
	if (!themeGrid) {
		return;
	}
	themeGrid.replaceChildren(...ACCENTS.map(accent => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'theme-choice';
		button.dataset.accent = accent.id;
		button.setAttribute('role', 'radio');
		button.setAttribute('aria-checked', 'false');
		button.title = t(accent.labelKey);

		const dot = document.createElement('span');
		dot.className = 'theme-choice__dot';
		dot.setAttribute('aria-hidden', 'true');
		dot.style.backgroundColor = accent.color;

		const name = document.createElement('span');
		name.className = 'theme-choice__name';
		name.dataset.i18n = accent.labelKey;
		name.textContent = t(accent.labelKey);

		button.append(dot, name);
		button.addEventListener('click', () => {
			setAccent(accent.id);
			syncSelection();
		});
		return button;
	}));
}

function focusables() {
	return dialog ? Array.from(dialog.querySelectorAll(FOCUSABLE)).filter(element => element.offsetParent !== null) : [];
}

function onKeydown(event) {
	if (!isOpen) {
		return;
	}
	if (event.key === 'Escape') {
		event.preventDefault();
		close();
		return;
	}
	if (event.key !== 'Tab') {
		return;
	}
	const items = focusables();
	if (items.length === 0) {
		event.preventDefault();
		return;
	}
	const first = items[0];
	const last = items[items.length - 1];
	if (document.activeElement === dialog) {
		event.preventDefault();
		(event.shiftKey ? last : first).focus();
		return;
	}
	if (event.shiftKey && document.activeElement === first) {
		event.preventDefault();
		last.focus();
	} else if (!event.shiftKey && document.activeElement === last) {
		event.preventDefault();
		first.focus();
	}
}

function open() {
	if (isOpen || !backdrop) {
		return;
	}
	isOpen = true;
	lastFocus = document.activeElement;
	clearTimeout(closeTimer);
	backdrop.hidden = false;
	settingsButton?.setAttribute('aria-expanded', 'true');
	syncSelection();
	const show = () => {
		backdrop.classList.add('is-open');
		dialog.focus({ preventScroll: true });
	};
	if (typeof requestAnimationFrame === 'function') {
		requestAnimationFrame(show);
	} else {
		show();
	}
}

function close() {
	if (!isOpen) {
		return;
	}
	isOpen = false;
	backdrop.classList.remove('is-open');
	settingsButton?.setAttribute('aria-expanded', 'false');
	clearTimeout(closeTimer);
	closeTimer = window.setTimeout(() => {
		if (!isOpen) {
			backdrop.hidden = true;
		}
	}, 240);
	if (lastFocus && typeof lastFocus.focus === 'function') {
		lastFocus.focus();
	}
}

export function initSettings() {
	backdrop = document.getElementById('settings-backdrop');
	dialog = document.getElementById('settings-dialog');
	closeButton = document.getElementById('settings-close');
	settingsButton = document.getElementById('settings');
	modeGroup = document.getElementById('settings-mode');
	themeGrid = document.getElementById('theme-grid');
	if (!backdrop || !dialog || !closeButton || !settingsButton || !modeGroup || !themeGrid) {
		return;
	}

	settingsButton.setAttribute('aria-haspopup', 'dialog');
	settingsButton.setAttribute('aria-controls', 'settings-dialog');
	settingsButton.setAttribute('aria-expanded', 'false');
	dialog.tabIndex = -1;
	buildThemeChoices();

	for (const button of modeGroup.querySelectorAll('[data-mode]')) {
		button.addEventListener('click', () => {
			setMode(button.dataset.mode);
			syncSelection();
		});
	}

	settingsButton.addEventListener('click', open);
	closeButton.addEventListener('click', close);
	backdrop.addEventListener('click', event => {
		if (event.target === backdrop) {
			close();
		}
	});
	dialog.addEventListener('keydown', onKeydown);
	window.addEventListener(THEME_EVENT, syncSelection);
	syncSelection();
}
