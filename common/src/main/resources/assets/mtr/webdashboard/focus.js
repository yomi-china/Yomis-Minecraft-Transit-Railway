/*
 * Where to fly the map, and where to put the editing card. Pure logic: no DOM, no canvas, no state.
 *
 * Split out for the same reason as mapview.js and mapedit.js - this is arithmetic that is easy to get
 * subtly wrong and impossible to eyeball, so it is asserted in Node by test-focus.mjs.
 *
 * The interaction it serves, decided in spilt-3.4.md: one edit entry per list row, the map flies to
 * the object, and a non-modal MD3 card appears next to it.
 */

import { fitBounds, isSanePoint, worldToScreen } from './mapview.js';

/**
 * The arrow's length along the axis it crosses, which is also the width of the box it occupies.
 *
 * Positioned so that its midpoint lands on the anchor, the box covers `anchor +- ARROW_SIZE / 2`, and the
 * card then starts `ARROW_SIZE / 2 + CARD_MARGIN` from the anchor. Deriving the card's offset from that
 * rather than picking both numbers by eye is the difference between an arrow that points at the object and
 * one that points near it - an earlier attempt looked plausible and was 42 pixels off.
 */
export const ARROW_SIZE = 20;

/** Clear space between the arrow's base and the card's edge. */
export const CARD_MARGIN = 12;

/** The offset from the anchor to the card's near edge. Derived, not chosen. */
export const CARD_OFFSET = ARROW_SIZE / 2 + CARD_MARGIN;

/** Clear space between the card and the edge of the map area. */
export const CARD_EDGE_MARGIN = 8;

/** The card's corner radius, matching the MD3 shape token used in index.css. */
export const CARD_CORNER_RADIUS = 16;

/**
 * How far the arrow must stay from the card's rounded corners for it to look attached.
 *
 * Must be at least the corner radius, or the arrow lands on the curve. And the arrow only reaches the
 * anchor when the card is not pushed off its preferred offset, which the test asserts directly.
 */
const ARROW_CORNER_INSET = CARD_CORNER_RADIUS;

/** Scale raised to at least this when focusing a single saved rail, matching the game's find(BlockPos). */
export const SAVED_RAIL_FOCUS_SCALE = 8;

/** Scale raised to at least this when focusing an area. */
export const AREA_FOCUS_SCALE = 2;

/**
 * The bounding box of a route, from the positions of the platforms it stops at.
 *
 * The route payload carries platform *ids*, not coordinates, so the positions have to come from the
 * platform list - which is what `index` (lists.js buildIndex) is for.
 *
 * Each platform occupies one block, so the box is grown by one on the far edges: without that, fitBounds
 * frames the platform's near corner and clips the rest of the last block.
 *
 * @returns {{minX: number, minZ: number, maxX: number, maxZ: number}|null} null when the route has no
 *          stops at all, which is a legal state - an empty route is allowed, so this must not throw.
 */
export function routeBounds(route, index) {
	if (!route || !index || !index.platformById) {
		return null;
	}

	let minX = Infinity;
	let minZ = Infinity;
	let maxX = -Infinity;
	let maxZ = -Infinity;

	(route.platformIds || []).forEach(stop => {
		if (!stop) {
			return;
		}
		const platform = index.platformById.get(stop.platformId);
		if (!platform || !isSanePoint(platform.midX, platform.midZ)) {
			return;
		}
		minX = Math.min(minX, platform.midX);
		minZ = Math.min(minZ, platform.midZ);
		maxX = Math.max(maxX, platform.midX);
		maxZ = Math.max(maxZ, platform.midZ);
	});

	if (minX === Infinity) {
		return null;
	}
	// Grown by one block on the far edges only: the platform's own block spans [x, x + 1).
	return { minX, minZ, maxX: maxX + 1, maxZ: maxZ + 1 };
}

/**
 * What the map should do to bring an object into view, and where on screen the card should point.
 *
 * @param {string} kind        'station' | 'depot' | 'route' | 'platform' | 'siding'.
 * @param {object} object      the payload for that object, or null when it is gone.
 * @param {object} index       lists.js buildIndex, for resolving platform positions.
 * @param {object} view        the current view, for turning a world point into a screen point.
 * @returns {{animate: boolean, targetView: object|null, anchorScreen: {x: number, y: number},
 *           hasArea: boolean, isEmpty: boolean}}
 *          `animate` false means the view is already correct and flying would be a no-op jitter.
 *          `anchorScreen` is where the card should point, in map-area local coordinates.
 */
