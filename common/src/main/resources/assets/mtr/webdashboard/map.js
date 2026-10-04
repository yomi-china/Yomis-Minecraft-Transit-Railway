import {
	COLOR_BACKGROUND,
	COLOR_PLAYER_MARKER,
	argbToRgba,
	centerOn,
	clampScale,
	createView,
	fitBounds,
	gridSpacing,
	hitTestSavedRails,
	isAreaVisible,
	outlineWorldWidth,
	panByScreenDelta,
	rgbToCss,
	savedRailSlots,
	screenToWorld,
	shouldDrawAreaLabel,
	shouldDrawSavedRailLabel,
	snapRect,
	visibleWorldBounds,
	worldToScreen,
	AREA_CORNER_RADIUS_PX,
	SAVED_RAIL_CORNER_RADIUS_PX,
	clampCornerRadius,
	isSaneArea,
	isSanePoint,
	snapAreaCorners,
	FLY_DURATION_MS,
	interpolateView
} from './mapview.js?v=16';
import { beginEdit, cancelEdit, dragToEdit, endEditDrag, getDraftBounds, hasDraft, isEditing } from './mapedit.js?v=16';
import { planFocus, sameView } from './focus.js?v=16';

/*
 * The dashboard map: a canvas that draws selections and saved rails on a flat top-down grid.
 *
 * The drawing maths lives in mapview.js and the editing state machine in mapedit.js, both pure and both
 * covered by tests that need no browser. What is left here is the part that genuinely needs a canvas:
 * sizing, the draw calls, and pointer handling.
 *
 * No terrain is drawn. The layer order below reserves a slot for it - see drawTerrain.
 *
 * The game puts its map on the right of the dashboard and its list on the left; this keeps that
 * arrangement, so the two read the same way round.
 */

/** Drag distance in pixels before a press counts as a drag rather than a click. */
const DRAG_THRESHOLD_PX = 4;

/**
 * Scale multiplier per wheel notch. Exported so the + and − buttons step by the same amount the wheel
 * does, instead of the two drifting apart.
 *
 * Below the game's doubling: a web map is scrolled far more freely than an in-game panel, and doubling per
 * notch overshoots badly. 1.5 lands within a few notches of the target zoom instead of leaping past it.
 */
export const ZOOM_STEP = 1.5;

/**
 * Share of the remaining zoom distance covered per frame.
 *
 * Lower is slower. Tuned so a single notch takes roughly a third of a second to settle - brisk enough not
 * to feel laggy, slow enough to follow. Raise it towards 0.5 to make the zoom snappier.
 */
const ZOOM_EASING_FACTOR = 0.14;

/** Stop when this little of the zoom remains, so the animation terminates rather than approaching forever. */
const ZOOM_SETTLE_RATIO = 0.002;

/** Ring colour for the selected saved rail, so it is findable when several share a block. */
const SELECTION_RING_COLOUR = argbToRgba(COLOR_PLAYER_MARKER);

const el = {
	canvas: null,
	pointer: null
};

/** Everything the map draws from. Replaced wholesale on a data reload. */
const mapState = {
	world: null,
	index: null,
	/** 'all' or a transport mode id; filters which saved rails are drawn. */
	mode: 'all',
	/** Which sidebar tab is active; decides whether platforms or sidings are drawn. */
	tab: 'stations',
	selectedId: null,
	/** True while a sidebar entry has been picked and the user asked to redraw its area. */
	edit: null
};

const view = createView();

/** Saved rails grouped by their block, rebuilt when the world or filter changes. */
let savedRailGroups = [];

let handlers = {};
let context = null;
let animation = null;
let pointerState = null;
let needsResize = true;
let viewHasBeenPlaced = false;
/** Scale the zoom animation is heading for, or null when no zoom is in flight. */
let zoomTargetScale = null;
/** Screen position the zoom is anchored to. */
let zoomAnchorScreenX = 0;
let zoomAnchorScreenY = 0;
/** Frame handle for a flight to an object, or null. Separate from {@link animation}, which drives the zoom. */
let flightAnimation = null;

// ---- lifecycle ----------------------------------------------------------

/**
 * @param {HTMLCanvasElement} canvas
 * @param {object} callbacks
 * @param {(id: string|null) => void} callbacks.onSelect       a saved rail was clicked, or null for empty space
 * @param {(area: {corner1: object, corner2: object}) => void} callbacks.onEditArea  a draft was finished
 * @param {(point: {x: number, z: number}|null) => void} callbacks.onPointerMove     world position, for the readout
 * @param {(view: object) => void} callbacks.onViewChange    centre or scale changed, for the readout
 */
export function init(canvas, callbacks) {
	el.canvas = canvas;
	handlers = callbacks || {};
	context = canvas.getContext('2d');

	el.pointer = { x: 0, z: 0, valid: false };

	// ResizeObserver rather than window.resize: the map area also changes when the sidebar wraps, which
	// window resize events do not report.
	const observer = new ResizeObserver(() => {
		needsResize = true;
	});
	observer.observe(canvas.parentElement || canvas);

	canvas.addEventListener('pointerdown', onPointerDown);
	canvas.addEventListener('pointermove', onPointerMove);
	canvas.addEventListener('pointerup', onPointerUp);
	canvas.addEventListener('pointercancel', onPointerUp);
	canvas.addEventListener('pointerleave', () => {
		el.pointer.valid = false;
		notifyPointer();
		redraw();
	});
	canvas.addEventListener('wheel', onWheel, { passive: false });
	canvas.addEventListener('contextmenu', event => event.preventDefault());

	needsResize = true;
	redraw();
}

