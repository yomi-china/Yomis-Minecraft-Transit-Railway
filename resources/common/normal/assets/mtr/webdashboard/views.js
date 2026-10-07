import { f, t } from './i18n.js?v=20';
import { MODE_ALL, MODES, countForMode } from './lists.js?v=20';

const el = {
	body: document.body,
	tabs: document.getElementById('tabs'),
	modes: document.getElementById('modes'),
	modesContainer: document.getElementById('sidebar-filters'),
	search: document.getElementById('search'),
	searchClear: document.getElementById('search-clear'),
	list: document.getElementById('list'),
	empty: document.getElementById('list-empty'),
	refresh: document.getElementById('refresh'),
	readOnlyBadge: document.getElementById('readonly-badge'),
	worldLabel: document.getElementById('world-label'),
	mapArea: document.getElementById('map-area'),
	mapReadout: document.getElementById('map-readout'),
	mapEditingBar: document.getElementById('map-editing-bar'),
	mapEditingSize: document.getElementById('map-editing-size'),
	mapSaveEdit: document.getElementById('map-save-edit'),
	mapZoomIn: document.getElementById('map-zoom-in'),
	mapZoomOut: document.getElementById('map-zoom-out'),
	mapFocusPlayer: document.getElementById('map-focus-player'),
	mapCancelEdit: document.getElementById('map-cancel-edit'),
};

export function setView(view) {
	el.body.dataset.view = view;
}

export function setCanEdit(canEdit) {
	if (!el.readOnlyBadge) {
		return;
	}
	el.readOnlyBadge.hidden = false;
	el.readOnlyBadge.dataset.state = canEdit ? 'editable' : 'readonly';
	el.readOnlyBadge.textContent = t(canEdit ? 'editableBadge' : 'readOnlyBadge');
}

export function setWorldLabel(dimension) {
	if (el.worldLabel) {
		el.worldLabel.textContent = dimension || '';
		el.worldLabel.hidden = !dimension;
	}
}

export function setMapEditing(editing) {
	if (el.mapEditingBar) {
		el.mapEditingBar.hidden = !editing;
	}
	if (el.mapArea) {
		el.mapArea.classList.toggle('is-editing', editing);
	}
}

export function setMapReadout(point) {
	if (!el.mapReadout) {
		return;
	}
	if (!point) {
		el.mapReadout.hidden = true;
		return;
	}
	el.mapReadout.hidden = false;
	el.mapReadout.textContent = point.x.toFixed(1) + ', ' + point.z.toFixed(1);
}

export function setMapDraftSize(draft) {
	if (!el.mapEditingSize) {
		return;
	}

	if (!draft) {
		el.mapEditingSize.textContent = '';
		el.mapEditingSize.dataset.invalid = 'false';
		setMapSaveEnabled(false);
		return;
	}

	const width = Math.abs(draft.maxX - draft.minX) + 1;
	const height = Math.abs(draft.maxZ - draft.minZ) + 1;
	el.mapEditingSize.textContent = f('mapDraftSize', width, height);
	el.mapEditingSize.dataset.invalid = draft.invalid ? 'true' : 'false';

	setMapSaveEnabled(!draft.invalid);
}

export function setMapSaveEnabled(enabled) {
	if (el.mapSaveEdit) {
		el.mapSaveEdit.disabled = !enabled;
	}
}

/** @returns the map overlay buttons, for app.js to wire up. */
export function getMapButtons() {
	return {
		zoomIn: el.mapZoomIn,
		zoomOut: el.mapZoomOut,
		focusPlayer: el.mapFocusPlayer,
		cancelEdit: el.mapCancelEdit,
		saveEdit: el.mapSaveEdit
	};
}

export function setFocusPlayerEnabled(enabled) {
	if (el.mapFocusPlayer) {
		el.mapFocusPlayer.disabled = !enabled;
	}
}

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

		const count = countForMode(index, activeTab, mode);
		if (count !== null) {
			const badge = document.createElement('span');
			badge.className = 'chip__count';
			badge.textContent = String(count);
			button.appendChild(badge);
		}

		return button;
	}));

	if (el.modesContainer) {
		el.modesContainer.hidden = activeTab === 'stations';
	}
	if (el.modes) {
		el.modes.hidden = activeTab === 'stations';
	}
}

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
	const item = document.createElement('div');
	item.className = 'row';
	item.dataset.id = row.id;
	item.setAttribute('role', 'option');
	item.setAttribute('aria-selected', String(row.id === context.selectedId));
	if (context.canEdit) {
		item.tabIndex = 0;
	}
	item.style.setProperty('--row-index', String(Math.min(position, 8)));

	const swatch = document.createElement('span');
	swatch.className = row.color ? 'row__swatch' : 'row__swatch row__swatch--empty';
	if (row.color) {
		swatch.style.backgroundColor = '#' + row.color.toString(16).padStart(6, '0');
	}

	const text = document.createElement('span');
	text.className = 'row__text';

	const name = document.createElement('span');
	name.className = 'row__name';
	name.textContent = row.name || t('untitled');
	name.title = row.name || t('untitled');
	text.appendChild(name);

	if (row.summary && row.summary.length > 0) {
		const summary = document.createElement('span');
		summary.className = 'row__summary';
		summary.textContent = row.summary.map(part => (part.key ? f(part.key, part.value) : part.literal)).join(' ');
		text.appendChild(summary);
	}

	item.append(swatch, text);

	if (row.mode && !context.hideMode) {
		const mode = document.createElement('span');
		mode.className = 'row__mode';
		mode.textContent = t('mode' + row.mode);
		item.appendChild(mode);
	}

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

export function renderSidebar(state, derived, chrome = {}) {
	renderTabs(state.tab);
	renderModes(state.mode, state.tab, state.index);
	syncSearchInput(state.search);
	renderList(derived.rows, derived.listState, {
		search: state.search,
		emptyKey: derived.emptyKey,
		selectedId: state.selectedId,
		canEdit: chrome.canEdit,
		rowKind: chrome.rowKind,
		editableKinds: chrome.editableKinds,
		hideMode: state.mode !== MODE_ALL
	});
}

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
			handlers.onSelect(row.dataset.id);
		});
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
}

export function setBusy(busy) {
	if (el.refresh) {
		el.refresh.disabled = busy;
		el.refresh.classList.toggle('is-busy', busy);
	}
}
