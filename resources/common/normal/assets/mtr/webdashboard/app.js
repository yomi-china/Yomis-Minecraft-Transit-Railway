import { t, applyTranslations } from './i18n.js?v=20';
import { SessionError, SessionFailure, clearTokenFromUrl, fetchSession, fetchStatus, login, logout, readTokenFromUrl } from './session.js?v=20';
import { DataErrorKind, DataFailure, fetchData, fetchMeta, patch, selectWorld } from './api.js?v=20';
import { MODE_ALL, buildIndex, buildRows } from './lists.js?v=20';
import * as views from './views.js?v=20';
import * as map from './map.js?v=20';
import * as popover from './popover.js?v=20';
import { hasOriginCorner } from './areafit.js?v=20';

const EDITABLE_KINDS = new Set(['station', 'route', 'depot']);

function kindForTab(tab) {
	return tab === 'stations' ? 'station' : tab === 'routes' ? 'route' : tab === 'depots' ? 'depot' : null;
}

const elements = {
	statusBadge: document.getElementById('status-badge'),
	statusLabel: document.querySelector('#status-badge [data-i18n]'),
	accountBadge: document.getElementById('account-badge'),
	accountName: document.getElementById('account-name'),
	meta: document.getElementById('meta'),
	version: document.getElementById('meta-version'),
	port: document.getElementById('meta-port'),
	lanWarning: document.getElementById('lan-warning'),
	signOut: document.getElementById('sign-out')
};

const state = {
	tab: 'stations',
	mode: MODE_ALL,
	search: '',
	editing: null,
	world: null,
	index: buildIndex(null),
	meta: null
};

function selectedId() {
	return state.editing ? state.editing.id : null;
}

function selectedKind() {
	return state.editing ? state.editing.kind : null;
}

let canEdit = false;
let editingArea = false;

function setStatus(stateName, labelKey) {
	if (elements.statusBadge) {
		elements.statusBadge.dataset.state = stateName;
	}
	if (elements.statusLabel) {
		elements.statusLabel.textContent = t(labelKey);
	}
}

function setAccount(username) {
	if (!elements.accountBadge || !elements.accountName) {
		return;
	}
	elements.accountName.textContent = username || '';
	elements.accountBadge.hidden = !username;
}

function setServiceMeta(status) {
	if (!elements.meta) {
		return;
	}
	elements.meta.hidden = false;
	if (elements.version) {
		elements.version.textContent = status.version || '—';
	}
	if (elements.port) {
		elements.port.textContent = status.port != null ? String(status.port) : '—';
	}
	if (elements.lanWarning) {
		elements.lanWarning.hidden = !status.lanAccess;
	}
}

async function refreshServiceMeta() {
	try {
		setServiceMeta(await fetchStatus());
	} catch (error) {
		console.warn('[MTR-WebDashboard] Could not read service status:', error);
	}
}

function render() {
	try {
		const rows = buildRows(state.world, state.index, state.tab, state.mode, state.search);
		const totalRows = rows.length;
		let listState = 'ready';
		let emptyKey = null;
		if (!state.world) {
			listState = 'unavailable';
		} else if (totalRows === 0 && state.search) {
			listState = 'noresults';
		} else if (totalRows === 0) {
			listState = 'empty';
			emptyKey = state.tab === 'routes' ? 'emptyRoutes' : state.tab === 'depots' ? 'emptyDepots' : 'emptyStations';
		}
		const derived = { rows, totalRows, listState, emptyKey };

		views.renderSidebar(state, derived, {
			canEdit,
			editableKinds: EDITABLE_KINDS
		});
		if (state.world) {
			views.setWorldLabel(state.world.dimension);
		}
		const playerCount = state.world && Array.isArray(state.world.players) ? state.world.players.length : 0;
		views.setFocusPlayerEnabled(playerCount > 0);
		map.setWorld(state.world, state.index);
		map.setMode(state.mode);
		map.setTab(state.tab);
		map.setSelectedId(selectedId());
	} catch (error) {
		console.error('[MTR-WebDashboard] render failed for state', { ...state }, error);
		throw error;
	}
}