/** Stops the animation loop, for teardown. */
export function dispose() {
	cancelFlight();
	if (animation != null) {
		cancelAnimationFrame(animation);
		animation = null;
	}
}

/**
 * Replaces the data being drawn and rebuilds the derived grouping.
 *
 * Does nothing when handed the same object, because app.js calls this from its render path and the
 * grouping rebuild walks every saved rail. Passing a different object with the same ids is a reload.
 *
 * @param {object|null} world a world object from `/api/data`.
 * @param {object|null} index the result of lists.js buildIndex, used to name areas when zoomed in.
 */
export function setWorld(world, index) {
	if (world === mapState.world) {
		return;
	}
	mapState.world = world;
	mapState.index = index || null;
	rebuildGroups();
	if (!viewHasBeenPlaced) {
		placeInitialView();
	}
	redraw();
}

export function setMode(mode) {
	if (mapState.mode === mode) {
		return;
	}
	mapState.mode = mode;
	rebuildGroups();
	redraw();
}

export function setTab(tab) {
	if (mapState.tab === tab) {
		return;
	}
	mapState.tab = tab;
	rebuildGroups();
	redraw();
}

export function setSelectedId(id) {
	mapState.selectedId = id;
	redraw();
}

// ---- editing ------------------------------------------------------------

/**
 * Enters area-editing mode for a station or depot.
 *
 * @param {'station'|'depot'} kind
 * @param {object} object the station or depot payload, for its current selection.
 */
export function beginAreaEdit(kind, object) {
	mapState.edit = beginEdit(kind, object.id, object);
	canvasCursor();
	redraw();
	notifyDraft();
}

/** Leaves editing and throws the draft away. */
export function cancelAreaEdit() {
	mapState.edit = cancelEdit();
	canvasCursor();
	redraw();
	notifyDraft();
}

/** @returns {boolean} whether an area edit is in progress. */
export function isAreaEditing() {
	return isEditing(mapState.edit);
}

/** @returns {boolean} whether a finished draft is waiting to be confirmed by stage 3.4. */
export function hasAreaDraft() {
	return hasDraft(mapState.edit);
}

/** @returns {object|null} the draft rectangle in world units, normalised. */
export function getAreaDraft() {
	return getDraftBounds(mapState.edit);
}

/** @returns {object|null} the object's existing selection, for drawing a reference outline. */
export function getAreaOriginal() {
	return mapState.edit && mapState.edit.original ? mapState.edit.original : null;
}

// ---- view control -------------------------------------------------------

function canvasCursor() {
	if (el.canvas) {
		// The crosshair itself comes from the `.map-area.is-editing` rule in CSS, so it is described in one
		// place and survives the canvas being resized or redrawn. This only maintains that class; it is
		// separate from the map's own drawing state.
		const area = el.canvas.parentElement;
		if (area) {
			area.classList.toggle('is-editing', isEditing(mapState.edit));
		}
	}
}

/**
 * Scale the map settles on when focusing on a player. Close enough to see individual blocks and the
 * platforms around them, which is what "where am I" is usually asked for.
 */
const FOCUS_PLAYER_SCALE = 8;

/**
 * Moves the view to a player's position.
 *
 * The position comes from the payload, so it is the position at the moment the data was requested - it is
 * not a live tracker, and deliberately so: a marker that moved under the viewer would make the button
 * useless for finding your own build.
 *
 * @param {{x: number, z: number}|null} player a `players` entry, or null when the world has none.
 * @returns {boolean} whether the view moved.
 */
export function focusOnPlayer(player) {
	if (!player || !isSanePoint(player.x, player.z)) {
		return false;
	}
	applyView({
		...view,
		centerX: player.x,
		centerZ: player.z,
		scale: clampScale(Math.max(view.scale, FOCUS_PLAYER_SCALE))
	});
	console.info('[MTR-WebDashboard] focused on player', player.name, player.x, player.z);
	return true;
}

/** Centres on a world point and raises the scale to a minimum. Mirrors the game's `find(BlockPos)`. */
export function focusOnPoint(worldX, worldZ, minScale) {
	applyView(centerOn({ ...view, scale: clampScale(Math.max(minScale || 0, view.scale)) }, worldX, worldZ));
}

/** Frames a selection. Mirrors the game's `find(x1, z1, x2, z2)`, which raises the scale to a minimum. */
export function focusOnArea(corner1, corner2, minScale) {
	if (!corner1 || !corner2) {
		return;
	}
	const framed = fitBounds(view, corner1.x, corner1.z, corner2.x, corner2.z);
	if (minScale && framed.scale < minScale) {
		framed.scale = clampScale(minScale);
	}
	applyView(framed);
}

/** @returns {object} the current view, for readouts. */
export function getView() {
	return view;
}

/**
 * Zooms by a factor about the middle of the view.
 *
 * Used by the + and − buttons: there is no cursor to anchor to, and the middle is what the viewer is
 * looking at. Shares the animation state with the wheel, so pressing the button mid-scroll retargets the
 * same motion rather than starting a competing one.
 */
export function zoomBy(factor) {
	zoomTargetScale = clampScale((zoomTargetScale != null ? zoomTargetScale : view.scale) * factor);
	zoomAnchorScreenX = view.width / 2;
	zoomAnchorScreenY = view.height / 2;
	startZoomAnimation();
}

