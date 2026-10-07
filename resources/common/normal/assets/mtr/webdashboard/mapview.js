export const MIN_SCALE = 1 / 2048;
export const MAX_SCALE = 256;
export const DEFAULT_SCALE = 1;

export const OUTLINE_BASE_WORLD_WIDTH = 0.9;
export const OUTLINE_MIN_SCREEN_WIDTH = 0.6;
export const OUTLINE_MAX_SCREEN_WIDTH = 2.5;
export const OUTLINE_MAX_AREA_RATIO = 0.4;
export const AREA_LABEL_NUMERATOR = 80;
export const SAVED_RAIL_LABEL_MIN_SCALE = 8;

export const ZOOM_ANIMATION_DURATION_MS = 250;

export const COLOR_BACKGROUND = 0xFF121212;
export const COLOR_AREA_FILL = 0x40000000;
export const COLOR_DRAFT_AREA = 0x7FFFFFFF;
export const COLOR_SAVED_RAIL = 0xFFFFFFFF;
export const COLOR_PLAYER_MARKER = 0xFF4285F4;

/** @typedef {{centerX: number, centerZ: number, scale: number, width: number, height: number}} View */

/** @returns {View} a view centred on the origin at the default scale. */
export function createView(width = 0, height = 0) {
	return { centerX: 0, centerZ: 0, scale: DEFAULT_SCALE, width, height };
}

export function clampScale(scale) {
	if (!Number.isFinite(scale)) {
		return DEFAULT_SCALE;
	}
	return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));
}

/** @returns {{x: number, z: number}} the world position under a screen point. */
export function screenToWorld(view, screenX, screenY) {
	return {
		x: (screenX - view.width / 2) / view.scale + view.centerX,
		z: (screenY - view.height / 2) / view.scale + view.centerZ
	};
}

/** @returns {{x: number, y: number}} the screen position of a world position. */
export function worldToScreen(view, worldX, worldZ) {
	return {
		x: (worldX - view.centerX) * view.scale + view.width / 2,
		y: (worldZ - view.centerZ) * view.scale + view.height / 2
	};
}

/** @returns {View} a new view zoomed about a screen point, keeping the world point under it pinned. */
export function zoomAt(view, anchorScreenX, anchorScreenY, factor) {
	return zoomToAt(view, anchorScreenX, anchorScreenY, view.scale * factor);
}

/** @returns {View} a new view at the given scale, keeping the anchor over the same world point. */
export function zoomToAt(view, anchorScreenX, anchorScreenY, targetScale) {
	const before = screenToWorld(view, anchorScreenX, anchorScreenY);
	const scale = clampScale(targetScale);
	return {
		centerX: before.x - (anchorScreenX - view.width / 2) / scale,
		centerZ: before.z - (anchorScreenY - view.height / 2) / scale,
		scale,
		width: view.width,
		height: view.height
	};
}

export function panByScreenDelta(view, deltaX, deltaY) {
	return {
		...view,
		centerX: view.centerX - deltaX / view.scale,
		centerZ: view.centerZ - deltaY / view.scale
	};
}

export function centerOn(view, worldX, worldZ) {
	return { ...view, centerX: worldX, centerZ: worldZ };
}

/**
 * @param {number} paddingPx margin kept on each side.
 * @returns {View} a view showing the whole box, or the input unchanged when the box is degenerate.
 */
export function fitBounds(view, minX, minZ, maxX, maxZ, paddingPx = 32) {
	if (![minX, minZ, maxX, maxZ].every(Number.isFinite)) {
		return view;
	}

	const usableWidth = Math.max(1, view.width - paddingPx * 2);
	const usableHeight = Math.max(1, view.height - paddingPx * 2);
	const spanX = Math.max(1, maxX - minX);
	const spanZ = Math.max(1, maxZ - minZ);

	return {
		centerX: (minX + maxX) / 2,
		centerZ: (minZ + maxZ) / 2,
		scale: clampScale(Math.min(usableWidth / spanX, usableHeight / spanZ)),
		width: view.width,
		height: view.height
	};
}