function resolveSelected() {
	const kind = selectedKind();
	const id = selectedId();
	if (!kind || !id || !state.world) {
		return null;
	}
	const collection = kind === 'station' ? state.world.stations
		: kind === 'depot' ? state.world.depots
			: kind === 'route' ? state.world.routes
				: kind === 'platform' ? state.world.platforms
					: kind === 'siding' ? state.world.sidings
						: null;
	return (collection || []).find(candidate => candidate.id === id) || null;
}

function getEditableArea() {
	const kind = selectedKind();
	if (kind !== 'station' && kind !== 'depot') {
		return null;
	}
	return resolveSelected();
}

let metaPromise = null;

function fetchMetaOnce() {
	if (!metaPromise) {
		metaPromise = fetchMeta().catch(error => {
			console.warn('[MTR-WebDashboard] could not read the service metadata:', error);
			metaPromise = null;
			return null;
		});
	}
	return metaPromise;
}

async function loadData() {
	views.setBusy(true);
	try {
		const [data, meta] = await Promise.all([fetchData(), fetchMetaOnce()]);
		state.meta = meta;
		state.world = selectWorld(data);
		state.index = buildIndex(state.world);
		keepSelectionIfStillPresent();
		render();
	} catch (error) {
		if (error instanceof DataFailure && error.kind === DataErrorKind.AUTH) {
			console.warn('[MTR-WebDashboard] the session is no longer valid; returning to read-only');
			renderReadOnly();
			await refreshServiceMeta();
			return;
		}
		if (error instanceof DataFailure && error.kind === DataErrorKind.NO_SERVER) {
			state.world = null;
			state.index = buildIndex(null);
			render();
			return;
		}
		console.warn('[MTR-WebDashboard] could not read the railway data:', error);
		views.renderSidebar(state, { rows: [], totalRows: 0, listState: 'failed' });
	} finally {
		views.setBusy(false);
	}
}

function keepSelectionIfStillPresent() {
	if (!state.editing) {
		return;
	}
	if (resolveSelected()) {
		return;
	}
	console.info('[MTR-WebDashboard] the edited object no longer exists:', state.editing);
	state.editing = null;
	popover.close();
}

function renderReadOnly() {
	setStatus('readonly', 'statusReadOnly');
	views.setView('readonly');
}

function exposeDiagnostics(session) {
	window.mtrDashboard = {
		state,
		session: () => ({ ...session }),
		counts: () => {
			const perTab = {};
			['stations', 'routes', 'depots'].forEach(tab => {
				perTab[tab] = buildRows(state.world, state.index, tab, state.mode, state.search).length;
			});
			return {
				tab: state.tab,
				mode: state.mode,
				search: state.search,
				perTab,
				modes: {
					routes: Object.fromEntries(state.index.modeCounts.routes),
					depots: Object.fromEntries(state.index.modeCounts.depots)
				}
			};
		},
		raw: () => state.world,
		map: () => ({ view: map.getView(), editing: map.isAreaEditing(), draft: map.getAreaDraft() }),
		show: tab => {
			state.tab = tab;
			render();
		}
	};
}

async function showDashboard(session) {
	setAccount(session.username || null);
	setStatus('ready', 'statusReady');
	canEdit = Boolean(session.canEdit);
	views.setCanEdit(canEdit);
	views.setView('dashboard');
	exposeDiagnostics(session);
	initMap();
	await loadData();
}

function initMap() {
	const canvas = document.getElementById('map');
	if (!canvas || canvas.dataset.initialised === 'true') {
		return;
	}
	canvas.dataset.initialised = 'true';

	map.init(canvas, {
		onSelect: id => {
			selectOnMap(id);
		},
		onEditArea: area => {
			state.draftArea = area;
			if (popover.hasSelectionField()) {
				popover.setSelection(area);
			}
			refreshAreaPanel();
		},
		onDraftChange: draft => {
			state.draftArea = draft;
			refreshAreaPanel();
		},
		onPointerMove: point => {
			views.setMapReadout(point);
		}
	});

	wireMapControls();
	views.setMapEditing(false);
	views.setMapDraftSize(null);
	views.setMapReadout(null);
}

