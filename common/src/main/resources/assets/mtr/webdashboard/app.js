import { t, applyTranslations } from './i18n.js?v=16';
import { SessionError, SessionFailure, clearTokenFromUrl, fetchSession, fetchStatus, login, logout, readTokenFromUrl } from './session.js?v=16';
import { DataErrorKind, DataFailure, fetchData, fetchMeta, patch, selectWorld } from './api.js?v=16';
import { MODE_ALL, buildIndex, buildRows } from './lists.js?v=16';
import * as views from './views.js?v=16';
import * as map from './map.js?v=16';
import * as popover from './popover.js?v=16';

/**
 * The kinds the server accepts edits for. Must match WebDashboardFields.knownKinds on the Java side - a
 * mismatch shows up as an edit button that always fails, so the two are named here rather than derived.
 */
const EDITABLE_KINDS = new Set(['station', 'route', 'depot']);

/**
 * Maps a sidebar tab to the resource kind it shows.
 *
 * The two vocabularies differ and the difference matters: a tab is named for its collection ("stations"),
 * a resource for one object ("station"). Comparing them directly - which is what the first version of the
 * edit-button guard did - never matches, so the buttons simply never appear while everything else looks
 * fine. This is the one place the translation happens.
 *
 * @returns {string|null} the kind, or null for a tab that is not editable.
 */
function kindForTab(tab) {
	return tab === 'stations' ? 'station' : tab === 'routes' ? 'route' : tab === 'depots' ? 'depot' : null;
}

/*
 * The dashboard: a sidebar of stations, routes and depots beside a canvas map.
 *
 * The sidebar came first (stage 3.2) and its filter rule - stations are never filtered, because the
 * model makes them shared across all four transport modes - lives in lists.js with the reasoning.
 *
 * Stage 3.3 adds the map. Its geometry is in mapview.js and its area-editing state machine in
 * mapedit.js, both pure and both covered by tests that need no browser; map.js is left with only the
 * canvas work. No terrain is drawn: that is a deliberate choice for this stage, and map.js keeps a
 * single function to replace when server-side terrain arrives.
 *
 * Editing is not saved yet. Drawing a new selection for a station or depot produces a draft that is
 * shown on the map and held in memory; stage 3.4 adds the confirm step and the request that persists it.
 *
 * The view shown depends on the session, not just on whether data loaded:
 *   not signed in        -> the read-only "you cannot edit" card
 *   signed in            -> the dashboard, with a read-only badge when the account cannot edit
 *
 * Every import carries the same ?v= string as index.html. Sub-imports are cached under their own
 * URL, so a page-level cache-buster alone would still leave a stale lists.js or views.js in play -
 * which is exactly how a fixed bug can appear to persist. They are written as literals rather than
 * built from a constant because a static import specifier cannot be a template, so changing the
 * version means editing this block, views.js's imports, map.js's imports and index.html together.
 */


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

/** The single mutable state. Everything the sidebar and the map render from. */
const state = {
	tab: 'stations',
	mode: MODE_ALL,
	search: '',
	/**
	 * The one thing the visitor has picked, as {@code {kind, id}}, or null.
	 *
	 * Stage 3.3 had a separate `selectedId` for the map highlight, and stage 3.4 would have wanted its own
	 * for the editing card - which is exactly how "the card shows A while the map highlights B" happens.
	 * There is one field now, and the list highlight, the map highlight and the card are all derived from
	 * it. {@link selectedId} reads it rather than being a second copy.
	 */
	editing: null,
	world: null,
	index: buildIndex(null),
	meta: null
};

/** @returns {string|null} the selected object's id, for the parts that only care about identity. */
function selectedId() {
	return state.editing ? state.editing.id : null;
}

/** @returns {string|null} the selected object's kind, for the parts that resolve it back to a payload. */
function selectedKind() {
	return state.editing ? state.editing.kind : null;
}

/** Set once the sign-in response is known, so the map can be initialised with the right affordances. */
let canEdit = false;

/**
 * Whether an area is currently being drawn on the map.
 *
 * Kept here rather than asked of the map on every render, because the sidebar's button has to disappear the
 * moment an edit begins - it is the control that began the edit, and leaving it in place after Cancel left a
 * button that did nothing.
 */
let editingArea = false;

// ---- session and service chrome ------------------------------------------

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
	// Surfaced in the page and not just the log: turning this on is what puts sign-in tokens on the
	// wire in clear text, and whoever is reading the page is the one who can change it.
	if (elements.lanWarning) {
		elements.lanWarning.hidden = !status.lanAccess;
	}
}

