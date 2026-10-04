import { f, t } from './i18n.js?v=16';
import { MODE_ALL, MODES, countForMode } from './lists.js?v=16';

/*
 * DOM rendering for the sidebar.
 *
 * The whole sidebar is re-rendered on every state change rather than patched. At a page size of 20
 * rows that is a few dozen nodes, far below the point where reconciliation would earn its
 * complexity, and it removes a whole class of "the view and the state disagree" bugs.
 *
 * Event handling is delegated to the containers, so re-rendering cannot leave a stale listener
 * behind on a node that no longer exists.
 */

/** Resolved once; the markup is static so there is no need to re-query on every render. */
const el = {
	body: document.body,
	tabs: document.getElementById('tabs'),
	modes: document.getElementById('modes'),
	// The container, not just the chips: hiding only the children would leave its padding behind as a gap.
	modesContainer: document.getElementById('sidebar-filters'),
	search: document.getElementById('search'),
	searchClear: document.getElementById('search-clear'),
	list: document.getElementById('list'),
	empty: document.getElementById('list-empty'),
	refresh: document.getElementById('refresh'),
	readOnlyBadge: document.getElementById('readonly-badge'),
	worldLabel: document.getElementById('world-label'),
	// Map chrome. The canvas itself belongs to map.js; these are the overlay controls around it.
	mapArea: document.getElementById('map-area'),
	mapReadout: document.getElementById('map-readout'),
	mapEditingBar: document.getElementById('map-editing-bar'),
	mapEditingSize: document.getElementById('map-editing-size'),
	mapZoomIn: document.getElementById('map-zoom-in'),
	mapZoomOut: document.getElementById('map-zoom-out'),
	mapFocusPlayer: document.getElementById('map-focus-player'),
	mapCancelEdit: document.getElementById('map-cancel-edit'),
	editBar: document.getElementById('sidebar-edit-bar'),
	editAreaButton: document.getElementById('sidebar-edit-area')
};

/**
 * Switches the whole page to one view. The attribute lives on {@code body} because the shell and the
 * centred card are siblings that trade places, not nested alternatives.
 */
export function setView(view) {
	el.body.dataset.view = view;
}

/**
 * Shows the visitor's editing capability, in one badge whose wording follows the answer.
 *
 * The first version always read "Read-only" and only toggled visibility, which made a visitor with
 * edit rights see a badge claiming the opposite. Labels and colours are fully swapped now, so the
 * badge is unambiguous in both directions.
 *
 * @param {boolean} canEdit
 */
export function setCanEdit(canEdit) {
	if (!el.readOnlyBadge) {
		return;
	}
	el.readOnlyBadge.hidden = false;
	el.readOnlyBadge.dataset.state = canEdit ? 'editable' : 'readonly';
	el.readOnlyBadge.textContent = t(canEdit ? 'editableBadge' : 'readOnlyBadge');
}

/** Shows which dimension is on screen. */
export function setWorldLabel(dimension) {
	if (el.worldLabel) {
		el.worldLabel.textContent = dimension || '';
		el.worldLabel.hidden = !dimension;
	}
}

/** Shows the bar that appears while an area is being redrawn. */
export function setMapEditing(editing) {
	if (el.mapEditingBar) {
		el.mapEditingBar.hidden = !editing;
	}
	if (el.mapArea) {
		el.mapArea.classList.toggle('is-editing', editing);
	}
}

/**
 * Updates the coordinate readout.
 *
 * Coordinates only. The player's name used to be prefixed here as well as shown in the top bar, which
 * printed it twice and mixed an identity into what is a position readout.
 *
 * @param {{x: number, z: number}|null} point the world position under the cursor, or null when the cursor
 *        is off the map.
 */
export function setMapReadout(point) {
	if (!el.mapReadout) {
		return;
	}
	if (!point) {
		el.mapReadout.hidden = true;
		return;
	}
	el.mapReadout.hidden = false;
	// One decimal place, matching the game's own readout: enough to identify a block without the digits
	// jittering as the cursor moves.
	el.mapReadout.textContent = point.x.toFixed(1) + ', ' + point.z.toFixed(1);
}

/**
 * Shows how large the area being drawn currently is.
 *
 * This is the feedback that makes the drag legible - without it a finished rectangle gives no indication
 * of its own size, and a drag that failed to register looks identical to one that succeeded.
 *
 * @param {{minX: number, minZ: number, maxX: number, maxZ: number}|null} draft
 */