function refreshAreaPanel() {
	const bounds = toBounds(state.draftArea);
	if (!bounds) {
		views.setMapDraftSize(null);
		return;
	}

	views.setMapDraftSize({
		...bounds,
		invalid: hasOriginCorner({ x: bounds.minX, z: bounds.minZ }, { x: bounds.maxX, z: bounds.maxZ })
	});
}

function toBounds(draft) {
	if (!draft) {
		return null;
	}
	if (Number.isFinite(draft.minX) && Number.isFinite(draft.minZ) && Number.isFinite(draft.maxX) && Number.isFinite(draft.maxZ)) {
		return { minX: draft.minX, minZ: draft.minZ, maxX: draft.maxX, maxZ: draft.maxZ };
	}
	if (draft.corner1 && draft.corner2) {
		return {
			minX: Math.min(draft.corner1.x, draft.corner2.x),
			minZ: Math.min(draft.corner1.z, draft.corner2.z),
			maxX: Math.max(draft.corner1.x, draft.corner2.x),
			maxZ: Math.max(draft.corner1.z, draft.corner2.z)
		};
	}
	return null;
}

function wireMapControls() {
	const buttons = views.getMapButtons();
	if (buttons.zoomIn) {
		buttons.zoomIn.addEventListener('click', () => map.zoomBy(map.ZOOM_STEP));
	}
	if (buttons.zoomOut) {
		buttons.zoomOut.addEventListener('click', () => map.zoomBy(1 / map.ZOOM_STEP));
	}
	if (buttons.focusPlayer) {
		buttons.focusPlayer.addEventListener('click', () => {
			const players = state.world && Array.isArray(state.world.players) ? state.world.players : [];
			map.focusOnPlayer(players.length > 0 ? players[0] : null);
		});
	}
	if (buttons.cancelEdit) {
		buttons.cancelEdit.addEventListener('click', () => {
			stopAreaEditing();
		});
	}
	if (buttons.saveEdit) {
		buttons.saveEdit.addEventListener('click', () => saveAreaDraft());
	}

	document.addEventListener('keydown', event => {
		if (event.key === 'Escape' && map.isAreaEditing()) {
			stopAreaEditing();
		}
	});
}

function startAreaEditing() {
	const area = getEditableArea();
	if (!area) {
		return;
	}

	popover.close();

	map.beginAreaEdit(state.tab === 'depots' ? 'depot' : 'station', area);
	editingArea = true;
	views.setMapEditing(true);
	views.setMapDraftSize(null);
	state.draftArea = null;
	render();
}

function stopAreaEditing(options) {
	const opts = options || {};
	map.cancelAreaEdit();
	editingArea = false;
	views.setMapEditing(false);
	views.setMapDraftSize(null);
	state.draftArea = null;
	render();

	if (!opts.silent && state.editing && canEdit && EDITABLE_KINDS.has(state.editing.kind)) {
		openEditor(state.editing.kind, state.editing.id);
	}
}

async function saveAreaDraft() {
	const bounds = toBounds(state.draftArea);
	const target = state.editing;
	if (!bounds || !target || !editingArea) {
		return;
	}

	views.setMapSaveEnabled(false);
	try {
		await patch(target.kind, target.id, {
			corners: { corner1: { x: bounds.minX, z: bounds.minZ }, corner2: { x: bounds.maxX, z: bounds.maxZ } }
		});

		map.cancelAreaEdit();
		editingArea = false;
		views.setMapEditing(false);
		views.setMapDraftSize(null);
		state.draftArea = null;
		await loadData();

		if (state.editing) {
			openEditor(state.editing.kind, state.editing.id);
		}
	} catch (error) {
		views.setMapSaveEnabled(true);
		console.warn('[MTR-WebDashboard] could not save the selection:', error);
		window.alert(t('editSaveFailed'));
	}
}

