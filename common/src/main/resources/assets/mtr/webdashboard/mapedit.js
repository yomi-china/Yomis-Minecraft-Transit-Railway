/*
 * The map's area-editing state machine. Pure logic: takes a state object and world coordinates,
 * mutates nothing it was not handed, and touches no DOM.
 *
 * Separate from map.js because this is the part the user asked to be written carefully, and because
 * the invariants below are exactly what a test can pin down while the canvas drawing cannot be.
 *
 * Invariants:
 *
 *   1. draftCorner2 is non-null only when draftCorner1 is non-null. Drawing relies on this to know
 *      whether a drag is in progress.
 *   2. Entering edit mode leaves both draft corners NULL. The object's existing selection is kept
 *      separately in `original`.
 *   3. A draft that was released without moving is discarded, not kept as a one-block selection.
 *   4. Leaving edit mode clears every field.
 *
 * Invariant 2 is where this deliberately differs from the game. WidgetMap.startEditingArea seeds its
 * draw area from the object's current corners, so the first click of a drag would treat a corner of
 * the existing selection as the drag's origin - producing a huge unexpected rectangle when the user
 * meant to start a small one. The existing selection still needs to be visible while editing, so it
 * lives in `original` and is drawn as a faint outline, never used as a drag origin.
 */

/**
 * @typedef {{x: number, z: number}} WorldPoint
 * @typedef {{kind: 'station'|'depot'|null, id: string|null, original: {c1: WorldPoint, c2: WorldPoint}|null,
 *            draftCorner1: WorldPoint|null, draftCorner2: WorldPoint|null, hasMoved: boolean}} EditState
 */

/** @returns {EditState} a state representing "not editing". */
export function createEditState() {
	return { kind: null, id: null, original: null, draftCorner1: null, draftCorner2: null, hasMoved: false };
}

/**
 * Floors a world coordinate to the block containing it.
 *
 * A deliberate copy of mapview.js's `snapToBlock` rather than an import: this module is the edit state
 * machine and is kept free of dependencies so it can be reasoned about and tested on its own.
 */
function snapToBlock(value) {
	return Number.isFinite(value) ? Math.floor(value) : 0;
}

/** @returns {boolean} whether an area edit is in progress. */
export function isEditing(edit) {
	return edit != null && edit.kind != null && edit.id != null;
}

/** @returns {boolean} whether a rectangle is being dragged out right now. */
export function isDrafting(edit) {
	return isEditing(edit) && edit.draftCorner1 != null;
}

/** @returns {boolean} whether a completed draft is waiting to be confirmed. */
export function hasDraft(edit) {
	return isEditing(edit) && edit.draftCorner1 != null && edit.draftCorner2 != null && edit.hasMoved;
}

/**
 * Enters edit mode for one object.
 *
 * @param {'station'|'depot'} kind
 * @param {string} id
 * @param {{corner1: {x: number, z: number}|null, corner2: {x: number, z: number}|null}} area the
 *        object's current selection, or an object with null corners when it has none.
 * @returns {EditState} a new state. Draft corners are null by invariant 2.
 */
export function beginEdit(kind, id, area) {
	const corner1 = area && area.corner1 ? { x: area.corner1.x, z: area.corner1.z } : null;
	const corner2 = area && area.corner2 ? { x: area.corner2.x, z: area.corner2.z } : null;
	return {
		kind,
		id,
		// Copied, not referenced: the world payload is replaced wholesale when data reloads, and holding a
		// reference into the old one would keep a stale selection alive on screen.
		original: corner1 && corner2 ? { c1: corner1, c2: corner2 } : null,
		draftCorner1: null,
		draftCorner2: null,
		hasMoved: false
	};
}

/**
 * Leaves edit mode, discarding any draft. Returns a fresh state so callers cannot keep a reference to
 * something half-cleared.
 *
 * @returns {EditState} a state representing "not editing".
 */
export function cancelEdit() {
	return createEditState();
}