export function setMapDraftSize(draft) {
	if (!el.mapEditingSize) {
		return;
	}
	if (!draft) {
		el.mapEditingSize.textContent = '';
		return;
	}
	// Widths are inclusive of both end blocks, so a 1-block selection reads as 1 rather than 0.
	const width = Math.abs(draft.maxX - draft.minX) + 1;
	const height = Math.abs(draft.maxZ - draft.minZ) + 1;
	el.mapEditingSize.textContent = f('mapDraftSize', width, height);
}

/** @returns the map overlay buttons, for app.js to wire up. */
export function getMapButtons() {
	return {
		zoomIn: el.mapZoomIn,
		zoomOut: el.mapZoomOut,
		focusPlayer: el.mapFocusPlayer,
		cancelEdit: el.mapCancelEdit
	};
}

/**
 * Enables or disables the focus-on-player button.
 *
 * Disabled rather than hidden when the world reports no player, so the control does not come and go as the
 * viewer changes dimension, and so its absence is not mistaken for a missing feature.
 */
export function setFocusPlayerEnabled(enabled) {
	if (el.mapFocusPlayer) {
		el.mapFocusPlayer.disabled = !enabled;
	}
}

/** Reflects the search text into the input, for the cases where state changes it. */
export function syncSearchInput(search) {
	if (el.search && el.search.value !== search) {
		el.search.value = search;
	}
	if (el.searchClear) {
		el.searchClear.hidden = !search;
	}
}

function renderTabs(activeTab) {
	if (!el.tabs) {
		return;
	}
	el.tabs.replaceChildren(...[['stations', 'tabStations'], ['routes', 'tabRoutes'], ['depots', 'tabDepots']].map(([tab, key]) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'tab';
		button.dataset.tab = tab;
		button.setAttribute('role', 'tab');
		button.setAttribute('aria-selected', String(tab === activeTab));
		button.textContent = t(key);
		return button;
	}));
}

function renderModes(activeMode, activeTab, index) {
	if (!el.modes) {
		return;
	}

	const entries = [[MODE_ALL, 'modeAll'], ...MODES.map(mode => [mode, 'mode' + mode])];
	el.modes.replaceChildren(...entries.map(([mode, key]) => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'chip';
		button.dataset.mode = mode;
		button.setAttribute('aria-pressed', String(mode === activeMode));

		const label = document.createElement('span');
		label.textContent = t(key);
		button.appendChild(label);

		// Counts are omitted on the stations tab: stations are not filtered, so every chip would show the
		// same number and imply a filter that is not happening.
		const count = countForMode(index, activeTab, mode);
		if (count !== null) {
			const badge = document.createElement('span');
			badge.className = 'chip__count';
			badge.textContent = String(count);
			button.appendChild(badge);
		}

		return button;
	}));

	/*
	 * The whole mode filter is hidden on the stations tab.
	 *
	 * Stations are shared by all four transport modes - `Station.hasTransportMode()` is false - so filtering
	 * does nothing to that list. Leaving the chips clickable meant a control that visibly responded but
	 * changed nothing, which reads as broken rather than as inapplicable.
	 *
	 * The note that used to explain the no-op was removed along with them. With the filter gone there is
	 * nothing left to explain, and an apology for a control that is not on screen is just noise.
	 */
	if (el.modesContainer) {
		el.modesContainer.hidden = activeTab === 'stations';
	}
	if (el.modes) {
		el.modes.hidden = activeTab === 'stations';
	}
}

/**
 * @param {Array} rows   rows for the current page.
 * @param {string} state 'loading' | 'ready' | 'empty' | 'noresults' | 'unavailable' | 'failed'
 * @param {object} context extra data for the empty-state wording, e.g. the search text.
 */
function renderList(rows, state, context = {}) {
	if (!el.list || !el.empty) {
		return;
	}

	if (state !== 'ready') {
		el.list.replaceChildren();
		el.empty.hidden = false;
		el.empty.dataset.state = state;

		const title = el.empty.querySelector('[data-empty-title]');
		const body = el.empty.querySelector('[data-empty-body]');
		if (title) {
			title.textContent = emptyTitle(state, context);
		}
		if (body) {
			const bodyKey = emptyBody(state);
			body.textContent = bodyKey ? t(bodyKey) : '';
			body.hidden = !bodyKey;
		}
		return;
	}

	el.empty.hidden = true;
	// The whole context is passed, not just the selected id: renderRow also needs context.hideMode to
	// decide whether to show a mode tag. Passing a single field here threw on the first route or depot
	// row, which killed the render and left the previous list on screen - so switching tabs appeared
	// to do nothing at all.
	el.list.replaceChildren(...rows.map((row, position) => renderRow(row, position, context)));
}

