/**
 * @typedef {{x: number, z: number}} WorldPoint
 * @typedef {{kind: 'station'|'depot'|null, id: string|null, original: {c1: WorldPoint, c2: WorldPoint}|null,
 *            draftCorner1: WorldPoint|null, draftCorner2: WorldPoint|null, hasMoved: boolean}} EditState
 */

/** @returns {EditState} a state representing "not editing". */
export function createEditState() {
	return { kind: null, id: null, original: null, draftCorner1: null, draftCorner2: null, hasMoved: false };
}

function snapToBlock(value) {
	return Number.isFinite(value) ? Math.floor(value) : 0;
}

/** @returns {boolean} whether an area edit is in progress. */
export function isEditing(edit) {
	return edit != null && edit.kind != null && edit.id != null;
}

/** @returns {boolean} whether a completed draft is waiting to be confirmed. */
export function hasDraft(edit) {
	return isEditing(edit) && edit.draftCorner1 != null && edit.draftCorner2 != null && edit.hasMoved;
}

/**
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

	if (edit.draftCorner1.x === edit.draftCorner2.x) {
		edit.draftCorner2.x += 1;
	}
	if (edit.draftCorner1.z === edit.draftCorner2.z) {
		edit.draftCorner2.z += 1;
	}
}

/**
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