export function planFocus(kind, object, index, view) {
	const fallback = { animate: false, targetView: null, anchorScreen: centreOf(view), hasArea: false, isEmpty: false };
	if (!object || !view) {
		return fallback;
	}

	if (kind === 'route') {
		const bounds = routeBounds(object, index);
		if (!bounds) {
			// A route with no stops. Nothing to fly to, so the card is pinned to the middle of the view;
			// the caller explains why in the card.
			return { ...fallback, isEmpty: true };
		}
		const targetView = fitBounds(view, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ, 48);
		return {
			animate: !sameView(view, targetView),
			targetView,
			anchorScreen: worldToScreen(targetView, (bounds.minX + bounds.maxX) / 2, (bounds.minZ + bounds.maxZ) / 2),
			hasArea: true,
			isEmpty: false
		};
	}

	if (kind === 'station' || kind === 'depot') {
		if (!object.hasArea || !object.corner1 || !object.corner2) {
			// No selection drawn yet. Nothing to fly to; the card points at the middle and offers to draw one.
			return fallback;
		}
		const minX = Math.min(object.corner1.x, object.corner2.x);
		const minZ = Math.min(object.corner1.z, object.corner2.z);
		const maxX = Math.max(object.corner1.x, object.corner2.x);
		const maxZ = Math.max(object.corner1.z, object.corner2.z);
		if (!isSanePoint(minX, minZ) || !isSanePoint(maxX, maxZ)) {
			return fallback;
		}

		const targetView = fitBounds(view, minX, minZ, maxX, maxZ, 64);
		return {
			animate: !sameView(view, targetView),
			targetView,
			anchorScreen: worldToScreen(targetView, (minX + maxX) / 2, (minZ + maxZ) / 2),
			hasArea: true,
			isEmpty: false
		};
	}

	// A platform or siding: a single block, so centre on it and raise the scale, like the game's find.
	if (!isSanePoint(object.midX, object.midZ)) {
		return fallback;
	}
	const scale = Math.max(view.scale, SAVED_RAIL_FOCUS_SCALE);
	const targetView = { ...view, centerX: object.midX + 0.5, centerZ: object.midZ + 0.5, scale };
	return {
		animate: !sameView(view, targetView),
		targetView,
		anchorScreen: worldToScreen(targetView, object.midX + 0.5, object.midZ + 0.5),
		hasArea: false,
		isEmpty: false
	};
}

function centreOf(view) {
	return view ? { x: view.width / 2, y: view.height / 2 } : { x: 0, y: 0 };
}

/**
 * @returns {boolean} whether two views differ enough to be worth animating. A sub-pixel difference would
 *          start and finish a 420 ms animation that goes nowhere, which reads as a stutter.
 */
export function sameView(a, b) {
	if (!a || !b) {
		return true;
	}
	const scaleClose = Math.abs(a.scale - b.scale) <= Math.abs(a.scale) * 1e-3;
	return Math.abs(a.centerX - b.centerX) < 0.5 && Math.abs(a.centerZ - b.centerZ) < 0.5 && scaleClose;
}

/**
 * Places the editing card next to the point it describes.
 *
 * The approach is "compute then clamp" rather than "flip to the other side when it does not fit".
 * Flipping looks appealing but misbehaves on a card that is centred, where the two placements are
 * almost equally bad and the card jumps sides as the view moves; it also has to be re-decided every
 * time the card's height changes, which is every time a different object is edited.
 *
 * The one rule that matters more than the rest: <b>if the arrow cannot reach the anchor, do not draw
 * an arrow.</b> An arrow pointing at empty map is worse than no arrow, because it tells the user the
 * card belongs to something it does not.
 *
 * @param {{x: number, y: number}} anchor   where the card should point, in map-area coordinates.
 * @param {{width: number, height: number}} card the card's measured size.
 * @param {{width: number, height: number}} area the map area's size.
 * @returns {{left, top, arrowX, arrowY, arrowVisible, arrowSide, docked, outside}}
 *          `docked` true means the card could not be placed beside the anchor at all and is pinned to
 *          the bottom edge, full width; `outside` true means the anchor is off screen entirely.
 */
