import { t, f, applyTranslations } from './i18n.js?v=65';
import { SessionError, SessionFailure, clearTokenFromUrl, fetchSession, fetchStatus, login, logout, readTokenFromUrl } from './session.js?v=65';
import { DataErrorKind, DataFailure, fetchData, fetchMeta, patch, selectWorld } from './api.js?v=65';
import { MODE_ALL, buildIndex, buildRows, compareByDisplayName } from './lists.js?v=65';
import * as views from './views.js?v=65';
import * as map from './map.js?v=65';
import * as tracks from './tracks.js?v=65';
import * as trains from './trains.js?v=65';
import * as trainToast from './train-toast.js?v=65';
import * as popover from './popover.js?v=65';
import { CHILD_KINDS, contextFor } from './editor.js?v=65';
import { hasOriginCorner } from './areafit.js?v=65';
import * as welcome from './welcome.js?v=65';
import { initSettings } from './settings.js?v=65';
import { initStatistics } from './stats.js?v=65';
import * as overlayToast from './overlay-toast.js?v=65';
import { getTrainSettings, setTrainSetting } from './train-settings.js?v=65';
import './theme.js?v=65';
import './fx.js?v=65';

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
	signOut: document.getElementById('sign-out'),
	trainStatus: document.getElementById('map-trains-status')
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
let authenticated = false;
let editingArea = false;
let childFocusId = null;
let trainAnchor = null;
let mapCanvas = null;
let mapRect = null;
let rectListenerAttached = false;

function activeMapSelection() {
	return childFocusId || selectedId();
}

function setView(view) {
	views.setView(view);
	welcome.setView(view);
}

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
	const features = status && status.features ? status.features : {};
	const canSeeLive = authenticated && features.trains === true;
	tracks.setAvailable(authenticated && features.tracks === true);
	trains.setAvailable(canSeeLive);
	trains.setStreamAvailable(canSeeLive && features.trainStream === true && features.trainStreamVersion === 1);
	overlayToast.setTrainsAvailable(canSeeLive);
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

function setTrainStatus(state) {
	const node = elements.trainStatus;
	if (!node) {
		return;
	}
	const parts = [];
	if (state && state.enabled && state.available) {
		if (state.degraded) {
			parts.push(t('trainDegraded'));
		}
		if (state.stale) {
			parts.push(t('trainStale'));
		}
		if (state.truncated && state.total > state.rendered) {
			parts.push(f('trainShowing', state.rendered, state.total));
		} else if (state.total > 0) {
			parts.push(f('trainCount', state.total));
		}
	}
	const text = parts.join(' · ');
	node.hidden = text.length === 0;
	if (text && node.textContent !== text) {
		node.textContent = text;
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
			rowKind: kindForTab(state.tab),
			editableKinds: EDITABLE_KINDS
		});
		if (state.world) {
			views.setWorldLabel(state.world.dimension);
		}
		const playerCount = state.world && Array.isArray(state.world.players) ? state.world.players.length : 0;
		views.setFocusPlayerEnabled(playerCount > 0);
		tracks.setWorld(state.world);
		tracks.setMode(state.mode);
		trains.setWorld(state.world);
		trainToast.setIndex(state.index);
		if (state.world) {
			keepTrainFilterValid();
		}
		map.setWorld(state.world, state.index);
		map.setMode(state.mode);
		map.setTab(state.tab);
		map.setSelectedId(activeMapSelection());
	} catch (error) {
		console.error('[MTR-WebDashboard] render failed for state', { ...state }, error);
		throw error;
	}
}

function keepTrainFilterValid() {
	const settings = getTrainSettings();
	if (settings.route !== 'all' && !state.index.routeById.has(settings.route)) {
		setTrainSetting('route', 'all');
	}
	if (settings.depot !== 'all' && !state.index.depotById.has(settings.depot)) {
		setTrainSetting('depot', 'all');
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
	authenticated = false;
	tracks.setAvailable(false);
	trains.setAvailable(false);
	trains.setStreamAvailable(false);
	overlayToast.setTrainsAvailable(false);
	trainToast.hide();
	setStatus('readonly', 'statusReadOnly');
	setView('readonly');
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
		tracks: () => tracks.getDebugState(),
		trains: () => trains.getDebugState(),
		show: tab => {
			state.tab = tab;
			render();
		}
	};
}

