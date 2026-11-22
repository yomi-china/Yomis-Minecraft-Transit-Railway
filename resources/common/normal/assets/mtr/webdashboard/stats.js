// Controller for the statistics dialog. Every figure is aggregated from the world the dashboard has
// already loaded, so opening the dialog never asks the server to scan anything.

import { f, t } from './i18n.js?v=64';
import { MODES, formatName } from './lists.js?v=64';

const countFormatter = new Intl.NumberFormat();

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

const el = {
	trigger: null,
	backdrop: null,
	dialog: null,
	close: null,
	body: null
};

let getSnapshot = () => ({ world: null, index: null });
let isOpen = false;
let lastFocus = null;
let closeTimer = 0;

function buildText(tag, className, text) {
	const element = document.createElement(tag);
	if (className) {
		element.className = className;
	}
	element.textContent = text;
	return element;
}

function formatLength(value) {
	const rounded = Math.round(value * 10) / 10;
	return f('statsLength', Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1));
}

function platformLabel(platform, index) {
	const name = formatName(platform.name) || t('untitled');
	const station = platform.stationId && index.stationById ? index.stationById.get(platform.stationId) : null;
	const stationName = station ? formatName(station.name) : '';
	return stationName ? stationName + ' · ' + name : name;
}

function sidingLabel(siding, index) {
	const name = formatName(siding.name) || t('untitled');
	const depot = siding.depotId && index.depotById ? index.depotById.get(siding.depotId) : null;
	const depotName = depot ? formatName(depot.name) : '';
	return depotName ? depotName + ' · ' + name : name;
}

/** Equal lengths are broken by name so a refresh cannot swap two records at random. */
function longer(current, candidate) {
	if (!current) {
		return candidate;
	}
	if (candidate.length !== current.length) {
		return candidate.length > current.length ? candidate : current;
	}
	return candidate.name < current.name ? candidate : current;
}

function shorter(current, candidate) {
	if (!current) {
		return candidate;
	}
	if (candidate.length !== current.length) {
		return candidate.length < current.length ? candidate : current;
	}
	return candidate.name < current.name ? candidate : current;
}

/** @returns {Array<object>} one record per transport mode, skipping modes with nothing to count. */
function collectModes(world, index) {
	const modes = new Map(MODES.map(mode => [mode, {
		mode,
		platforms: index.modeCounts.platforms.get(mode) || 0,
		sidings: index.modeCounts.sidings.get(mode) || 0,
		tracks: 0,
		longestPlatform: null,
		shortestPlatform: null,
		longestSiding: null,
		shortestSiding: null,
		longestTrack: null
	}]));

	const railStats = world.railStats || {};
	modes.forEach(record => {
		const rails = railStats[record.mode];
		if (!rails) {
			return;
		}
		record.tracks = Number(rails.count) || 0;
		record.longestTrack = Number.isFinite(rails.longest) ? rails.longest : null;
	});

	(world.platforms || []).forEach(platform => {
		const record = modes.get(platform.transportMode);
		if (!record || !Number.isFinite(platform.length)) {
			return;
		}
		const candidate = { length: platform.length, name: platformLabel(platform, index) };
		record.longestPlatform = longer(record.longestPlatform, candidate);
		record.shortestPlatform = shorter(record.shortestPlatform, candidate);
	});

	(world.sidings || []).forEach(siding => {
		const record = modes.get(siding.transportMode);
		if (!record || !Number.isFinite(siding.railLength)) {
			return;
		}
		const candidate = { length: siding.railLength, name: sidingLabel(siding, index) };
		record.longestSiding = longer(record.longestSiding, candidate);
		record.shortestSiding = shorter(record.shortestSiding, candidate);
	});

	return Array.from(modes.values()).filter(record => record.platforms > 0 || record.sidings > 0 || record.tracks > 0);
}

function buildTile(value, labelKey) {
	const tile = document.createElement('div');
	tile.className = 'stats-tile';
	tile.append(
		buildText('span', 'stats-tile__value', countFormatter.format(value)),
		buildText('span', 'stats-tile__label', t(labelKey))
	);
	return tile;
}

function buildCount(value, labelKey) {
	const count = document.createElement('span');
	count.className = 'stats-count';
	const number = document.createElement('b');
	number.textContent = countFormatter.format(value);
	count.append(number, document.createTextNode(t(labelKey)));
	return count;
}

function buildCounts(totals) {
	const counts = document.createElement('div');
	counts.className = 'stats-counts';
	counts.append(
		buildCount(totals.platforms, 'sectionPlatforms'),
		buildCount(totals.sidings, 'sectionSidings'),
		buildCount(totals.tracks, 'statsTracks')
	);
	return counts;
}

