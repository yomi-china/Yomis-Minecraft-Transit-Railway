/*
 * Pure geometry for the dashboard map. No DOM, no canvas, no state.
 *
 * The transform is the game's WidgetMap transform:
 *
 *     screenX = (worldX - centerX) * scale + width  / 2
 *     screenZ = (worldZ - centerZ) * scale + height / 2
 *
 * World X maps to screen X and world Z to screen Y; there is no rotation and no Y (height) anywhere, the
 * game's map being a flat top-down view.
 */

/* Zoom range. Deliberately wider than the game's 1/128 .. 64: a browser viewport is far larger than the
 * game's map panel, so the Minecraft UI's limits do not apply. */
export const MIN_SCALE = 1 / 2048;
export const MAX_SCALE = 256;

/** The game's initial scale: one pixel per block. */
export const DEFAULT_SCALE = 1;

/* Rendering constants that mirror the game so the two look alike. Hard-coded deliberately: if the game
 * changes one, test-map.mjs fails and prompts a review. */

/** WidgetMap.OUTLINE_BASE_WORLD_WIDTH - outline width in world units, scaled by zoom. */
export const OUTLINE_BASE_WORLD_WIDTH = 0.9;
/** WidgetMap.OUTLINE_MIN_SCREEN_WIDTH, in CSS pixels. */
export const OUTLINE_MIN_SCREEN_WIDTH = 0.6;
/** WidgetMap.OUTLINE_MAX_SCREEN_WIDTH, in CSS pixels. */
export const OUTLINE_MAX_SCREEN_WIDTH = 2.5;
/** WidgetMap.OUTLINE_MAX_AREA_RATIO - an outline may not exceed this share of the shorter side. */
export const OUTLINE_MAX_AREA_RATIO = 0.4;
/** WidgetMap.canDrawAreaText: scale >= 80 / max(|dx|, |dz|). */
export const AREA_LABEL_NUMERATOR = 80;
/** WidgetMap: platform and siding names appear at scale >= 8. */
export const SAVED_RAIL_LABEL_MIN_SCALE = 8;

/** WidgetMap.ZOOM_ANIMATION_DURATION. */
export const ZOOM_ANIMATION_DURATION_MS = 250;

/** Game colours, as ARGB integers, converted for canvas use by argbToRgba. */
export const COLOR_BACKGROUND = 0xFF121212;
export const COLOR_AREA_FILL = 0x40000000;
export const COLOR_DRAFT_AREA = 0x7FFFFFFF;
export const COLOR_SAVED_RAIL = 0xFFFFFFFF;
export const COLOR_PLAYER_MARKER = 0xFF4285F4;

/**
 * @typedef {{centerX: number, centerZ: number, scale: number, width: number, height: number}} View
 */

/** @returns {View} a view centred on the origin at the default scale. */
export function createView(width = 0, height = 0) {
	return { centerX: 0, centerZ: 0, scale: DEFAULT_SCALE, width, height };
}

/** Clamps a scale into the dashboard's range. */
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

/**
 * @returns {View} a new view zoomed about a screen point, keeping the world point under it pinned.
 *
 * The scale is clamped about that same point, not before anchoring: clamping first would slide the map
 * whenever the zoom hits a limit.
 */
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

/** Pans by a screen-space delta. Matches the game's `centerX -= deltaX / scale`. */
export function panByScreenDelta(view, deltaX, deltaY) {
	return {
		...view,
		centerX: view.centerX - deltaX / view.scale,
		centerZ: view.centerZ - deltaY / view.scale
	};
}

/** Centres on a world point without changing the zoom. */
export function centerOn(view, worldX, worldZ) {
	return { ...view, centerX: worldX, centerZ: worldZ };
}

/**
 * Frames a world-space box, leaving a margin. The game's "find" only recentres and raises the scale to a
 * minimum, which leaves an area larger than the view still clipped.
 *
 * @param {number} paddingPx margin kept on each side.
 * @returns {View} a view showing the whole box, or the input unchanged when the box is degenerate.
 */