async function refreshServiceMeta() {
	try {
		setServiceMeta(await fetchStatus());
	} catch (error) {
		// Deliberately not fatal: the version and port are decoration, and the sidebar does not need them.
		console.warn('[MTR-WebDashboard] Could not read service status:', error);
	}
}

// ---- derivation ----------------------------------------------------------

/**
 * Turns the state into everything the sidebar needs to draw itself.
 *
 * Kept out of views.js so that module stays a pure renderer, and out of lists.js so the filter rules
 * there remain independent of presentation.
 */
function derive() {
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

	// Every row is handed over; the sidebar scrolls. No slicing, no page arithmetic.
	return { rows, totalRows, listState, emptyKey };
}

function render() {
	try {
		const derived = derive();
		views.renderSidebar(state, derived, {
			canEdit,
			editAreaId: getEditableAreaId(),
			editingArea,
			editableKinds: EDITABLE_KINDS
		});
		if (state.world) {
			views.setWorldLabel(state.world.dimension);
			// Only one world is shown at a time, so the focus button follows whichever one is loaded.
			views.setFocusPlayerEnabled(Boolean(map.getFirstPlayer()));
		}
		// The map keeps its own view and edit state; it only needs to be told what changed.
		map.setWorld(state.world, state.index);
		map.setMode(state.mode);
		map.setTab(state.tab);
		map.setSelectedId(selectedId());
		console.debug('[MTR-WebDashboard] render', {
			tab: state.tab,
			mode: state.mode,
			search: state.search,
			listState: derived.listState,
			rows: derived.totalRows
		});
	} catch (error) {
		// A throw here used to leave the previous render on screen with no sign that anything failed,
		// which reads as "the buttons stopped working". Now it is visible and the state is inspectable.
		console.error('[MTR-WebDashboard] render failed for state', { ...state }, error);
		throw error;
	}
}

/**
 * @returns {string|null} the id of the selected object when it has a drawable area, so the sidebar can
 *          offer to redraw it.
 *
 * Only stations and depots have selections; routes, platforms and sidings do not. The map's own click
 * selection can point at a platform, which is deliberately not offered for editing here.
 */
function getEditableAreaId() {
	const kind = selectedKind();
	if (!canEdit || !state.world || (kind !== 'station' && kind !== 'depot')) {
		return null;
	}
	const found = resolveSelected();
	return found ? found.id : null;
}

/** @returns {object|null} the payload of whatever is selected, or null when it is gone. */
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

/** @returns {object|null} the station or depot currently selected, when it can be edited. */
function getEditableArea() {
	const kind = selectedKind();
	if (kind !== 'station' && kind !== 'depot') {
		return null;
	}
	return resolveSelected();
}

// ---- data loading --------------------------------------------------------