function emptyTitle(state, context) {
	switch (state) {
		case 'loading':
			return t('loadingData');
		case 'unavailable':
			return t('dataUnavailable');
		case 'failed':
			return t('dataFailed');
		case 'noresults':
			return f('noResults', context.search || '');
		default:
			return t(context.emptyKey || 'noResults');
	}
}

function emptyBody(state) {
	return state === 'unavailable' ? 'dataUnavailableBody' : null;
}

function renderRow(row, position, context) {
	// A div with role="option" rather than a button, because the row now CONTAINS a button. A button inside
	// a button is invalid HTML and browsers recover from it by dropping one of them, which silently breaks
	// whichever action loses.
	const item = document.createElement('div');
	item.className = 'row';
	item.dataset.id = row.id;
	item.setAttribute('role', 'option');
	item.setAttribute('aria-selected', String(row.id === context.selectedId));
	// The row is only focusable when it is actually actionable, so the tab order does not fill up with
	// rows that cannot be opened.
	if (context.canEdit) {
		item.tabIndex = 0;
	}
	// Staggered entrance, capped by index in CSS so a long page does not crawl in.
	item.style.setProperty('--row-index', String(Math.min(position, 8)));

	const swatch = document.createElement('span');
	// A zero colour is a legitimate value and would render as an invisible black block, so it is marked
	// and drawn as an outlined empty swatch instead.
	swatch.className = row.color ? 'row__swatch' : 'row__swatch row__swatch--empty';
	if (row.color) {
		swatch.style.backgroundColor = '#' + row.color.toString(16).padStart(6, '0');
	}

	const text = document.createElement('span');
	text.className = 'row__text';

	const name = document.createElement('span');
	name.className = 'row__name';
	name.textContent = row.name || t('untitled');
	// The full name on hover, since a long one is ellipsised.
	name.title = row.name || t('untitled');
	text.appendChild(name);

	if (row.summary && row.summary.length > 0) {
		const summary = document.createElement('span');
		summary.className = 'row__summary';
		// Two shapes are possible: a translation key plus its argument, or a literal string that is
		// already display-ready (a depot's name, for instance). Joining happens here rather than in
		// lists.js so the derivation layer stays free of presentation decisions.
		summary.textContent = row.summary.map(part => (part.key ? f(part.key, part.value) : part.literal)).join(' · ');
		text.appendChild(summary);
	}

	item.append(swatch, text);

	if (row.mode && !context.hideMode) {
		// Which transport mode a route or depot belongs to. Redundant when a single mode is already
		// selected, so the caller suppresses it then.
		const mode = document.createElement('span');
		mode.className = 'row__mode';
		mode.textContent = t('mode' + row.mode);
		item.appendChild(mode);
	}

	// The single editing entry point. Clicking the row does the same thing, so this is discoverability
	// rather than a second path - and it is the only control on the row, which is the point.
	if (context.canEdit && context.editableKinds && context.editableKinds.has(context.rowKind)) {
		const edit = document.createElement('button');
		edit.type = 'button';
		edit.className = 'row__edit';
		edit.dataset.action = 'edit';
		edit.title = t('editButton');
		edit.setAttribute('aria-label', f('editRowLabel', row.name || t('untitled')));
		edit.appendChild(iconPencil());
		item.appendChild(edit);
	}

	return item;
}