export function fitBounds(view, minX, minZ, maxX, maxZ, paddingPx = 32) {
	if (![minX, minZ, maxX, maxZ].every(Number.isFinite)) {
		return view;
	}

	const usableWidth = Math.max(1, view.width - paddingPx * 2);
	const usableHeight = Math.max(1, view.height - paddingPx * 2);
	// Floored at one block: a zero-width selection would divide by zero.
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

/** The game's zoom easing: 1 - (1 - p)^3. */
export function easeOutCubic(progress) {
	const p = Math.min(1, Math.max(0, progress));
	return 1 - Math.pow(1 - p, 3);
}

/** @returns {boolean} whether a zoom animation has finished. */
export function isAnimationComplete(startMillis, nowMillis) {
	return nowMillis - startMillis >= ZOOM_ANIMATION_DURATION_MS;
}

/**
 * Interpolates a view during a zoom animation. Only the scale is eased; the centre is solved from the
 * anchor so the point under the cursor stays put, as the game does when it recomputes the centre per frame.
 */
export function interpolateZoom(fromView, toScale, anchorScreenX, anchorScreenY, progress) {
	const scale = fromView.scale + (toScale - fromView.scale) * easeOutCubic(progress);
	// Solved against the original view, so repeated frames do not compound a rounding drift.
	const anchor = screenToWorld(fromView, anchorScreenX, anchorScreenY);
	return {
		centerX: anchor.x - (anchorScreenX - fromView.width / 2) / scale,
		centerZ: anchor.z - (anchorScreenY - fromView.height / 2) / scale,
		scale,
		width: fromView.width,
		height: fromView.height
	};
}

// ---- what to draw -------------------------------------------------------

/**
 * @returns {{minX: number, minZ: number, maxX: number, maxZ: number}} the world rectangle the view covers,
 *          grown by a margin in screen pixels so partially visible shapes are not skipped.
 */
export function visibleWorldBounds(view, marginPx = 0) {
	const topLeft = screenToWorld(view, -marginPx, -marginPx);
	const bottomRight = screenToWorld(view, view.width + marginPx, view.height + marginPx);
	return { minX: topLeft.x, minZ: topLeft.z, maxX: bottomRight.x, maxZ: bottomRight.z };
}

/** @returns {boolean} whether a world rectangle intersects the visible area. */
export function isAreaVisible(bounds, minX, minZ, maxX, maxZ) {
	return maxX >= bounds.minX && minX <= bounds.maxX && maxZ >= bounds.minZ && minZ <= bounds.maxZ;
}

/**
 * Outline width in world units. Mirrors WidgetMap.drawOutlineFromWorldCoords: a fixed world width scaled by
 * zoom, clamped to a screen-pixel band, then refused if it exceeds a share of the area's shorter side (so a
 * tiny selection does not become a solid block of colour).
 */
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

/**
 * Whether an area is big enough on screen to justify drawing its name. Mirrors WidgetMap.canDrawAreaText:
 * the threshold depends on the area's own size, so a large station labels itself sooner than a small one.
 */
export function shouldDrawAreaLabel(scale, spanX, spanZ) {
	const longest = Math.max(Math.abs(spanX), Math.abs(spanZ));
	if (longest <= 0) {
		return false;
	}
	return scale >= AREA_LABEL_NUMERATOR / longest;
}

/** Whether platform and siding names should be drawn at this zoom. */
export function shouldDrawSavedRailLabel(scale) {
	return scale >= SAVED_RAIL_LABEL_MIN_SCALE;
}

/**
 * Splits one block into one slot per saved rail sitting on it, mirroring WidgetMap.mouseOnSavedRail: several
 * platforms or sidings sharing a block are stacked along Z in equal slices. The same slicing is used for
 * drawing and for hit testing, so the two cannot disagree about which rail is where.
 *
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
 * Finds which saved rail a world point hits, replicating the game's test. X is left-closed and right-open,
 * Z is tested against the per-slot boundaries; the asymmetry is the game's, so two adjacent blocks cannot
 * both claim the point between them.
 *
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

// ---- colours ------------------------------------------------------------

/**
 * Converts the game's ARGB integer to a CSS rgba() string.
 *
 * @param {number} argb a 32-bit ARGB value.
 * @returns {string} e.g. "rgba(0, 0, 0, 0.251)".
 */
export function argbToRgba(argb) {
	const value = argb >>> 0;
	const alpha = ((value >>> 24) & 0xFF) / 255;
	return `rgba(${(value >>> 16) & 0xFF}, ${(value >>> 8) & 0xFF}, ${value & 0xFF}, ${round(alpha, 3)})`;
}

/**
 * @returns {string|null} a 24-bit RGB as "#rrggbb". Null for 0, which means "never set" - drawing it would
 *          produce an invisible black shape, so callers show an outlined empty swatch instead.
 */
export function rgbToCss(rgb) {
	if (!Number.isFinite(rgb) || rgb === 0) {
		return null;
	}
	return '#' + (rgb & 0xFFFFFF).toString(16).padStart(6, '0');
}

/** Duration of a pan-and-zoom between two views. Longer than a wheel step, because it is a jump. */
export const FLY_DURATION_MS = 420;

/**
 * Interpolates between two arbitrary views, for flying the map to an object.
 *
 * Unlike {@link interpolateZoom}, which holds a world point fixed under a fixed screen point, this moves the
 * centre freely. The scale is interpolated <b>geometrically</b>: perceived zoom is logarithmic, so a linear
 * ramp from 1x to 8x sits at 4.5 halfway through and reads as "barely moved, then suddenly enormous",
 * whereas the geometric midpoint of sqrt(8) reads as a constant rate.
 */
export function interpolateView(fromView, toView, progress) {
	const eased = easeOutCubic(progress);
	// Guarded, because a non-positive scale on either side would make the whole result NaN.
	const ratio = fromView.scale > 0 && toView.scale > 0 ? toView.scale / fromView.scale : 1;
	return {
		centerX: fromView.centerX + (toView.centerX - fromView.centerX) * eased,
		centerZ: fromView.centerZ + (toView.centerZ - fromView.centerZ) * eased,
		scale: fromView.scale * Math.pow(ratio, eased),
		width: fromView.width,
		height: fromView.height
	};
}

// ---- grid ---------------------------------------------------------------

/**
 * Lowest zoom at which the reference grid is drawn. The line count is `viewport / (spacing * scale)`, so a
 * low enough scale draws thousands of lines and stalls the frame; above this threshold the spacing has
 * already snapped to 1024 blocks, keeping a wide viewport to a couple of dozen lines.
 */
export const GRID_MIN_SCALE = 0.05;

/** Target spacing between grid lines, in screen pixels before snapping. */
const GRID_TARGET_PX = 48;

/**
 * Spacing of the reference grid, in blocks. A scale reference only, since no terrain is drawn, so it is
 * chosen to stay legible rather than to mean anything: the candidates are block counts a player thinks in.
 * Taking the first at or above the target spacing makes the value change rarely as the user zooms, which is
 * what stops the grid shimmering.
 *
 * @returns {number} block spacing, or 0 when the grid should not be drawn.
 */
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

// ---- sanity limits ------------------------------------------------------

/**
 * Largest horizontal world coordinate Minecraft accepts. Anything beyond it cannot come from a legitimate
 * build, and letting such a value into a bounding box pushes the whole map to a scale of ~0.0005 where every
 * real selection collapses into a single pixel.
 */
export const MAX_WORLD_COORDINATE = 30000000;

/** Y is not comparable to X and Z: a world's build height is a few thousand blocks at most. */
const MAX_WORLD_Y = 8192;

/** @returns {boolean} whether a horizontal coordinate could plausibly exist in a Minecraft world. */
export function isSaneCoordinate(value) {
	return Number.isFinite(value) && Math.abs(value) <= MAX_WORLD_COORDINATE;
}

/** @returns {boolean} whether a height could plausibly exist. */
export function isSaneHeight(value) {
	return Number.isFinite(value) && Math.abs(value) <= MAX_WORLD_Y;
}

/**
 * @returns {boolean} whether both corners of an area are usable.
 *
 * A corrupt corner is treated as "no selection" rather than clamped: clamping would invent a selection the
 * player never drew, which is worse than drawing nothing.
 */
export function isSaneArea(corner1, corner2) {
	if (!corner1 || !corner2) {
		return false;
	}
	return isSaneCoordinate(corner1.x) && isSaneCoordinate(corner1.z)
		&& isSaneCoordinate(corner2.x) && isSaneCoordinate(corner2.z);
}

/** @returns {boolean} whether a saved rail's block position is usable. */
export function isSanePoint(x, z) {
	return isSaneCoordinate(x) && isSaneCoordinate(z);
}

// ---- pixel snapping -----------------------------------------------------

/**
 * Snaps a world rectangle to whole device pixels.
 *
 * Adjacent blocks would otherwise round independently and leave a one-pixel seam that reads as a grid line,
 * and rounding the near edge down and the far edge up guarantees a visible sliver for a shape thinner than a
 * pixel - which is the useful behaviour when zoomed far out.
 */
export function snapRect(x1, y1, x2, y2) {
	const left = Math.round(Math.min(x1, x2));
	const top = Math.round(Math.min(y1, y2));
	const right = Math.round(Math.max(x1, x2));
	const bottom = Math.round(Math.max(y1, y2));
	return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) };
}

