/*
 * The map's area-editing state machine. Pure logic: no DOM, no canvas.
 *
 * Invariants:
 *   1. draftCorner2 is non-null only when draftCorner1 is non-null.
 *   2. Entering edit mode leaves both draft corners null; the object's existing selection is kept in
 *      `original`. This differs from the game, whose WidgetMap.startEditingArea seeds the draw area from
 *      the current corners - which would make the first click treat a corner of the existing selection as
 *      the drag origin and produce a far larger rectangle than intended.
 *   3. A drag released without moving is discarded, not kept as a one-block selection.
 *   4. Leaving edit mode clears every field.
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
 * Floors a world coordinate to the block containing it, as WidgetMap.coordsToWorldPos does. A selection is
 * a set of blocks - AreaBase.inArea tests block coordinates inclusively - so a fractional corner describes
 * nothing the mod can act on.
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
 * @param {{corner1: WorldPoint|null, corner2: WorldPoint|null}} area the object's current selection.
 * @returns {EditState} a new state, with null draft corners by invariant 2.
 */
export function beginEdit(kind, id, area) {
	const corner1 = area && area.corner1 ? { x: area.corner1.x, z: area.corner1.z } : null;
	const corner2 = area && area.corner2 ? { x: area.corner2.x, z: area.corner2.z } : null;
	return {
		kind,
		id,
		// Copied, not referenced: the world payload is replaced wholesale on reload, and holding a reference
		// into the old one would keep a stale selection on screen.
		original: corner1 && corner2 ? { c1: corner1, c2: corner2 } : null,
		draftCorner1: null,
		draftCorner2: null,
		hasMoved: false
	};
}

/** @returns {EditState} a fresh "not editing" state, discarding any draft. */
export function cancelEdit() {
	return createEditState();
}

/**
 * Feeds a pointer position into the drag, starting one if none is in progress. The first call anchors the
 * rectangle and later calls move its opposite corner; one function rather than separate "start" and "drag"
 * so a caller cannot start a drag twice and lose the anchor.
 *
 * @param {EditState} edit mutated in place.
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

	// Matches WidgetMap.mouseDragged. Areas are tested inclusively, so equal corners already describe one
	// block; the nudge makes the box one block *wide* on that axis, which is the game's convention.
	if (edit.draftCorner1.x === edit.draftCorner2.x) {
		edit.draftCorner2.x += 1;
	}
	if (edit.draftCorner1.z === edit.draftCorner2.z) {
		edit.draftCorner2.z += 1;
	}
}

/**
 * Ends the drag, discarding the draft when nothing moved (invariant 3).
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
 * @returns {{minX: number, minZ: number, maxX: number, maxZ: number}|null} the draft rectangle, normalised
 *          so min <= max on both axes, or null when there is no draft.
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
 * Swaps which corner of the draft is the anchor, keeping the same rectangle. Presentational only: nothing
 * downstream depends on corner order.
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