async function loadData() {
	views.setBusy(true);
	try {
		// Meta is fetched alongside rather than before: it only supplies the untitled fallback and the
		// behaviour flags, and waiting on it would delay the list for no benefit. A failure there is
		// tolerated, hence the catch that turns it into null.
		const [data, meta] = await Promise.all([fetchData(), fetchMeta().catch(() => null)]);
		state.meta = meta;
		state.world = selectWorld(data);
		state.index = buildIndex(state.world);
		// The selection is deliberately KEPT across a reload. Saving reloads the data, and dropping the
		// selection there would close the card and scroll the list back to the top after every save - which
		// is precisely when the visitor wants to still be looking at the thing they just changed.
		keepSelectionIfStillPresent();
		// Logged because an empty sidebar is usually a data problem rather than a rendering one, and the
		// counts say which immediately.
		console.info('[MTR-WebDashboard] payload received', {
			worlds: (data.worlds || []).length,
			dimension: state.world ? state.world.dimension : null,
			counts: state.world ? state.world.counts : null,
			meta: meta ? 'ok' : 'unavailable'
		});
		render();
	} catch (error) {
		if (error instanceof DataFailure && error.kind === DataErrorKind.AUTH) {
			// The session went stale under us, most likely revoked from in-game. Back to read-only rather
			// than an error page, because there is a clear way forward: sign in again from the game.
			console.warn('[MTR-WebDashboard] the session is no longer valid; returning to read-only');
			renderReadOnly();
			await refreshServiceMeta();
			return;
		}
		if (error instanceof DataFailure && error.kind === DataErrorKind.NO_SERVER) {
			// Distinct from an empty world: the service is up but no world is loaded.
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

/**
 * Drops the selection when the object behind it no longer exists.
 *
 * Something else may have deleted it - in game, or in another tab - so a reload can leave the card and the
 * map highlight pointing at nothing. Said out loud rather than silently cleared, because "the station I was
 * editing disappeared" is worth knowing.
 */
function keepSelectionIfStillPresent() {
	if (!state.editing) {
		return;
	}
	if (resolveSelected()) {
		return;
	}
	console.info('[MTR-WebDashboard] the edited object no longer exists:', state.editing);
	state.editing = null;
	closeEditor();
}

// ---- view selection ------------------------------------------------------

function renderReadOnly() {
	setStatus('readonly', 'statusReadOnly');
	views.setView('readonly');
}

/**
 * Exposes the live state for inspection from the browser console.
 *
 * Added because "the list shows the wrong thing" has several possible causes that look identical on
 * screen - a stale script, a filter that did not apply, a payload that arrived empty - and this makes
 * them distinguishable in one line without a rebuild:
 *
 *     mtrDashboard.state.tab          what the sidebar thinks it is showing
 *     mtrDashboard.counts()           rows per tab and per mode, from the same code the UI uses
 *     mtrDashboard.raw()              the payload the server actually sent
 */
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
	console.info('[MTR-WebDashboard] diagnostics ready. Try: mtrDashboard.counts()');
}

async function showDashboard(session) {
	setAccount(session.username || null);
	setStatus('ready', 'statusReady');
	canEdit = Boolean(session.canEdit);
	views.setCanEdit(canEdit);
	views.setView('dashboard');
	exposeDiagnostics(session);
	// Initialised after the view switch, because the canvas needs the shell to be laid out: while the
	// shell is display:none its measured size is zero and the first frame would be blank.
	initMap();
	await loadData();
}

/**
 * Wires the canvas and its overlay controls.
 *
 * Idempotent, because showDashboard can run again after a re-read of the session.
 */
function initMap() {
	const canvas = document.getElementById('map');
	if (!canvas || canvas.dataset.initialised === 'true') {
		return;
	}
	canvas.dataset.initialised = 'true';

	map.init(canvas, {
		onSelect: id => {
			// Clicking a saved rail on the map selects it here too, so the highlight and the sidebar agree.
			// No tab switch: the id may belong to a collection the sidebar is not showing, and jumping the
			// user to another list would lose their place.
			selectOnMap(id);
		},
		onEditArea: area => {
			// The draft is held by the map; this only records that one exists, so the UI can offer to
			// confirm it. Stage 3.4 turns this callback into the save request.
			console.info('[MTR-WebDashboard] area draft drawn', area);
			state.draftArea = area;
			views.setMapDraftSize(map.getAreaDraft());
		},
		onDraftChange: draft => {
			views.setMapDraftSize(draft);
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
			// The position is whatever the payload carried; nothing here tracks the player live.
			map.focusOnPlayer(map.getFirstPlayer());
		});
	}
	if (buttons.cancelEdit) {
		buttons.cancelEdit.addEventListener('click', () => stopAreaEditing());
	}

	// Escape leaves area editing, matching the hint in the editing bar.
	document.addEventListener('keydown', event => {
		if (event.key === 'Escape' && map.isAreaEditing()) {
			stopAreaEditing();
		}
	});
}

/** Begins redrawing the selected station or depot's area. */
function startAreaEditing() {
	const area = getEditableArea();
	if (!area) {
		return;
	}
	map.beginAreaEdit(state.tab === 'depots' ? 'depot' : 'station', area);
	editingArea = true;
	views.setMapEditing(true);
	views.setMapDraftSize(null);
	state.draftArea = null;
	// Re-rendered so the sidebar's own button goes away: the map owns the interaction from here, and leaving
	// the button in place invited a second edit on top of the first.
	render();
	console.info('[MTR-WebDashboard] area editing started for', area.id, area.name);
}

/** Leaves area editing and throws the draft away. */
function stopAreaEditing() {
	map.cancelAreaEdit();
	editingArea = false;
	views.setMapEditing(false);
	views.setMapDraftSize(null);
	state.draftArea = null;
	render();
	console.info('[MTR-WebDashboard] area editing cancelled');
}

// ---- wiring --------------------------------------------------------------

function bindSidebar() {
	views.bindSidebar({
		onTab: tab => {
			console.debug('[MTR-WebDashboard] tab clicked:', tab, '(was', state.tab + ')');
			if (state.tab === tab) {
				return;
			}
			cancelAreaEditingIfNeeded();
			// The selection is dropped because it may belong to a collection this tab does not show, which
			// would leave an invisible highlight and a card for something no longer listed.
			clearSelection();
			state.tab = tab;
			render();
		},
		onMode: mode => {
			console.debug('[MTR-WebDashboard] mode clicked:', mode, '(was', state.mode + ')');
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
		},
		onEditArea: () => {
			startAreaEditing();
		}
	});
}

/**
 * Handles a click on a list row or its edit button.
 *
 * Both do the same thing by design - the button is the discoverable affordance and the row is the
 * convenient one - so they share this path rather than being two implementations that can drift apart.
 * Clicking the already-selected row closes the card, which is the obvious way to dismiss it.
 */
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
	// Unsaved work in the previous card is confirmed before it is replaced, not silently dropped.
	if (!closeEditor()) {
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

/** Handles a click on the map, which selects a saved rail and never opens a card. */
function selectOnMap(id) {
	if (id) {
		const kind = kindForId(id);
		if (kind === 'platform' || kind === 'siding') {
			state.editing = { kind, id };
		} else {
			state.editing = null;
		}
	} else {
		// Empty map. Closing the editor is part of the gesture, so the unsaved-changes question applies.
		clearSelection();
	}
	render();
}

/** Clears the selection and closes the card, asking first when there is unsaved work. */
function clearSelection() {
	if (!closeEditor()) {
		return false;
	}
	state.editing = null;
	return true;
}

/**
 * Resolves an id to the kind of object it belongs to, by looking it up in the loaded world.
 *
 * The ids come from three collections and are opaque, so this is a search rather than a decode. It is a
 * handful of `find` calls over arrays the page already holds, which is cheaper than threading the kind
 * through every row and map callback.
 */
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

// ---- the editing card ----------------------------------------------------

/**
 * Flies the map to the object and opens its card.
 *
 * The order matters: the anchor the card is placed against is computed for the view the map is flying TO,
 * so the card appears beside the object rather than beside wherever it used to be.
 */
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
		onSave: () => {
			// A full reload rather than patching the local copy: the change may have moved other things with
			// it (the server reports that in `warnings`), and a refetch is the honest answer to "is my copy
			// right". It also keeps the revision and the list-derived counts consistent.
			loadData();
		},
		confirmDiscard: () => window.confirm(t('editDiscardChanges')),
		onClose: () => {
			// The card is gone, so the object is no longer being edited. The selection is kept: the map
			// highlight and the list selection are still useful after the card is dismissed.
			console.info('[MTR-WebDashboard] editor closed');
		}
	});
}