/** Rounds to a fixed number of decimals, to keep generated CSS strings short and comparable. */
export function round(value, decimals) {
	const factor = Math.pow(10, decimals);
	return Math.round(value * factor) / factor;
}

/**
 * Snaps a world coordinate down to the block containing it.
 *
 * A selection is a set of blocks, not a pair of points: AreaBase.inArea compares block coordinates and a
 * platform's midpoint is a BlockPos, so a corner of (80.93, 23.4) means the same selection as (80, 23).
 * Floors rather than rounds, because the block the cursor is inside is the block the user pointed at - this
 * is also what the game's own conversion does, which is what makes the two agree.
 */
export function snapToBlock(value) {
	return Number.isFinite(value) ? Math.floor(value) : 0;
}

/**
 * Floors both corners of a selection to block boundaries and orders them low to high.
 *
 * No minimum size is imposed: mapedit.dragToEdit already applies the game's rule during the drag, so a
 * second one here would report a selection one block larger than the one the user watched being drawn.
 */
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

// ---- rounded rectangles -------------------------------------------------

/** Corner radius for a selection outline, in CSS pixels. Fixed on screen, not in world units. */
export const AREA_CORNER_RADIUS_PX = 4;

/** Corner radius for a platform or siding block, in CSS pixels, subject to the half-size clamp. */
export const SAVED_RAIL_CORNER_RADIUS_PX = 3;

/**
 * Corner radius that will actually be used, never more than half the shorter side. A larger radius makes
 * roundRect produce a lens or a pill rather than a rounded rectangle, which at low zoom - where a block is a
 * couple of pixels - looks like a smudge.
 */
export function clampCornerRadius(radius, width, height) {
	const limit = Math.min(Math.abs(width), Math.abs(height)) / 2;
	if (!Number.isFinite(radius) || radius <= 0 || limit <= 0) {
		return 0;
	}
	return Math.min(radius, limit);
}