function bindSidebar() {
	views.bindSidebar({
		onTab: tab => {
			if (state.tab === tab) {
				return;
			}
			cancelAreaEditingIfNeeded();
			clearSelection();
			state.tab = tab;
			render();
		},
		onMode: mode => {
			if (state.mode === mode) {
				return;
			}
			cancelAreaEditingIfNeeded();
			clearSelection();
			state.mode = mode;
			render();
		},
		onSearch: search => {
			state.search = search;
			render();
		},
		onSelect: id => {
			selectFromList(id);
		},
		onRefresh: () => {
			loadData();
		}
	});
}

function selectFromList(id) {
	if (!id) {
		return;
	}
	if (selectedId() === id) {
		clearSelection();
		render();
		return;
	}

	cancelAreaEditingIfNeeded();
	if (!popover.close()) {
		return;
	}

	const kind = kindForId(id);
	if (!kind) {
		return;
	}
	state.editing = { kind, id };
	render();
	if (canEdit && EDITABLE_KINDS.has(kind)) {
		openEditor(kind, id);
	}
}

function selectOnMap(id) {
	if (id) {
		map.setSelectedId(id);
		return;
	}
	clearSelection();
	render();
}

function clearSelection() {
	if (!popover.close()) {
		return false;
	}
	state.editing = null;
	return true;
}

function kindForId(id) {
	if (!state.world || !id) {
		return null;
	}
	const collections = [['platform', state.world.platforms], ['station', state.world.stations], ['route', state.world.routes], ['depot', state.world.depots], ['siding', state.world.sidings]];
	for (const [kind, collection] of collections) {
		if ((collection || []).some(candidate => candidate.id === id)) {
			return kind;
		}
	}
	return null;
}

function openEditor(kind, id) {
	const object = resolveSelected();
	if (!object) {
		return;
	}

	const plan = map.focusOnTarget(kind, object, state.index);
	popover.open({
		kind,
		object,
		anchor: plan.anchorScreen,
		container: document.getElementById('map-area'),
		onSubmit: (editKind, editId, fields) => patch(editKind, editId, fields),
		onRedrawArea: () => {
			startAreaEditing();
		},
		onSave: () => {
			loadData();
		}
	});
}

function cancelAreaEditingIfNeeded() {
	if (editingArea) {
		stopAreaEditing();
	}
}

async function bindSignOut() {
	if (!elements.signOut) {
		return;
	}
	elements.signOut.addEventListener('click', async () => {
		elements.signOut.disabled = true;
		if (await logout()) {
			window.location.reload();
		} else {
			elements.signOut.disabled = false;
			elements.signOut.textContent = t('signOutFailed');
		}
	});
}

async function main() {
	applyTranslations(document);
	setStatus('loading', 'statusLoading');
	bindSidebar();
	await bindSignOut();

	const token = readTokenFromUrl();

	if (token) {
		try {
			const session = await login(token);
			clearTokenFromUrl();
			await enter(await fetchSession());
			return;
		} catch (error) {
			clearTokenFromUrl();
			if (error instanceof SessionFailure && error.kind === SessionError.REJECTED) {
				console.warn('[MTR-WebDashboard] the server refused the sign-in token:', error.message);
				setStatus('offline', 'statusOffline');
				views.setView('expired');
				await refreshServiceMeta();
				return;
			}
			console.warn('[MTR-WebDashboard] sign-in could not complete:', error);
			renderOffline();
			return;
		}
	}

	try {
		await enter(await fetchSession());
	} catch (error) {
		renderOffline();
	}
}

async function enter(session) {
	if (session.authenticated) {
		await showDashboard(session);
	} else {
		renderReadOnly();
	}
	await refreshServiceMeta();
}

function renderOffline() {
	setStatus('offline', 'statusOffline');
	views.setView('offline');
}

main();