function buildRecord(labelKey, entry) {
	const row = document.createElement('div');
	row.className = 'stats-record';

	const text = document.createElement('div');
	text.className = 'stats-record__text';
	text.appendChild(buildText('span', 'stats-record__label', t(labelKey)));
	if (entry && entry.name) {
		const name = buildText('span', 'stats-record__name', entry.name);
		name.title = entry.name;
		text.appendChild(name);
	}

	row.append(text, buildText('span', 'stats-record__value', entry ? formatLength(entry.length) : '—'));
	return row;
}

function buildModeCard(record) {
	const card = document.createElement('article');
	card.className = 'stats-mode';

	const header = document.createElement('header');
	header.className = 'stats-mode__header';
	header.append(buildText('h4', 'stats-mode__name', t('mode' + record.mode)), buildCounts({
		platforms: record.platforms,
		sidings: record.sidings,
		tracks: record.tracks
	}));

	const records = document.createElement('div');
	records.className = 'stats-records';
	records.append(
		buildRecord('statsLongestPlatform', record.longestPlatform),
		buildRecord('statsShortestPlatform', record.shortestPlatform),
		buildRecord('statsLongestSiding', record.longestSiding),
		buildRecord('statsShortestSiding', record.shortestSiding),
		buildRecord('statsLongestTrack', record.longestTrack == null ? null : { length: record.longestTrack })
	);

	card.append(header, records);
	return card;
}

function render(world, index) {
	if (!el.body) {
		return;
	}
	if (!world) {
		el.body.replaceChildren(buildText('p', 'stats-empty', t('dataUnavailable')));
		return;
	}

	const summary = document.createElement('div');
	summary.className = 'stats-summary';
	summary.append(
		buildTile((world.stations || []).length, 'tabStations'),
		buildTile((world.routes || []).length, 'tabRoutes'),
		buildTile((world.depots || []).length, 'tabDepots')
	);

	const modes = collectModes(world, index);
	if (modes.length === 0) {
		el.body.replaceChildren(summary, buildText('p', 'stats-empty', t('statsEmpty')));
		return;
	}

	const totals = modes.reduce((sum, record) => {
		sum.platforms += record.platforms;
		sum.sidings += record.sidings;
		sum.tracks += record.tracks;
		return sum;
	}, { platforms: 0, sidings: 0, tracks: 0 });

	const head = document.createElement('div');
	head.className = 'stats-section__head';
	head.append(buildText('h3', 'stats-section__title', t('statsByMode')), buildCounts(totals));

	const section = document.createElement('section');
	section.className = 'stats-section';
	const list = document.createElement('div');
	list.className = 'stats-modes';
	list.append(...modes.map(buildModeCard));
	section.append(head, list);

	el.body.replaceChildren(summary, section);
}

function focusables() {
	return el.dialog ? Array.from(el.dialog.querySelectorAll(FOCUSABLE)).filter(element => element.offsetParent !== null) : [];
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
	if (document.activeElement === el.dialog) {
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
	if (isOpen || !el.backdrop) {
		return;
	}
	isOpen = true;
	lastFocus = document.activeElement;
	clearTimeout(closeTimer);
	const snapshot = getSnapshot();
	render(snapshot.world, snapshot.index);
	el.backdrop.hidden = false;
	el.trigger?.setAttribute('aria-expanded', 'true');
	const show = () => {
		el.backdrop.classList.add('is-open');
		el.dialog.focus({ preventScroll: true });
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
	el.backdrop.classList.remove('is-open');
	el.trigger?.setAttribute('aria-expanded', 'false');
	clearTimeout(closeTimer);
	closeTimer = window.setTimeout(() => {
		if (!isOpen) {
			el.backdrop.hidden = true;
		}
	}, 240);
	if (lastFocus && typeof lastFocus.focus === 'function') {
		lastFocus.focus();
	}
}

/**
 * @param {{getData: () => {world: object|null, index: object}}} options
 */
export function initStatistics(options) {
	if (options && typeof options.getData === 'function') {
		getSnapshot = options.getData;
	}

	el.trigger = document.getElementById('statistics');
	el.backdrop = document.getElementById('stats-backdrop');
	el.dialog = document.getElementById('stats-dialog');
	el.close = document.getElementById('stats-close');
	el.body = document.getElementById('stats-body');
	if (!el.trigger || !el.backdrop || !el.dialog || !el.close || !el.body) {
		return;
	}

	el.trigger.setAttribute('aria-haspopup', 'dialog');
	el.trigger.setAttribute('aria-controls', 'stats-dialog');
	el.trigger.setAttribute('aria-expanded', 'false');
	el.dialog.tabIndex = -1;

	el.trigger.addEventListener('click', open);
	el.close.addEventListener('click', close);
	el.backdrop.addEventListener('click', event => {
		if (event.target === el.backdrop) {
			close();
		}
	});
	el.dialog.addEventListener('keydown', onKeydown);
}