export function placePopover(anchor, card, area) {
	const areaWidth = Math.max(1, area.width);
	const areaHeight = Math.max(1, area.height);
	const cardWidth = Math.max(0, card.width);
	const cardHeight = Math.max(0, card.height);

	const anchorX = Number.isFinite(anchor.x) ? anchor.x : areaWidth / 2;
	const anchorY = Number.isFinite(anchor.y) ? anchor.y : areaHeight / 2;
	const outside = anchorX < 0 || anchorX > areaWidth || anchorY < 0 || anchorY > areaHeight;

	// Not enough width to sit beside the anchor with a usable card: dock it along the bottom instead.
	// The threshold includes a second edge margin so the card does not end up jammed against the edge with
	// the arrow's gap collapsed, which looks like a bug even though it is technically inside.
	const docked = cardWidth + CARD_OFFSET + CARD_EDGE_MARGIN > areaWidth;
	if (docked) {
		const height = Math.min(cardHeight, Math.max(80, areaHeight - CARD_EDGE_MARGIN * 2));
		return {
			left: CARD_EDGE_MARGIN,
			top: Math.max(CARD_EDGE_MARGIN, areaHeight - height - CARD_EDGE_MARGIN),
			width: areaWidth - CARD_EDGE_MARGIN * 2,
			arrowX: 0,
			arrowY: 0,
			arrowVisible: false,
			arrowSide: 'left',
			docked: true,
			outside
		};
	}

	// Preferred placement: to the right of the anchor, vertically centred on it. The card then covers the
	// least interesting part of the map, and the object stays visible next to it.
	const preferredLeft = anchorX + CARD_OFFSET;
	const left = clamp(preferredLeft, CARD_EDGE_MARGIN, areaWidth - cardWidth - CARD_EDGE_MARGIN);
	const top = clamp(anchorY - cardHeight / 2, CARD_EDGE_MARGIN, areaHeight - cardHeight - CARD_EDGE_MARGIN);

	// The arrow can only reach the anchor if the card was not pushed away from it horizontally and the
	// anchor sits within the card's vertical span.
	//
	// The tolerance is a pixel, not a rounding epsilon: `preferredLeft` and `left` differ by a few ULPs at
	// most, and treating that as "pushed away" would hide the arrow in the common case.
	const reachesHorizontally = left >= preferredLeft - 1;
	const reachesVertically = anchorY >= top + ARROW_CORNER_INSET && anchorY <= top + cardHeight - ARROW_CORNER_INSET;
	const arrowVisible = reachesHorizontally && reachesVertically;

	// The arrow box, measured from the card's LEFT edge - the same origin as `left`, so the caller positions
	// both with one rule, and its midpoint lands on the anchor whenever the card is not pushed off its
	// preferred offset.
	//
	// Card to the RIGHT of the anchor: the anchor lies CARD_OFFSET to the left of the card's edge, so the box
	// centre is at minus CARD_OFFSET from that edge. Card to the LEFT: mirrored, at cardWidth - CARD_OFFSET.
	const arrowCentre = left > anchorX ? -CARD_OFFSET : cardWidth - CARD_OFFSET;
	const arrowX = arrowCentre - ARROW_SIZE / 2;
	const arrowY = clamp(anchorY - top, ARROW_CORNER_INSET, cardHeight - ARROW_CORNER_INSET);

	return {
		left,
		top,
		width: cardWidth,
		arrowX,
		arrowY,
		arrowVisible,
		// The arrow sits on the card's left edge unless the card had to be pushed left of the anchor.
		arrowSide: left > anchorX ? 'left' : 'right',
		docked: false,
		outside
	};
}

function clamp(value, min, max) {
	if (max < min) {
		// A card larger than the area: prefer the minimum so at least its top-left is on screen.
		return min;
	}
	return Math.min(max, Math.max(min, value));
}

/**
 * Where a saved rail's centre sits in map-area coordinates, so the map and the focus code agree on what
 * "the block" means.
 */
export function savedRailCentre(platform, view) {
	if (!platform || !isSanePoint(platform.midX, platform.midZ)) {
		return null;
	}
	return worldToScreen(view, platform.midX + 0.5, platform.midZ + 0.5);
}
