/*
 * Selection arithmetic: what a rectangle contains, and whether it can be stored. Pure logic: no DOM, no map.
 *
 * Kept apart from map.js because two places need the same answers - the map while a selection is being
 * dragged, and the card when the draft is saved - and a second copy of "is this point inside" would drift
 * from the first.
 *
 * Deliberately small. An earlier version also counted which saved rails a selection would gain or lose, to
 * warn before saving; that was not asked for and is gone, along with the two functions it needed.
 */

/**
 * Whether a value falls between two others, both ends included.
 *
 * A port of `RailwayData.isBetween`, which the model uses from `AreaBase.inArea`. The inclusivity is the
 * part that matters and the part most easily got wrong: because both ends count, a 1x1 selection is one
 * block rather than none, and a saved rail sitting exactly on the edge belongs to the area.
 */
export function isBetween(value, end1, end2) {
	return value >= Math.min(end1, end2) && value <= Math.max(end1, end2);
}

/** @returns {boolean} whether a world point is inside a rectangle, both ends included. */
export function isInsideArea(corner1, corner2, x, z) {
	if (!isValidCorner(corner1) || !isValidCorner(corner2)) {
		return false;
	}
	return isBetween(x, corner1.x, corner2.x) && isBetween(z, corner1.z, corner2.z);
}

/** @returns {boolean} whether a corner has the two whole coordinates a selection needs. */
export function isValidCorner(corner) {
	return Boolean(corner)
		&& Number.isFinite(corner.x)
		&& Number.isFinite(corner.z)
		&& Number.isInteger(corner.x)
		&& Number.isInteger(corner.z);
}

/**
 * Whether a corner sits on the world origin.
 *
 * <b>Storing such a corner is impossible, not merely discouraged.</b> `AreaBase.setCorners` reads a corner
 * of (0, 0) as "no selection was ever set" and nulls it, so a selection with a corner there would be
 * silently cleared while the request reported success. The page refuses it while the selection is being
 * dragged - the save button is disabled - and the server refuses it again as a backstop.
 *
 * @returns {boolean} whether the corner is at the origin.
 */
export function isAtOrigin(corner) {
	return Boolean(corner) && corner.x === 0 && corner.z === 0;
}

/** @returns {boolean} whether a selection has a corner on the origin, and so cannot be saved. */
export function hasOriginCorner(corner1, corner2) {
	return isAtOrigin(corner1) || isAtOrigin(corner2);
}

/**
 * A selection's size in blocks.
 *
 * One more than the difference, because both ends are included: the rectangle from 10 to 10 is one block
 * wide, not zero. This is the number the map's readout shows, and reporting the raw difference would make
 * every selection look one block too small.
 */
export function areaSize(corner1, corner2) {
	if (!isValidCorner(corner1) || !isValidCorner(corner2)) {
		return { width: 0, height: 0, blocks: 0 };
	}
	const width = Math.abs(corner2.x - corner1.x) + 1;
	const height = Math.abs(corner2.z - corner1.z) + 1;
	return { width, height, blocks: width * height };
}

/** @returns {boolean} whether two selections describe the same rectangle, corner order included. */
export function sameCorners(a, b) {
	if (!a || !b) {
		return a === b;
	}
	return a.corner1 && a.corner2 && b.corner1 && b.corner2
		&& a.corner1.x === b.corner1.x && a.corner1.z === b.corner1.z
		&& a.corner2.x === b.corner2.x && a.corner2.z === b.corner2.z;
}
