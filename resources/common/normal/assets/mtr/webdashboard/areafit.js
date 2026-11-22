/** @returns {boolean} whether a corner has the two whole coordinates a selection needs. */
export function isValidCorner(corner) {
	return Boolean(corner)
		&& Number.isFinite(corner.x)
		&& Number.isFinite(corner.z)
		&& Number.isInteger(corner.x)
		&& Number.isInteger(corner.z);
}

/** @returns {boolean} whether the corner is at the origin. */
export function isAtOrigin(corner) {
	return Boolean(corner) && corner.x === 0 && corner.z === 0;
}

/** @returns {boolean} whether a selection has a corner on the origin, and so cannot be saved. */
export function hasOriginCorner(corner1, corner2) {
	return isAtOrigin(corner1) || isAtOrigin(corner2);
}