/**
 * Closes the card, asking about unsaved changes.
 *
 * @returns {boolean} whether the card is now closed. False means the visitor chose to keep editing, and the
 *          caller must abort whatever it was about to do.
 */
function closeEditor() {
	return popover.close();
}

/** @returns {boolean} whether an editing card is open. */
function isEditorOpen() {
	return popover.isOpen();
}

/**
 * Ends an in-progress edit when the selection it belongs to is about to change.
 *
 * Called before the state changes rather than after, so the flag and the map agree by the time the next
 * render reads them.
 */
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
		// Disabled first so a double click cannot fire two sign-outs and leave the button looking live
		// while the first request is still in flight.
		elements.signOut.disabled = true;
		if (await logout()) {
			window.location.reload();
		} else {
			// Left on the dashboard rather than reloading: a reload would still be signed in and would read
			// as the button doing nothing.
			elements.signOut.disabled = false;
			elements.signOut.textContent = t('signOutFailed');
		}
	});
}

// ---- startup -------------------------------------------------------------

async function main() {
	applyTranslations(document);
	setStatus('loading', 'statusLoading');
	bindSidebar();
	await bindSignOut();

	const token = readTokenFromUrl();
	// Logged because "the page still says read-only" has several causes that look identical on screen:
	// the fragment never arrived, the server refused the token, or a cached script is still running.
	// The token itself is never logged, only whether one was present.
	console.info('[MTR-WebDashboard] fragment token present:', token !== null, '| script build: stage3.2');

	if (token) {
		try {
			const session = await login(token);
			clearTokenFromUrl();
			console.info('[MTR-WebDashboard] sign-in accepted:', session);
			// Re-read through the ordinary path so what is displayed does not depend on the sign-in
			// response alone.
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

/**
 * Shows whatever the session permits. The only branch is signed in or not: a signed-in account
 * without edit rights still gets the dashboard, with a read-only badge.
 */
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
