import { t } from './i18n.js?v=65';
import { ACCENTS, THEME_EVENT, getTheme, setAccent, setMode } from './theme.js?v=65';
import { getOverlaySettings, setOverlaySetting, OVERLAY_EVENT } from './overlay-settings.js?v=65';
import { getTrainSettings, setTrainSetting, TRAIN_EVENT } from './train-settings.js?v=65';

let backdrop = null;
let dialog = null;
let closeButton = null;
let settingsButton = null;
let modeGroup = null;
let themeGrid = null;
let nav = null;
let panels = [];
let trackMinScale = null;
let trackMinScaleValue = null;
let trackThickness = null;
let trackThicknessValue = null;
let trackColors = null;
let trainEnabled = null;
let trainConsistGroup = null;
let trainRate = null;
let trainRateValue = null;
let trainMax = null;
let trainMaxValue = null;
let trainMinScale = null;
let trainMinScaleValue = null;
let activeCategory = 'personalization';
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

function formatMultiplier(value) {
	const rounded = Math.round(value * 100) / 100;
	return String(rounded).replace(/\.?0+$/, '') + '×';
}

function syncTrackControls() {
	const settings = getOverlaySettings();
	if (trackMinScale) {
		trackMinScale.value = String(settings.minScale);
	}
	if (trackMinScaleValue) {
		trackMinScaleValue.textContent = formatMultiplier(settings.minScale);
	}
	if (trackThickness) {
		trackThickness.value = String(settings.thickness);
	}
	if (trackThicknessValue) {
		trackThicknessValue.textContent = formatMultiplier(settings.thickness);
	}
	if (trackColors) {
		trackColors.checked = settings.colors;
	}
}

function syncTrainControls() {
	const settings = getTrainSettings();
	if (trainEnabled) {
		trainEnabled.checked = settings.enabled;
	}
	if (trainConsistGroup) {
		for (const button of trainConsistGroup.querySelectorAll('[data-consist]')) {
			button.setAttribute('aria-checked', String(button.dataset.consist === settings.consist));
		}
	}
	if (trainRate) {
		trainRate.value = String(settings.rateHz);
	}
	if (trainRateValue) {
		trainRateValue.textContent = settings.rateHz + ' Hz';
	}
	if (trainMax) {
		trainMax.value = String(settings.max);
	}
	if (trainMaxValue) {
		trainMaxValue.textContent = String(settings.max);
	}
	if (trainMinScale) {
		trainMinScale.value = String(settings.minScale);
	}
	if (trainMinScaleValue) {
		trainMinScaleValue.textContent = formatMultiplier(settings.minScale);
	}
}

function selectCategory(category) {
	activeCategory = category === 'tracks' || category === 'trains' ? category : 'personalization';
	if (nav) {
		for (const button of nav.querySelectorAll('[data-category]')) {
			const selected = button.dataset.category === activeCategory;
			button.classList.toggle('is-active', selected);
			if (selected) {
				button.setAttribute('aria-current', 'page');
			} else {
				button.removeAttribute('aria-current');
			}
		}
	}
	for (const panel of panels) {
		panel.hidden = panel.dataset.panel !== activeCategory;
	}
	if (activeCategory === 'tracks') {
		syncTrackControls();
	} else if (activeCategory === 'trains') {
		syncTrainControls();
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

export function openSettings(category = 'personalization') {
	selectCategory(category);
	open();
}

export function initSettings() {
	backdrop = document.getElementById('settings-backdrop');
	dialog = document.getElementById('settings-dialog');
	closeButton = document.getElementById('settings-close');
	settingsButton = document.getElementById('settings');
	modeGroup = document.getElementById('settings-mode');
	themeGrid = document.getElementById('theme-grid');
	nav = dialog ? dialog.querySelector('.settings__nav') : null;
	panels = dialog ? Array.from(dialog.querySelectorAll('[data-panel]')) : [];
	trackMinScale = document.getElementById('track-min-scale');
	trackMinScaleValue = document.getElementById('track-min-scale-value');
	trackThickness = document.getElementById('track-thickness');
	trackThicknessValue = document.getElementById('track-thickness-value');
	trackColors = document.getElementById('track-colors');
	trainEnabled = document.getElementById('train-enabled');
	trainConsistGroup = document.getElementById('train-consist');
	trainRate = document.getElementById('train-rate');
	trainRateValue = document.getElementById('train-rate-value');
	trainMax = document.getElementById('train-max');
	trainMaxValue = document.getElementById('train-max-value');
	trainMinScale = document.getElementById('train-min-scale');
	trainMinScaleValue = document.getElementById('train-min-scale-value');
	if (!backdrop || !dialog || !closeButton || !settingsButton || !modeGroup || !themeGrid || !nav) {
		return;
	}

	settingsButton.setAttribute('aria-haspopup', 'dialog');
	settingsButton.setAttribute('aria-controls', 'settings-dialog');
	settingsButton.setAttribute('aria-expanded', 'false');
	dialog.tabIndex = -1;
	buildThemeChoices();
	selectCategory('personalization');

	for (const button of modeGroup.querySelectorAll('[data-mode]')) {
		button.addEventListener('click', () => {
			setMode(button.dataset.mode);
			syncSelection();
		});
	}

	for (const button of nav.querySelectorAll('[data-category]')) {
		button.addEventListener('click', () => selectCategory(button.dataset.category));
	}

	if (trackMinScale) {
		trackMinScale.addEventListener('input', () => {
			setOverlaySetting('minScale', Number(trackMinScale.value));
			syncTrackControls();
		});
	}
	if (trackThickness) {
		trackThickness.addEventListener('input', () => {
			setOverlaySetting('thickness', Number(trackThickness.value));
			syncTrackControls();
		});
	}
	if (trackColors) {
		trackColors.addEventListener('change', () => {
			setOverlaySetting('colors', trackColors.checked);
			syncTrackControls();
		});
	}

	if (trainEnabled) {
		trainEnabled.addEventListener('change', () => {
			setTrainSetting('enabled', trainEnabled.checked);
			syncTrainControls();
		});
	}
	if (trainConsistGroup) {
		for (const button of trainConsistGroup.querySelectorAll('[data-consist]')) {
			button.addEventListener('click', () => {
				setTrainSetting('consist', button.dataset.consist);
				syncTrainControls();
			});
		}
	}
	if (trainRate) {
		trainRate.addEventListener('input', () => {
			setTrainSetting('rateHz', Number(trainRate.value));
			syncTrainControls();
		});
	}
	if (trainMax) {
		trainMax.addEventListener('input', () => {
			setTrainSetting('max', Number(trainMax.value));
			syncTrainControls();
		});
	}
	if (trainMinScale) {
		trainMinScale.addEventListener('input', () => {
			setTrainSetting('minScale', Number(trainMinScale.value));
			syncTrainControls();
		});
	}

	settingsButton.addEventListener('click', () => openSettings('personalization'));
	closeButton.addEventListener('click', close);
	backdrop.addEventListener('click', event => {
		if (event.target === backdrop) {
			close();
		}
	});
	dialog.addEventListener('keydown', onKeydown);
	window.addEventListener(THEME_EVENT, syncSelection);
	window.addEventListener(OVERLAY_EVENT, syncTrackControls);
	window.addEventListener(TRAIN_EVENT, syncTrainControls);
	syncSelection();
	syncTrackControls();
	syncTrainControls();
}