/** The pencil glyph, inline so the page keeps its zero-external-request property. */
function iconPencil() {
	const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	svg.setAttribute('viewBox', '0 0 24 24');
	svg.setAttribute('width', '18');
	svg.setAttribute('height', '18');
	svg.setAttribute('aria-hidden', 'true');
	svg.setAttribute('focusable', 'false');
	const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
	path.setAttribute('d', 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z');
	svg.appendChild(path);
	return svg;
}

/**
 * Renders the entire sidebar for the given state.
 *
 * @param {object} state   the store's snapshot.
 * @param {object} derived the result of deriving rows for that snapshot.
 * @param {object} [chrome] editing affordances: {@code canEdit}, {@code editableKinds} (the set of kinds
 *        the server accepts edits for) and {@code editAreaId}, the selected object's id when it has a
 *        drawable area and the visitor may change it.
 */
export function renderSidebar(state, derived, chrome = {}) {
	renderTabs(state.tab);
	renderModes(state.mode, state.tab, state.index);
	syncSearchInput(state.search);
	renderEditBar(chrome);
	renderList(derived.rows, derived.listState, {
		search: state.search,
		emptyKey: derived.emptyKey,
		selectedId: state.selectedId,
		canEdit: chrome.canEdit,
		// The singular kind, not the tab name: a tab is named for its collection ("stations") and the server
		// and the editor speak of one object ("station"). The caller translates, because the mapping is a
		// fact about the tabs rather than about rendering a row.
		rowKind: chrome.rowKind,
		editableKinds: chrome.editableKinds,
		// Under a single-mode filter every route and depot row would repeat the same mode label.
		hideMode: state.mode !== MODE_ALL
	});
}

/**
 * Shows the "redraw area" action, but only when the selection actually has an area, the visitor may change
 * it, and no edit is already running.
 *
 * The editing flag is what makes cancelling work: once an edit begins the map owns the interaction, so the
 * button that started it must go away. Leaving it visible meant that pressing Cancel left the button on
 * screen doing nothing, because the object was still selected.
 *
 * Placed above the list rather than in the row's hover actions: the game puts area drawing on the list row,
 * but a web row has no room for a seventh icon without becoming a toolbar, and a mistaken click there would
 * redefine a station. One button for the selected item is harder to hit by accident.
 */
function renderEditBar(chrome) {
	if (!el.editBar) {
		return;
	}
	// Offered only for objects whose kind has a selection, which excludes routes, platforms and sidings.
	const available = Boolean(chrome.canEdit && chrome.editAreaId && !chrome.editingArea);
	el.editBar.hidden = !available;
	if (el.editAreaButton) {
		el.editAreaButton.dataset.id = chrome.editAreaId || '';
	}
}

/**
 * Wires the sidebar's interactions. Registered once; every handler reads the current state through
 * the callbacks rather than closing over a snapshot, so nothing goes stale after a re-render.
 */
export function bindSidebar(handlers) {
	if (el.tabs) {
		el.tabs.addEventListener('click', event => {
			const button = event.target.closest('.tab');
			if (button) {
				handlers.onTab(button.dataset.tab);
			}
		});
	}

	if (el.modes) {
		el.modes.addEventListener('click', event => {
			const button = event.target.closest('.chip');
			if (button) {
				handlers.onMode(button.dataset.mode);
			}
		});
	}

	if (el.list) {
		el.list.addEventListener('click', event => {
			const row = event.target.closest('.row');
			if (!row) {
				return;
			}
			// Clicking the row and clicking its edit button do the same thing, by design: the button is the
			// discoverable affordance and the row is the convenient one. Handled in one place so the two
			// cannot drift apart.
			handlers.onSelect(row.dataset.id);
		});
		// The row is a div rather than a button now, so the keyboard needs handling explicitly. Only Enter
		// and Space are claimed, and only when the row itself is focused - never when the edit button is,
		// which would double-fire.
		el.list.addEventListener('keydown', event => {
			if (event.key !== 'Enter' && event.key !== ' ') {
				return;
			}
			if (event.target.classList.contains('row')) {
				event.preventDefault();
				handlers.onSelect(event.target.dataset.id);
			}
		});
	}

	if (el.search) {
		el.search.addEventListener('input', () => handlers.onSearch(el.search.value));
		// Escape clears, matching the game's search field behaviour.
		el.search.addEventListener('keydown', event => {
			if (event.key === 'Escape') {
				handlers.onSearch('');
			}
		});
	}

	if (el.searchClear) {
		el.searchClear.addEventListener('click', () => handlers.onSearch(''));
	}

	if (el.refresh) {
		el.refresh.addEventListener('click', () => handlers.onRefresh());
	}

	if (el.editAreaButton) {
		el.editAreaButton.addEventListener('click', () => handlers.onEditArea());
	}

	// No arrow-key paging: the list scrolls, so the browser's own PageUp/PageDown, Home/End and arrow
	// key scrolling all work on it already. Hijacking the arrows would take those away.
}

/** Shows whether a background load is in flight, so a slow game tick does not look like a freeze. */
export function setBusy(busy) {
	if (el.refresh) {
		el.refresh.disabled = busy;
		el.refresh.classList.toggle('is-busy', busy);
	}
}