export function easeOutCubic(progress) {
	const p = Math.min(1, Math.max(0, progress));
	return 1 - Math.pow(1 - p, 3);
}

export function isAnimationComplete(startMillis, nowMillis) {
	return nowMillis - startMillis >= ZOOM_ANIMATION_DURATION_MS;
}

export function interpolateZoom(fromView, toScale, anchorScreenX, anchorScreenY, progress) {
	const scale = fromView.scale + (toScale - fromView.scale) * easeOutCubic(progress);
	const anchor = screenToWorld(fromView, anchorScreenX, anchorScreenY);
	return {
		centerX: anchor.x - (anchorScreenX - fromView.width / 2) / scale,
		centerZ: anchor.z - (anchorScreenY - fromView.height / 2) / scale,
		scale,
		width: fromView.width,
		height: fromView.height
	};
}

export function visibleWorldBounds(view, marginPx = 0) {
	const topLeft = screenToWorld(view, -marginPx, -marginPx);
	const bottomRight = screenToWorld(view, view.width + marginPx, view.height + marginPx);
	return { minX: topLeft.x, minZ: topLeft.z, maxX: bottomRight.x, maxZ: bottomRight.z };
}

export function isAreaVisible(bounds, minX, minZ, maxX, maxZ) {
	return maxX >= bounds.minX && minX <= bounds.maxX && maxZ >= bounds.minZ && minZ <= bounds.maxZ;
}

export function outlineWorldWidth(scale, spanX, spanZ) {
	let screenWidth = OUTLINE_BASE_WORLD_WIDTH * scale;
	screenWidth = Math.min(OUTLINE_MAX_SCREEN_WIDTH, Math.max(OUTLINE_MIN_SCREEN_WIDTH, screenWidth));

	let worldWidth = screenWidth / scale;
	const maxWorldWidth = Math.min(Math.abs(spanX), Math.abs(spanZ)) * OUTLINE_MAX_AREA_RATIO;
	if (worldWidth > maxWorldWidth) {
		worldWidth = maxWorldWidth;
	}
	return worldWidth;
}

export function shouldDrawAreaLabel(scale, spanX, spanZ) {
	const longest = Math.max(Math.abs(spanX), Math.abs(spanZ));
	if (longest <= 0) {
		return false;
	}
	return scale >= AREA_LABEL_NUMERATOR / longest;
}

export function shouldDrawSavedRailLabel(scale) {
	return scale >= SAVED_RAIL_LABEL_MIN_SCALE;
}

/**
 * @returns {Array<{zFrom: number, zTo: number}>} offsets within the block, in block units.
 */
export function savedRailSlots(count) {
	if (!Number.isFinite(count) || count < 1) {
		return [];
	}
	const slots = [];
	for (let i = 0; i < count; i++) {
		slots.push({ zFrom: i / count, zTo: (i + 1) / count });
	}
	return slots;
}

/**
 * @param {Array<{pos: {x: number, z: number}, items: Array}>} groups saved rails grouped by block.
 * @returns {{item: any, group: any, slot: {zFrom: number, zTo: number}}|null}
 */
export function hitTestSavedRails(groups, worldX, worldZ) {
	if (!Array.isArray(groups)) {
		return null;
	}
	for (const group of groups) {
		if (!group || !group.pos || !Array.isArray(group.items) || group.items.length === 0) {
			continue;
		}
		const left = group.pos.x;
		const right = group.pos.x + 1;
		if (worldX < left || worldX >= right) {
			continue;
		}
		const slots = savedRailSlots(group.items.length);
		for (let i = 0; i < slots.length; i++) {
			const top = group.pos.z + slots[i].zFrom;
			const bottom = group.pos.z + slots[i].zTo;
			if (worldZ >= top && worldZ < bottom) {
				return { item: group.items[i], group, slot: slots[i] };
			}
		}
	}
	return null;
}