/**
 * Feeds a pointer position into the drag, starting one if none is in progress.
 *
 * The first call anchors the rectangle; later calls move its opposite corner. This is one function
 * rather than a separate "start" and "drag" so a caller cannot start a drag twice and lose the anchor.
 *
 * Both corners are floored to whole blocks as they are recorded, mirroring `WidgetMap.coordsToWorldPos`,
 * which floors every position it converts. A selection is a set of blocks - `AreaBase.inArea` tests block
 * coordinates, inclusively - so a fractional corner cannot describe anything the mod can act on. Storing
 * one also put sixteen digits of noise into the live size readout.
 *
 * @param {EditState} edit mutated in place.
 * @param {number} worldX
 * @param {number} worldZ
 */
export function dragToEdit(edit, worldX, worldZ) {
	if (!isEditing(edit)) {
		return;
	}

	const blockX = snapToBlock(worldX);
	const blockZ = snapToBlock(worldZ);

	if (edit.draftCorner1 == null) {
		edit.draftCorner1 = { x: blockX, z: blockZ };
		edit.draftCorner2 = { x: blockX, z: blockZ };
		edit.hasMoved = false;
		return;
	}

	edit.draftCorner2 = { x: blockX, z: blockZ };
	edit.hasMoved = true;

	// Matches WidgetMap.mouseDragged, including its `+1`. Because the mod tests areas inclusively, equal
	// corners already describe a one-block selection - the nudge makes the box one block *wide* on that axis,
	// which is the game's own convention and is what the size readout counts.
	if (edit.draftCorner1.x === edit.draftCorner2.x) {
		edit.draftCorner2.x += 1;
	}
	if (edit.draftCorner1.z === edit.draftCorner2.z) {
		edit.draftCorner2.z += 1;
	}
}

/**
 * Ends the drag.
 *
 * Invariant 3: a release with no movement throws the draft away rather than leaving a one-block
 * selection behind. In the game a stray click always produces a selection, which is tolerable when the
 * user is holding a mouse in-game but not when a misclick on a web page silently redefines a station.
 *
 * @param {EditState} edit mutated in place.
 * @returns {boolean} whether a usable draft remains.
 */
export function endEditDrag(edit) {
	if (!isEditing(edit)) {
		return false;
	}
	if (!edit.hasMoved) {
		edit.draftCorner1 = null;
		edit.draftCorner2 = null;
		return false;
	}
	return true;
}

/**
 * The draft rectangle, normalised so min <= max on both axes.
 *
 * @returns {{minX: number, minZ: number, maxX: number, maxZ: number}|null} null when there is no draft.
 */
export function getDraftBounds(edit) {
	if (edit == null || edit.draftCorner1 == null || edit.draftCorner2 == null) {
		return null;
	}
	return {
		minX: Math.min(edit.draftCorner1.x, edit.draftCorner2.x),
		minZ: Math.min(edit.draftCorner1.z, edit.draftCorner2.z),
		maxX: Math.max(edit.draftCorner1.x, edit.draftCorner2.x),
		maxZ: Math.max(edit.draftCorner1.z, edit.draftCorner2.z)
	};
}

/**
 * Keeps the dragged corner and swaps the other two.
 *
 * The game stores the selection as whichever two corners the user happened to drag, and their order is
 * what gets saved. Nothing downstream depends on the order - the server stores both corners and every
 * containment test normalises - so this is presentational only, and the UI will warn that the selection
 * must not be no larger than the station. It exists so that the two corners can be chosen deliberately
 * rather than by accident of drag direction.
 *
 * @returns {EditState} a new state with the corners rotated, or the input when there is no draft.
 */
export function swapDraftCorners(edit) {
	if (!hasDraft(edit)) {
		return edit;
	}
	return {
		...edit,
		draftCorner1: { x: edit.draftCorner2.x, z: edit.draftCorner2.z },
		draftCorner2: { x: edit.draftCorner1.x, z: edit.draftCorner1.z }
	};
}