/**
 * Moves the view.
 *
 * @param {object} next the view to adopt.
 * @param {boolean} [silent] skips the {@code onViewChange} callback. Used per-frame during a flight: the
 *        callback drives the readout, and calling it 60 times a second forced a full sidebar re-render,
 *        which is what used to make zooming stutter. The flight reports once when it settles instead.
 */
function applyView(next, silent) {
	view.centerX = next.centerX;
	view.centerZ = next.centerZ;
	view.scale = next.scale;
	viewHasBeenPlaced = true;
	redraw();
	if (!silent && handlers.onViewChange) {
		handlers.onViewChange(view);
	}
}

/**
 * Picks the first view.
 *
 * Confirmed behaviour: centre on a player when the world reports one, because that is what the game's
 * map does and it is where the viewer's attention is. With no players, fall back to the extent of the
 * data; with no data either, the origin.
 */
function placeInitialView() {
	const player = firstPlayer();
	if (player && isSanePoint(player.x, player.z)) {
		view.centerX = player.x;
		view.centerZ = player.z;
		view.scale = clampScale(1);
		viewHasBeenPlaced = true;
		// Logged because "the map opened somewhere unexpected" would otherwise be indistinguishable from a
		// jump: this line says which position was chosen, and why.
		console.info('[MTR-WebDashboard] map centred on the first player', player.name, player.x, player.z);
		return;
	}
	const bounds = computeContentBounds();
	if (bounds) {
		const fitted = fitBounds(view, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ);
		view.centerX = fitted.centerX;
		view.centerZ = fitted.centerZ;
		view.scale = fitted.scale;
		viewHasBeenPlaced = true;
		console.info('[MTR-WebDashboard] no player in this world, map framed the data', bounds, fitted);
		if (skippedEntries > 0) {
			console.warn('[MTR-WebDashboard] left out ' + skippedEntries + ' entr(y/ies) whose coordinates are outside the world boundary');
		}
	}
}

function firstPlayer() {
	const players = mapState.world && Array.isArray(mapState.world.players) ? mapState.world.players : [];
	return players.length > 0 ? players[0] : null;
}

/**
 * @returns {object|null} the player the map opens centred on, so the UI can offer to focus on them.
 *
 * The payload carries the position as of the request that produced it. Nothing here polls or updates it.
 */
export function getFirstPlayer() {
	return firstPlayer();
}

/**
 * Data entries whose coordinates were impossible and are therefore not drawn.
 *
 * Counted so the diagnostic can report them. Not surfaced in the UI: doing so properly needs a place in the
 * sidebar to put it, and a station that silently fails to appear is better explained by one console line
 * than by a half-built warning box.
 */
let skippedEntries = 0;

/**
 * @returns {{minX, minZ, maxX, maxZ}|null} the extent of everything the map can draw.
 *
 * Areas with unusable corners are excluded, not clamped. Corrupt coordinates do occur - the mod's own
 * multi-dimension rail handling has known problems - and a single impossible corner turns the bounding
 * box into the whole world, which collapses every real selection to a pixel and is what made "fit
 * everything" appear to jump somewhere far away.
 */
function computeContentBounds() {
	if (!mapState.world) {
		return null;
	}
	let minX = Infinity;
	let minZ = Infinity;
	let maxX = -Infinity;
	let maxZ = -Infinity;
	skippedEntries = 0;

	const include = (x, z) => {
		if (!isSanePoint(x, z)) {
			skippedEntries++;
			return;
		}
		minX = Math.min(minX, x);
		minZ = Math.min(minZ, z);
		maxX = Math.max(maxX, x);
		maxZ = Math.max(maxZ, z);
	};

	['stations', 'depots'].forEach(key => {
		(mapState.world[key] || []).forEach(area => {
			if (!area.hasArea) {
				return;
			}
			if (!isSaneArea(area.corner1, area.corner2)) {
				skippedEntries++;
				return;
			}
			include(area.corner1.x, area.corner1.z);
			include(area.corner2.x, area.corner2.z);
		});
	});
	savedRailGroups.forEach(group => include(group.pos.x, group.pos.z));

	return minX === Infinity ? null : { minX, minZ, maxX, maxZ };
}

// ---- drawing ------------------------------------------------------------

/** Redraws once. Safe to call from anywhere; repeated calls in a frame cost one clear and one paint. */
export function redraw() {
	if (!context || !el.canvas) {
		return;
	}
	resizeIfNeeded();

	context.setTransform(1, 0, 0, 1, 0, 0);
	context.clearRect(0, 0, el.canvas.width, el.canvas.height);
	// Everything below is in CSS pixels; the device pixel ratio is applied by the transform.
	context.setTransform(devicePixelRatio(), 0, 0, devicePixelRatio(), 0, 0);

	drawTerrain();
	drawAreas();
	drawSavedRails();
	drawDraft();
	drawLabels();
	drawPlayers();

	if (handlers.onViewChange) {
		handlers.onViewChange(view);
	}
}

function devicePixelRatio() {
	return window.devicePixelRatio || 1;
}