async function showDashboard(session) {
	authenticated = true;
	setAccount(session.username || null);
	setStatus('ready', 'statusReady');
	canEdit = Boolean(session.canEdit);
	views.setCanEdit(canEdit);
	setView('dashboard');
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
	mapCanvas = canvas;
	mapRect = null;
	if (!rectListenerAttached && typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
		rectListenerAttached = true;
		window.addEventListener('resize', () => {
			mapRect = null;
		});
	}

	map.init(canvas, {
		trainCanvas: document.getElementById('map-trains'),
		onSelect: (id, detail) => {
			selectOnMap(id, detail);
		},
		onEditArea: area => {
			state.draftArea = area;
			refreshAreaPanel();
		},
		onDraftChange: draft => {
			state.draftArea = draft;
			refreshAreaPanel();
		},
		onPointerMove: point => {
			views.setMapReadout(point);
		},
		drawOverlay: (context, mapView, mapTheme) => {
			tracks.draw(context, mapView, mapTheme);
		},
		drawTrains: (context, mapView, mapTheme) => {
			trains.draw(context, mapView, mapTheme);
		},
		onViewChange: mapView => {
			tracks.onViewChange(mapView);
			trains.onViewChange(mapView);
		}
	});

	tracks.init({
		redraw: () => map.redraw(),
		onState: tracksState => {
			views.setTracksAvailable(tracksState.available);
			views.setTracksEnabled(tracksState.enabled);
			views.setTracksBusy(tracksState.busy);
			views.setTracksDegraded(tracksState.degraded);
		},
		onAuthFailure: () => {
			console.warn('[MTR-WebDashboard] the track overlay lost its session; returning to read-only');
			tracks.setAvailable(false);
			trains.setAvailable(false);
			overlayToast.setTrainsAvailable(false);
			trainToast.hide();
			renderReadOnly();
		}
	});

	trains.init({
		redraw: () => map.redraw(),
		redrawTrains: () => map.redrawTrains(),
		onState: state => setTrainStatus(state),
		onSelect: record => {
			if (!record) {
				trainToast.hide();
				return;
			}
			if (trainToast.isOpen() && trainToast.getTrainId() === record.id) {
				trainToast.update(record);
			} else {
				trainToast.show(record, trainAnchor);
			}
		},
		onTracking: record => {
			if (!record) {
				return;
			}
			if (trainToast.getTrainId() !== record.id) {
				trainToast.show(record, trainAnchor);
			}
			trainToast.update(record);
			trainToast.setTracked(true);
		},
		onTrackingLost: () => {
			trainToast.setTracked(false);
			trainToast.hide();
		},
		onTrackingMove: (x, y) => {
			if (!mapCanvas) {
				mapCanvas = document.getElementById('map');
			}
			if (!mapCanvas) {
				return;
			}
			if (!mapRect) {
				mapRect = mapCanvas.getBoundingClientRect();
			}
			trainToast.moveTo({ clientX: mapRect.left + x, clientY: mapRect.top + y });
		},
		focus: (x, z, scale, durationMs) => {
			map.flyTo({ ...map.getView(), centerX: x, centerZ: z, scale }, durationMs);
		},
		follow: (x, z) => map.followTo(x, z, 0.22),
		onAuthFailure: () => {
			console.warn('[MTR-WebDashboard] the train overlay lost its session; returning to read-only');
			trains.setAvailable(false);
			tracks.setAvailable(false);
			overlayToast.setTrainsAvailable(false);
			trainToast.hide();
			renderReadOnly();
		}
	});

	trainToast.init({
		onTrack: id => trains.track(id),
		onStop: () => trains.stopTracking(),
		onClose: () => {
			trains.stopTracking();
			trains.clearSelection();
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
	if (buttons.tracks) {
		buttons.tracks.setAttribute('aria-expanded', 'false');
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
		onFilter: (kind, id) => {
			if (!trains.isAvailable() || (kind !== 'route' && kind !== 'depot')) {
				return;
			}
			const settings = getTrainSettings();
			const key = kind === 'route' ? 'route' : 'depot';
			const other = kind === 'route' ? 'depot' : 'route';
			const next = settings[key] === String(id) ? 'all' : String(id);
			setTrainSetting('enabled', true);
			if (next !== 'all') {
				setTrainSetting(other, 'all');
			}
			setTrainSetting(key, next);
			render();
		},
		onRefresh: () => {
			tracks.invalidate();
			trains.invalidate();
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
	trainToast.hide();
	if (trains.isTracking()) {
		trains.stopTracking();
	}
	trains.clearSelection();

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

function selectOnMap(id, detail) {
	if (detail) {
		trainAnchor = detail;
	}
	if (detail && trains.trySelect(detail)) {
		return;
	}
	if (id) {
		trainToast.hide();
		if (trains.isTracking()) {
			trains.stopTracking();
		}
		trains.clearSelection();
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
	trainToast.hide();
	if (trains.isTracking()) {
		trains.stopTracking();
	}
	trains.clearSelection();
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
	if (childFocusId != null) {
		childFocusId = null;
		map.setChildFocusId(null);
	}

	const childKind = CHILD_KINDS[kind] || null;
	const plan = map.focusOnTarget(kind, object, state.index);
	popover.open({
		kind,
		object,
		context: contextFor(object, state.meta),
		meta: state.meta,
		childKind,
		childObjects: childKind ? childObjectsFor(kind, object) : [],
		anchor: plan.anchorScreen,
		container: document.getElementById('map-area'),
		onSubmit: (editKind, editId, fields) => patch(editKind, editId, fields),
		onSubmitChild: async (childKindId, childId, fields) => {
			const result = await patch(childKindId, childId, fields);
			updateObjectInWorld(childKindId, childId, result && result.object);
			map.refresh();
			return result;
		},
		onChildFocus: childId => {
			childFocusId = childId;
			map.setChildFocusId(childId);
			map.setSelectedId(activeMapSelection());
		},
		onRedrawArea: () => {
			startAreaEditing();
		},
		onSave: () => {
			childFocusId = null;
			map.setChildFocusId(null);
			loadData();
		},
		onClose: () => {
			childFocusId = null;
			map.setChildFocusId(null);
			map.setSelectedId(activeMapSelection());
		}
	});
}

/** @returns {Array<object>} the platforms of a station or the sidings of a depot, in display order. */
function childObjectsFor(kind, object) {
	const childKind = CHILD_KINDS[kind];
	if (!childKind || !state.world) {
		return [];
	}

	const byId = childKind === 'platform' ? state.index.platformById : state.index.sidingById;
	const ids = childKind === 'platform'
		? (Array.isArray(object.platformIds) ? object.platformIds : [])
		: (((state.world.depotIdToSidingIds || {})[object.id]) || []);

	return ids
		.map(id => byId.get(id))
		.filter(Boolean)
		.sort(compareByDisplayName);
}

/**
 * Replaces the cached object in place after a child edit, so the card, the map labels and the sidebar all read
 * the same instance. Rebuilding the whole dataset here would discard whatever the card's other rows hold.
 */
function updateObjectInWorld(kind, id, updated) {
	if (!updated || !state.world) {
		return;
	}
	const collection = kind === 'platform' ? state.world.platforms : kind === 'siding' ? state.world.sidings : null;
	const existing = (collection || []).find(entry => entry.id === id);
	if (existing) {
		Object.assign(existing, updated);
	}
}

function cancelAreaEditingIfNeeded() {
	if (editingArea) {
		stopAreaEditing();
	}
}

function returnHome() {
	if (document.body.dataset.view !== 'dashboard') {
		return;
	}
	cancelAreaEditingIfNeeded();
	popover.close();
	setView('home');
}

function enterDashboard() {
	if (document.body.dataset.view !== 'home') {
		return;
	}
	setView('dashboard');
	render();
	requestAnimationFrame(() => {
		map.redraw();
	});
}

function bindViewSwitch() {
	const homeButton = document.getElementById('home');
	if (homeButton) {
		homeButton.addEventListener('click', returnHome);
	}
	const enterButton = document.getElementById('welcome-enter');
	if (enterButton) {
		enterButton.addEventListener('click', enterDashboard);
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
	welcome.init();
	initSettings();
	initStatistics({
		getData: () => ({ world: state.world, index: state.index })
	});
	overlayToast.initOverlayToast();
	setStatus('loading', 'statusLoading');
	bindSidebar();
	bindViewSwitch();
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
				setView('expired');
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
	setView('offline');
}

main();
