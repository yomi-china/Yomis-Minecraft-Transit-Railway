// Controller for the visitor welcome layer: picks the slogan once per page load, swaps the copy
// for the four non-dashboard views, and owns the small help bubble. The Canvas renderer lives in
// welcome-bg.js so this file stays about text and interaction.

import { t } from './i18n.js?v=35';
import { mount } from './welcome-bg.js?v=35';

export const SLOGAN_KEYS = [
	'welcomeSlogan1',
	'welcomeSlogan2',
	'welcomeSlogan3',
	'welcomeSlogan4',
	'welcomeSlogan5',
	'welcomeSlogan6'
];

const VIEW_STATE = {
	checking: { state: 'checking', labelKey: 'statusLoading' },
	readonly: { state: 'readonly', labelKey: 'statusReadOnly' },
	offline: { state: 'offline', labelKey: 'statusOfflineShort' },
	expired: { state: 'expired', labelKey: 'statusExpiredShort' },
	home: { state: 'ready', labelKey: 'statusReady' }
};

const el = {
	canvas: null,
	sheet: null,
	title: null,
	titleText: null,
	subtitle: null,
	helpWrap: null,
	helpButton: null,
	enterButton: null,
	bubble: null,
	bubbleClose: null,
	state: null,
	stateLabel: null
};

let background = null;
let initialized = false;
let bubbleOpen = false;
let sloganKey = null;

/**
 * @param {() => number} [random]
 * @returns {string} one of SLOGAN_KEYS
 */
export function pickSlogan(random = Math.random) {
	const value = Number(random());
	const index = Math.min(
		SLOGAN_KEYS.length - 1,
		Math.max(0, Math.floor((Number.isFinite(value) ? value : 0) * SLOGAN_KEYS.length))
	);
	return SLOGAN_KEYS[index];
}

function setBubble(open) {
	if (!el.bubble || !el.helpButton) {
		return;
	}
	bubbleOpen = Boolean(open);
	el.bubble.dataset.open = bubbleOpen ? 'true' : 'false';
	el.bubble.setAttribute('aria-hidden', bubbleOpen ? 'false' : 'true');
	el.helpButton.setAttribute('aria-expanded', bubbleOpen ? 'true' : 'false');
	if (el.sheet) {
		el.sheet.classList.toggle('is-help-open', bubbleOpen);
	}
}

function closeBubble() {
	setBubble(false);
}

function bindHelp() {
	if (!el.helpButton || !el.bubble) {
		return;
	}

	el.helpButton.addEventListener('click', () => setBubble(!bubbleOpen));

	if (el.bubbleClose) {
		el.bubbleClose.addEventListener('click', () => {
			closeBubble();
			el.helpButton.focus();
		});
	}

	document.addEventListener('pointerdown', event => {
		if (!bubbleOpen) {
			return;
		}
		const target = event.target;
		if (el.helpWrap && target && typeof el.helpWrap.contains === 'function' && el.helpWrap.contains(target)) {
			return;
		}
		closeBubble();
	}, true);

	document.addEventListener('keydown', event => {
		if (event.key === 'Escape' && bubbleOpen) {
			closeBubble();
			el.helpButton.focus();
		}
	});
}

/**
 * Resolves the welcome markup and starts the backdrop. Safe to call twice.
 */
export function init() {
	if (initialized) {
		return;
	}
	initialized = true;

	el.canvas = document.getElementById('welcome-canvas');
	el.sheet = document.getElementById('welcome-sheet');
	el.title = document.getElementById('welcome-title');
	el.titleText = document.getElementById('welcome-title-text');
	el.subtitle = document.getElementById('welcome-subtitle');
	el.helpWrap = document.getElementById('welcome-help-wrap');
	el.helpButton = document.getElementById('welcome-help');
	el.enterButton = document.getElementById('welcome-enter');
	el.bubble = document.getElementById('welcome-help-bubble');
	el.bubbleClose = document.getElementById('welcome-help-close');
	el.state = document.getElementById('welcome-state');
	el.stateLabel = document.getElementById('welcome-state-label');

	sloganKey = pickSlogan();
	background = mount(el.canvas);
	bindHelp();
	setView('checking');
}

function setTitleText(value) {
	if (el.titleText) {
		el.titleText.textContent = value;
	} else if (el.title) {
		el.title.textContent = value;
	}
}

/**
 * @param {'checking'|'readonly'|'offline'|'expired'|'dashboard'} view
 */
export function setView(view) {
	if (!initialized) {
		return;
	}

	closeBubble();

	const info = VIEW_STATE[view];
	if (info) {
		if (el.state) {
			el.state.dataset.state = info.state;
		}
		if (el.stateLabel) {
			el.stateLabel.textContent = t(info.labelKey);
		}
	}

	if (el.title) {
		if (view === 'readonly' || view === 'home') {
			setTitleText(t(sloganKey));
		} else if (view === 'offline') {
			setTitleText(t('offlineHeadline'));
		} else if (view === 'expired') {
			setTitleText(t('expiredHeadline'));
		} else if (view === 'checking') {
			setTitleText('');
		}
	}

	if (el.subtitle) {
		if (view === 'readonly') {
			el.subtitle.textContent = t('welcomeSubtitle');
		} else if (view === 'home') {
			el.subtitle.textContent = t('homeSubtitle');
		} else if (view === 'offline') {
			el.subtitle.textContent = t('offlineBody');
		} else if (view === 'expired') {
			el.subtitle.textContent = t('expiredBody');
		} else if (view === 'checking') {
			el.subtitle.textContent = '';
		}
	}

	if (el.helpWrap) {
		el.helpWrap.hidden = view !== 'readonly';
	}

	if (el.enterButton) {
		el.enterButton.hidden = view !== 'home';
	}

	if (background) {
		background.setActive(view !== 'dashboard');
	}
}