function resizeIfNeeded() {
	const ratio = devicePixelRatio();
	const rect = el.canvas.getBoundingClientRect();
	const cssWidth = Math.max(1, Math.round(rect.width));
	const cssHeight = Math.max(1, Math.round(rect.height));
	const deviceWidth = Math.round(cssWidth * ratio);
	const deviceHeight = Math.round(cssHeight * ratio);

	// Only touch the backing store when it actually changed: assigning width or height clears the canvas
	// and reallocates it, which would drop frames if done every frame.
	if (needsResize || el.canvas.width !== deviceWidth || el.canvas.height !== deviceHeight) {
		el.canvas.width = deviceWidth;
		el.canvas.height = deviceHeight;
		needsResize = false;
	}

	view.width = cssWidth;
	view.height = cssHeight;
}

/**
 * Layer 1: the background.
 *
 * No terrain - that is the confirmed "plan C" for this stage. What is drawn is a dark backdrop and a
 * reference grid, which give a sense of scale and make panning legible on an otherwise empty field.
 *
 * This is the function to replace when server-side terrain arrives: read `features.terrain`, fetch
 * tiles for `visibleWorldBounds(view)`, and draw them here. Everything below stays as it is.
 */
function drawTerrain() {
	context.fillStyle = argbToRgba(COLOR_BACKGROUND);
	context.fillRect(0, 0, view.width, view.height);

	const spacing = gridSpacing(view.scale);
	if (spacing === 0) {
		return;
	}

	const bounds = visibleWorldBounds(view, 1);
	const startX = Math.floor(bounds.minX / spacing) * spacing;
	const startZ = Math.floor(bounds.minZ / spacing) * spacing;

	context.strokeStyle = 'rgba(255, 255, 255, 0.06)';
	context.lineWidth = 1;
	context.beginPath();
	for (let x = startX; x <= bounds.maxX; x += spacing) {
		const screenX = Math.round(worldToScreen(view, x, 0).x) + 0.5;
		context.moveTo(screenX, 0);
		context.lineTo(screenX, view.height);
	}
	for (let z = startZ; z <= bounds.maxZ; z += spacing) {
		const screenY = Math.round(worldToScreen(view, 0, z).y) + 0.5;
		context.moveTo(0, screenY);
		context.lineTo(view.width, screenY);
	}
	context.stroke();
}

/**
 * Layer 2: the selections belonging to the active tab.
 *
 * Which kind is drawn follows the sidebar tab, exactly as the names and the saved rails already do:
 * the stations tab shows station selections, the depot tab shows depot selections. The game behaves
 * the same way, and drawing both at once made it impossible to tell which outline belonged to what -
 * a depot drawn over a station looked like one shape.
 *
 * The transport filter applies to depots, which have a mode of their own. Stations are shared by all
 * four modes in the model (`Station.hasTransportMode()` is false), so their selections are never
 * filtered - the same rule the sidebar list follows.
 */
function drawAreas() {
	const key = areaKeyForTab();
	if (!key) {
		return;
	}

	const bounds = visibleWorldBounds(view, 4);
	(mapState.world && mapState.world[key] ? mapState.world[key] : []).forEach(area => {
		// isSaneArea rejects a selection whose corners are outside the world boundary, which does happen in
		// this mod's data; drawing one would smear the canvas with a rectangle hundreds of millions of
		// blocks wide.
		if (!area.hasArea || !isSaneArea(area.corner1, area.corner2)) {
			return;
		}
		if (key === 'depots' && !matchesMode(area)) {
			return;
		}
		if (!isAreaVisible(bounds, minOf(area.corner1.x, area.corner2.x), minOf(area.corner1.z, area.corner2.z), maxOf(area.corner1.x, area.corner2.x), maxOf(area.corner1.z, area.corner2.z))) {
			return;
		}

		const selected = area.id === mapState.selectedId;
		const editing = isEditing(mapState.edit) && area.id === mapState.edit.id;

		// While an area is being edited its stored selection is drawn as a faint reference only, so the
		// draft reads as the thing being manipulated.
		if (editing) {
			drawAreaShape(area, 'rgba(255, 255, 255, 0.35)', 'rgba(255, 255, 255, 0.06)');
			return;
		}

		const fill = selected ? 'rgba(255, 255, 255, 0.18)' : argbToRgba(0x40000000);
		const outline = rgbToCss(area.color) || 'rgba(255, 255, 255, 0.65)';
		drawAreaShape(area, outline, fill);
	});
}

/**
 * @returns {'stations'|'depots'|null} which collection holds the areas for the active tab, or null when
 *          the tab's selections are not drawn at all. Routes have no area.
 */
function areaKeyForTab() {
	if (mapState.tab === 'depots') {
		return 'depots';
	}
	if (mapState.tab === 'stations') {
		return 'stations';
	}
	return null;
}

/** @returns {boolean} whether an object passes the transport filter. */
function matchesMode(object) {
	return mapState.mode === 'all' || object.transportMode === mapState.mode;
}

/**
 * Draws one selection as a rounded, filled rectangle with a rounded outline.
 *
 * The outline is a stroke rather than the game's four edge rectangles. The game fills edges because it
 * draws into a vertex buffer where a stroked rectangle would straddle the boundary; on a canvas a stroke
 * is both simpler and able to follow the rounded corners, which is what makes the shape read as an MD3
 * surface rather than a wireframe box.
 */