export function argbToRgba(argb) {
	const value = argb >>> 0;
	const alpha = ((value >>> 24) & 0xFF) / 255;
	return `rgba(${(value >>> 16) & 0xFF}, ${(value >>> 8) & 0xFF}, ${value & 0xFF}, ${round(alpha, 3)})`;
}

/** @returns {string|null} a 24-bit RGB as "#rrggbb". Null for 0. */
export function rgbToCss(rgb) {
	if (!Number.isFinite(rgb) || rgb === 0) {
		return null;
	}
	return '#' + (rgb & 0xFFFFFF).toString(16).padStart(6, '0');
}

export const FLY_DURATION_MS = 420;

export function interpolateView(fromView, toView, progress) {
	const eased = easeOutCubic(progress);
	const ratio = fromView.scale > 0 && toView.scale > 0 ? toView.scale / fromView.scale : 1;
	return {
		centerX: fromView.centerX + (toView.centerX - fromView.centerX) * eased,
		centerZ: fromView.centerZ + (toView.centerZ - fromView.centerZ) * eased,
		scale: fromView.scale * Math.pow(ratio, eased),
		width: fromView.width,
		height: fromView.height
	};
}

export const GRID_MIN_SCALE = 0.05;
const GRID_TARGET_PX = 48;

/** @returns {number} block spacing, or 0 when the grid should not be drawn. */
export function gridSpacing(scale) {
	if (!Number.isFinite(scale) || scale < GRID_MIN_SCALE) {
		return 0;
	}
	const candidates = [16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384];
	const target = GRID_TARGET_PX / scale;
	for (const candidate of candidates) {
		if (candidate >= target) {
			return candidate;
		}
	}
	return candidates[candidates.length - 1];
}

export const MAX_WORLD_COORDINATE = 30000000;
const MAX_WORLD_Y = 8192;

export function isSaneCoordinate(value) {
	return Number.isFinite(value) && Math.abs(value) <= MAX_WORLD_COORDINATE;
}

export function isSaneHeight(value) {
	return Number.isFinite(value) && Math.abs(value) <= MAX_WORLD_Y;
}

export function isSaneArea(corner1, corner2) {
	if (!corner1 || !corner2) {
		return false;
	}
	return isSaneCoordinate(corner1.x) && isSaneCoordinate(corner1.z)
		&& isSaneCoordinate(corner2.x) && isSaneCoordinate(corner2.z);
}

export function isSanePoint(x, z) {
	return isSaneCoordinate(x) && isSaneCoordinate(z);
}

export function snapRect(x1, y1, x2, y2) {
	const left = Math.round(Math.min(x1, x2));
	const top = Math.round(Math.min(y1, y2));
	const right = Math.round(Math.max(x1, x2));
	const bottom = Math.round(Math.max(y1, y2));
	return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) };
}

export function round(value, decimals) {
	const factor = Math.pow(10, decimals);
	return Math.round(value * factor) / factor;
}

export function snapToBlock(value) {
	return Number.isFinite(value) ? Math.floor(value) : 0;
}

export function snapAreaCorners(corner1, corner2) {
	const x1 = snapToBlock(corner1.x);
	const z1 = snapToBlock(corner1.z);
	const x2 = snapToBlock(corner2.x);
	const z2 = snapToBlock(corner2.z);
	return {
		corner1: { x: Math.min(x1, x2), z: Math.min(z1, z2) },
		corner2: { x: Math.max(x1, x2), z: Math.max(z1, z2) }
	};
}

export const AREA_CORNER_RADIUS_PX = 4;
export const SAVED_RAIL_CORNER_RADIUS_PX = 3;

export function clampCornerRadius(radius, width, height) {
	const limit = Math.min(Math.abs(width), Math.abs(height)) / 2;
	if (!Number.isFinite(radius) || radius <= 0 || limit <= 0) {
		return 0;
	}
	return Math.min(radius, limit);
}