function drawAreaShape(area, outlineColor, fillColor) {
	const spanX = area.corner2.x - area.corner1.x;
	const spanZ = area.corner2.z - area.corner1.z;
	const width = outlineWorldWidth(view.scale, spanX, spanZ);
	if (!(width > 0)) {
		return;
	}

	const topLeft = worldToScreen(view, minOf(area.corner1.x, area.corner2.x), minOf(area.corner1.z, area.corner2.z));
	const bottomRight = worldToScreen(view, maxOf(area.corner1.x, area.corner2.x), maxOf(area.corner1.z, area.corner2.z));
	const rect = snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
	// Inset by half the stroke so the outline sits inside the selection rather than overflowing it.
	const inset = Math.max(0.5, width * view.scale / 2);
	const radius = clampCornerRadius(AREA_CORNER_RADIUS_PX, rect.width - inset * 2, rect.height - inset * 2);

	context.beginPath();
	context.roundRect(rect.x + inset, rect.y + inset, Math.max(0, rect.width - inset * 2), Math.max(0, rect.height - inset * 2), radius);
	if (fillColor) {
		context.fillStyle = fillColor;
		context.fill();
	}
	context.lineWidth = Math.max(1, width * view.scale);
	context.strokeStyle = outlineColor;
	context.stroke();
}

/**
 * Layer 3: platforms or sidings, as white blocks split between any that share a block.
 *
 * Which of the two is drawn follows the sidebar tab, matching the game's `setShowStations`, and the
 * transport filter is applied here rather than server-side because the payload carries all modes.
 */
function drawSavedRails() {
	if (savedRailGroups.length === 0) {
		return;
	}
	const bounds = visibleWorldBounds(view, 2);

	savedRailGroups.forEach(group => {
		if (!isAreaVisible(bounds, group.pos.x, group.pos.z, group.pos.x + 1, group.pos.z + 1)) {
			return;
		}
		const slots = savedRailSlots(group.items.length);
		slots.forEach((slot, index) => {
			// A selected saved rail is drawn solid and its block-mates translucent, so the selection is
			// visible even when several share one block.
			const selected = group.items[index].id === mapState.selectedId;
			fillWorldRoundedRect(
				group.pos.x, group.pos.z + slot.zFrom,
				group.pos.x + 1, group.pos.z + slot.zTo,
				selected ? 'rgba(255, 255, 255, 1)' : 'rgba(255, 255, 255, 0.6)',
				SAVED_RAIL_CORNER_RADIUS_PX
			);
		});
	});

	// The selection ring goes around the whole block, because one slice of it is only a few pixels wide at
	// most zooms.
	const selectedGroup = savedRailGroups.find(group => group.items.some(item => item.id === mapState.selectedId));
	if (selectedGroup) {
		const topLeft = worldToScreen(view, selectedGroup.pos.x, selectedGroup.pos.z);
		const bottomRight = worldToScreen(view, selectedGroup.pos.x + 1, selectedGroup.pos.z + 1);
		const rect = snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
		const radius = clampCornerRadius(SAVED_RAIL_CORNER_RADIUS_PX + 1, rect.width + 4, rect.height + 4);
		context.beginPath();
		context.roundRect(rect.x - 2, rect.y - 2, rect.width + 4, rect.height + 4, radius);
		context.strokeStyle = SELECTION_RING_COLOUR;
		context.lineWidth = 2;
		context.stroke();
	}
}

/** Layer 4: the rectangle currently being dragged out. */
function drawDraft() {
	const draft = getDraftBounds(mapState.edit);
	if (!draft) {
		return;
	}

	fillWorldRect(draft.minX, draft.minZ, draft.maxX, draft.maxZ, argbToRgba(0x7FFFFFFF));

	// A dashed edge distinguishes "not saved yet" from the solid outline of a committed selection.
	const topLeft = worldToScreen(view, draft.minX, draft.minZ);
	const bottomRight = worldToScreen(view, draft.maxX, draft.maxZ);
	const rect = snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
	context.save();
	context.setLineDash([6, 4]);
	context.strokeStyle = 'rgba(255, 255, 255, 0.9)';
	context.lineWidth = 1;
	context.strokeRect(rect.x + 0.5, rect.y + 0.5, rect.width - 1, rect.height - 1);
	context.restore();
}

/** Layer 5: area names and saved rail names, at the same zoom thresholds the game uses. */
function drawLabels() {
	if (!mapState.world) {
		return;
	}
	context.textAlign = 'center';
	context.textBaseline = 'middle';
	context.font = '12px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

	const drawText = (text, worldX, worldZ) => {
		const screen = worldToScreen(view, worldX, worldZ);
		if (screen.x < -200 || screen.x > view.width + 200 || screen.y < 0 || screen.y > view.height) {
			return;
		}
		context.lineWidth = 3;
		context.strokeStyle = 'rgba(0, 0, 0, 0.75)';
		context.strokeText(text, screen.x, screen.y);
		context.fillStyle = 'rgba(255, 255, 255, 0.95)';
		context.fillText(text, screen.x, screen.y);
	};

	if (shouldDrawSavedRailLabel(view.scale)) {
		savedRailGroups.forEach(group => {
			const slots = savedRailSlots(group.items.length);
			slots.forEach((slot, index) => {
				const item = group.items[index];
				if (item.name) {
					drawText(formatLabel(item.name), group.pos.x + 0.5, group.pos.z + (slot.zFrom + slot.zTo) / 2);
				}
			});
		});
	}

	// Areas are labelled for the same collection the outlines are drawn for, so a label can never appear
	// without its shape - which is what happened when the two used different rules.
	const key = areaKeyForTab();
	if (!key) {
		return;
	}
	(mapState.world[key] || []).forEach(area => {
		if (!area.hasArea || !isSaneArea(area.corner1, area.corner2) || (key === 'depots' && !matchesMode(area))) {
			return;
		}
		const spanX = area.corner2.x - area.corner1.x;
		const spanZ = area.corner2.z - area.corner1.z;
		if (!shouldDrawAreaLabel(view.scale, spanX, spanZ)) {
			return;
		}
		drawText(formatLabel(area.name), (area.corner1.x + area.corner2.x) / 2, (area.corner1.z + area.corner2.z) / 2);
	});
}

/** Layer 6: players, drawn exactly like the game - a white cross with a blue core. */
function drawPlayers() {
	const players = mapState.world && Array.isArray(mapState.world.players) ? mapState.world.players : [];
	players.forEach(player => {
		if (!isSanePoint(player.x, player.z)) {
			return;
		}
		const screen = worldToScreen(view, player.x, player.z);
		if (screen.x < -20 || screen.x > view.width + 20 || screen.y < -20 || screen.y > view.height + 20) {
			return;
		}
		const x = Math.round(screen.x);
		const y = Math.round(screen.y);

		context.fillStyle = argbToRgba(0xFFFFFFFF);
		context.fillRect(x - 2, y - 3, 4, 6);
		context.fillRect(x - 3, y - 2, 6, 4);

		context.fillStyle = argbToRgba(COLOR_PLAYER_MARKER);
		context.fillRect(x - 2, y - 2, 4, 4);
	});
}

/** Draws a world rectangle in fill colour, snapped to device pixels so blocks tile without seams. */
function fillWorldRect(worldX1, worldZ1, worldX2, worldZ2, fillStyle) {
	const rect = worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2);
	context.fillStyle = fillStyle;
	context.fillRect(rect.x, rect.y, rect.width, rect.height);
}

/** As {@link fillWorldRect}, with rounded corners of at most half the shorter side. */
function fillWorldRoundedRect(worldX1, worldZ1, worldX2, worldZ2, fillStyle, radiusPx) {
	const rect = worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2);
	const radius = clampCornerRadius(radiusPx, rect.width, rect.height);
	context.fillStyle = fillStyle;
	context.beginPath();
	// roundRect with a zero radius is still correct, so no branch is needed for the degenerate case.
	context.roundRect(rect.x, rect.y, rect.width, rect.height, radius);
	context.fill();
}

/** @returns {{x, y, width, height}} a world rectangle in snapped device pixels. */
function worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2) {
	const topLeft = worldToScreen(view, Math.min(worldX1, worldX2), Math.min(worldZ1, worldZ2));
	const bottomRight = worldToScreen(view, Math.max(worldX1, worldX2), Math.max(worldZ1, worldZ2));
	return snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
}

/** Bilingual names use `|` as a separator; the list shows both halves with a space, so the map does too. */
function formatLabel(name) {
	return typeof name === 'string' ? name.replace(/\|/g, ' ').trim() : '';
}

function minOf(a, b) {
	return Math.min(a, b);
}
function maxOf(a, b) {
	return Math.max(a, b);
}

// ---- derived data -------------------------------------------------------

/**
 * Groups the saved rails of the current tab and transport filter by their block.
 *
 * Rebuilt on data, tab or filter changes rather than per frame, and the filter is applied here so the
 * draw loop only walks what it will actually paint.
 */
function rebuildGroups() {
	savedRailGroups = [];
	if (!mapState.world) {
		return;
	}

	// The game shows platforms except on the depot tab, where it shows sidings instead.
	const source = mapState.tab === 'depots' ? mapState.world.sidings : mapState.world.platforms;
	const byBlock = new Map();

	(source || []).forEach(savedRail => {
		if (mapState.mode !== 'all' && savedRail.transportMode !== mapState.mode) {
			return;
		}
		// Same reason as the areas: a saved rail with an impossible midpoint would poison the bounding box
		// and paint a stray block far off screen.
		if (!isSanePoint(savedRail.midX, savedRail.midZ)) {
			return;
		}
		const key = savedRail.midX + ',' + savedRail.midZ;
		if (!byBlock.has(key)) {
			byBlock.set(key, { pos: { x: savedRail.midX, z: savedRail.midZ }, items: [] });
		}
		byBlock.get(key).items.push(savedRail);
	});

	// Sorted so a block's slots are assigned in a stable order; otherwise two platforms could swap places
	// between redraws and the white blocks would appear to flicker.
	savedRailGroups = [...byBlock.values()].sort((a, b) => (a.pos.x - b.pos.x) || (a.pos.z - b.pos.z));
	savedRailGroups.forEach(group => group.items.sort((a, b) => String(a.id).localeCompare(String(b.id))));
}

// ---- pointer handling ---------------------------------------------------

function toCanvasPoint(event) {
	const rect = el.canvas.getBoundingClientRect();
	return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

function onPointerDown(event) {
	const point = toCanvasPoint(event);
	// Middle button always pans, in and out of edit mode. Right button pans while editing, so the view is
	// not locked while a selection is being drawn - the game does lock it, and that is one of the things
	// this page is meant to improve on.
	const editing = isEditing(mapState.edit);
	const pans = event.button === 1 || (!editing && event.button === 0) || (editing && event.button === 2);

	if (pans) {
		pointerState = { kind: 'pan', lastX: point.x, lastY: point.y, moved: false };
		// Grabbing the map takes over from any flight to an object.
		cancelFlight();
		el.canvas.setPointerCapture(event.pointerId);
		event.preventDefault();
		return;
	}

	if (editing && event.button === 0) {
		const world = screenToWorld(view, point.x, point.y);
		pointerState = { kind: 'draw', startX: point.x, startY: point.y, moved: false };
		dragToEdit(mapState.edit, world.x, world.z);
		el.canvas.setPointerCapture(event.pointerId);
		event.preventDefault();
		redraw();
	}
}

function onPointerMove(event) {
	const point = toCanvasPoint(event);

	const world = screenToWorld(view, point.x, point.y);
	el.pointer.x = world.x;
	el.pointer.z = world.z;
	el.pointer.valid = true;

	if (pointerState && pointerState.kind === 'pan') {
		const deltaX = point.x - pointerState.lastX;
		const deltaY = point.y - pointerState.lastY;
		pointerState.lastX = point.x;
		pointerState.lastY = point.y;
		if (Math.abs(deltaX) + Math.abs(deltaY) > 0) {
			pointerState.moved = true;
			const next = panByScreenDelta(view, deltaX, deltaY);
			applyView(next);
		}
	} else if (pointerState && pointerState.kind === 'draw') {
		if (Math.abs(point.x - pointerState.startX) + Math.abs(point.y - pointerState.startY) >= DRAG_THRESHOLD_PX) {
			pointerState.moved = true;
		}
		if (pointerState.moved) {
			dragToEdit(mapState.edit, world.x, world.z);
			redraw();
			// Reported live so the size readout updates as the rectangle grows, rather than only on release.
			notifyDraft();
		}
	} else {
		// Not dragging: still redraw so the readout follows the cursor.
		redraw();
	}

	notifyPointer();
}

function onPointerUp(event) {
	const state = pointerState;
	pointerState = null;
	if (!state) {
		return;
	}
	if (el.canvas.hasPointerCapture && el.canvas.hasPointerCapture(event.pointerId)) {
		el.canvas.releasePointerCapture(event.pointerId);
	}

	if (state.kind === 'draw') {
		// A press with no movement is discarded rather than becoming a one-block selection.
		const kept = endEditDrag(mapState.edit);
		notifyDraft();
		const draft = getDraftBounds(mapState.edit);
		if (kept && draft && handlers.onEditArea) {
			// Snapped to whole blocks: a selection is a set of blocks, and the mod's containment tests compare
			// block coordinates. Reporting the raw drag positions produced corners like 80.11111111111111, which
			// means the same selection as block 80 but reads as noise in the size display.
			handlers.onEditArea(snapAreaCorners(
				{ x: draft.minX, z: draft.minZ },
				{ x: draft.maxX, z: draft.maxZ }
			));
		}
		redraw();
		return;
	}

	if (state.kind === 'pan' && !state.moved && event.button === 0) {
		// A click rather than a drag: select whatever saved rail is under the cursor, or clear.
		const world = screenToWorld(view, toCanvasPoint(event).x, toCanvasPoint(event).y);
		const hit = hitTestSavedRails(savedRailGroups, world.x, world.z);
		if (handlers.onSelect) {
			handlers.onSelect(hit ? hit.item.id : null);
		}
	}
}

function onWheel(event) {
	event.preventDefault();
	if (event.deltaY === 0) {
		return;
	}

	const point = toCanvasPoint(event);

	/*
	 * Zoom step.
	 *
	 * A fixed doubling per notch, as the game does with `scale * 2^amount`. The magnitude of deltaY is
	 * deliberately ignored: a trackpad reports a stream of small deltas and a notched wheel reports
	 * 100 or 120, so scaling by the magnitude would make the two feel wildly different.
	 */
	const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;

	// The user has taken over from any flight to an object. Their input wins immediately rather than
	// fighting an animation that is still trying to reach somewhere else.
	cancelFlight();

	/*
	 * The target accumulates, and is computed from the scale the animation has already reached - not from
	 * the scale at the moment of the first event. Flicking the wheel used to start a fresh 250 ms
	 * animation per event, each cancelling the last, so a burst of them restarted from a nearly unchanged
	 * scale and the map appeared to stall.
	 */
	zoomTargetScale = clampScale((zoomTargetScale != null ? zoomTargetScale : view.scale) * factor);
	zoomAnchorScreenX = point.x;
	zoomAnchorScreenY = point.y;
	startZoomAnimation();
}

/**
 * Animates the live view towards {@link zoomTargetScale}, holding the world point under the anchor fixed.
 *
 * Two differences from a straight interpolation between two snapshots:
 *
 *  - the anchor's world point is re-solved from the live view on every frame, so wheel events arriving
 *    mid-animation compound smoothly instead of the scale jumping back to an earlier value;
 *  - the view is not pushed to the readout every frame. Doing that forced a full sidebar re-render at
 *    60 Hz, which is what made zooming stutter.
 */
function startZoomAnimation() {
	if (animation != null) {
		// Already running; it will pick up the new target on its next frame.
		return;
	}

	const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
	if (reduceMotion) {
		applyZoomFrame(1);
		finishZoom();
		return;
	}

	const step = () => {
		animation = null;
		const remaining = applyZoomFrame(ZOOM_EASING_FACTOR);
		if (remaining) {
			animation = requestAnimationFrame(step);
		} else {
			finishZoom();
		}
	};
	animation = requestAnimationFrame(step);
}

/**
 * Moves the scale a fraction of the way towards the target, re-anchoring as it goes.
 *
 * Exponential smoothing rather than an eased interpolation over a fixed duration: it has no end time to
 * restart, so a wheel burst simply retargets and the motion stays continuous.
 *
 * @param {number} fraction how much of the remaining distance to cover this frame.
 * @returns {boolean} whether the target has been reached.
 */
function applyZoomFrame(fraction) {
	const target = zoomTargetScale;
	if (target == null) {
		return false;
	}

	// The anchor is re-read from the live view, so the point under the cursor stays put even if the view
	// moved since the last frame.
	const anchorWorld = screenToWorld(view, zoomAnchorScreenX, zoomAnchorScreenY);
	const next = view.scale + (target - view.scale) * fraction;
	// Snapped once the remaining distance is imperceptible, so the animation terminates instead of
	// asymptotically approaching the target forever.
	const done = Math.abs(target - next) / target < ZOOM_SETTLE_RATIO;
	const scale = clampScale(done ? target : next);

	view.scale = scale;
	view.centerX = anchorWorld.x - (zoomAnchorScreenX - view.width / 2) / scale;
	view.centerZ = anchorWorld.z - (zoomAnchorScreenY - view.height / 2) / scale;
	viewHasBeenPlaced = true;
	redraw();
	return !done;
}

/** Clears the zoom target and reports the settled view once, so the readout catches up. */
function finishZoom() {
	zoomTargetScale = null;
	if (handlers.onViewChange) {
		handlers.onViewChange(view);
	}
}

// ---- flying to an object -------------------------------------------------

/**
 * Flies the view to a target, easing over a fixed duration.
 *
 * Deliberately a different mechanism from the wheel's zoom, which uses exponential smoothing towards a
 * scale. That one must have no end time, so a burst of wheel events simply retargets and the motion stays
 * continuous. This one is a single discrete jump - "take me to that station" - and a fixed duration with
 * easing is what makes the destination legible: the viewer needs to see where the map went, not arrive
 * there instantly.
 *
 * Only one of the two can drive the view at a time, so starting a flight cancels a zoom in progress and
 * vice versa.
 *
 * @param {object} targetView the view to arrive at.
 * @param {number} [durationMs] overrides the default flight time.
 * @returns {boolean} whether a flight was started. False when the view is already there, so the caller can
 *          avoid starting a 420 ms animation that goes nowhere.
 */
export function flyTo(targetView, durationMs) {
	if (!targetView) {
		return false;
	}
	const to = { ...targetView, width: view.width, height: view.height };
	if (sameView(view, to)) {
		applyView(to);
		return false;
	}

	cancelFlight();
	cancelZoom();

	const from = { ...view };
	const duration = durationMs != null ? durationMs : FLY_DURATION_MS;
	const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

	if (reduceMotion || !(duration > 0)) {
		// Honouring the preference means arriving, not refusing to move.
		applyView(to);
		return true;
	}

	const startedAt = performance.now();
	const step = () => {
		flightAnimation = null;
		const elapsed = performance.now() - startedAt;
		const progress = Math.min(1, elapsed / duration);
		applyView(interpolateView(from, to, progress), true);
		if (progress < 1) {
			flightAnimation = requestAnimationFrame(step);
		} else {
			// Report the settled view once, so the readout catches up without a per-frame re-render.
			if (handlers.onViewChange) {
				handlers.onViewChange(view);
			}
		}
	};
	flightAnimation = requestAnimationFrame(step);
	return true;
}

/** Stops a flight in progress, leaving the view wherever it had reached. */
export function cancelFlight() {
	if (flightAnimation != null) {
		cancelAnimationFrame(flightAnimation);
		flightAnimation = null;
	}
}

/** Stops a wheel zoom in progress. Used when a flight takes over the view. */
function cancelZoom() {
	zoomTargetScale = null;
}

/**
 * Brings an object into view, ready for its editing card.
 *
 * Focus decisions - what to frame, and where the card should point - are made by focus.js, which is pure
 * and tested. This only turns the decision into a flight.
 *
 * @param {string} kind 'station' | 'depot' | 'route' | 'platform' | 'siding'.
 * @param {object} object the payload for that object.
 * @param {object} index lists.js buildIndex, for resolving a route's platform positions.
 * @returns {{animate: boolean, anchorScreen: {x: number, y: number}, hasArea: boolean, isEmpty: boolean}}
 *          the anchor is in map-area coordinates, for placing the editing card.
 */
export function focusOnTarget(kind, object, index) {
	const plan = planFocus(kind, object, index, view);
	if (plan.targetView) {
		flyTo(plan.targetView);
	}
	// The anchor is returned in the COORDINATES OF THE TARGET VIEW, so the card points at where the object
	// will be once the flight lands. Using the current view would leave the card pointing at empty map for
	// the whole 420 ms and then snap to the right place.
	return { animate: plan.animate, anchorScreen: plan.anchorScreen, hasArea: plan.hasArea, isEmpty: plan.isEmpty };
}

function notifyPointer() {
	if (handlers.onPointerMove) {
		handlers.onPointerMove(el.pointer.valid ? { x: el.pointer.x, z: el.pointer.z } : null);
	}
}

/** Reports the current draft, so the editing bar can show its size or clear itself. */
function notifyDraft() {
	if (handlers.onDraftChange) {
		handlers.onDraftChange(getDraftBounds(mapState.edit));
	}
}
