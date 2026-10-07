import { fitBounds, isSanePoint, worldToScreen } from './mapview.js';

export const ARROW_SIZE = 20;
export const CARD_MARGIN = 12;
export const CARD_OFFSET = ARROW_SIZE / 2 + CARD_MARGIN;
export const CARD_EDGE_MARGIN = 8;
export const CARD_CORNER_RADIUS = 16;
const ARROW_CORNER_INSET = CARD_CORNER_RADIUS;
export const SAVED_RAIL_FOCUS_SCALE = 8;

/**
 * @returns {{minX: number, minZ: number, maxX: number, maxZ: number}|null} the bounding box of a route.
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
	return { minX, minZ, maxX: maxX + 1, maxZ: maxZ + 1 };
}

/**
 * @param {string} kind        'station' | 'depot' | 'route' | 'platform' | 'siding'.
 * @param {object} object      the payload for that object, or null when it is gone.
 * @param {object} index       lists.js buildIndex, for resolving platform positions.
 * @param {object} view        the current view, for turning a world point into a screen point.
 * @returns {{animate: boolean, targetView: object|null, anchorScreen: {x: number, y: number},
 *           hasArea: boolean, isEmpty: boolean}}
 */
export function planFocus(kind, object, index, view) {
	const fallback = { animate: false, targetView: null, anchorScreen: centreOf(view), hasArea: false, isEmpty: false };
	if (!object || !view) {
		return fallback;
	}

	if (kind === 'route') {
		const bounds = routeBounds(object, index);
		if (!bounds) {
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

/** @returns {boolean} whether two views differ enough to be worth animating. */
export function sameView(a, b) {
	if (!a || !b) {
		return true;
	}
	const scaleClose = Math.abs(a.scale - b.scale) <= Math.abs(a.scale) * 1e-3;
	return Math.abs(a.centerX - b.centerX) < 0.5 && Math.abs(a.centerZ - b.centerZ) < 0.5 && scaleClose;
}

/**
 * @param {{x: number, y: number}} anchor   where the card should point, in map-area coordinates.
 * @param {{width: number, height: number}} card the card's measured size.
 * @param {{width: number, height: number}} area the map area's size.
 * @returns {{left, top, arrowX, arrowY, arrowVisible, arrowSide, docked, outside}}
 */
export function placePopover(anchor, card, area) {
	const areaWidth = Math.max(1, area.width);
	const areaHeight = Math.max(1, area.height);
	const cardWidth = Math.max(0, card.width);
	const cardHeight = Math.max(0, card.height);

	const anchorX = Number.isFinite(anchor.x) ? anchor.x : areaWidth / 2;
	const anchorY = Number.isFinite(anchor.y) ? anchor.y : areaHeight / 2;
	const outside = anchorX < 0 || anchorX > areaWidth || anchorY < 0 || anchorY > areaHeight;

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

	const preferredLeft = anchorX + CARD_OFFSET;
	const left = clamp(preferredLeft, CARD_EDGE_MARGIN, areaWidth - cardWidth - CARD_EDGE_MARGIN);
	const top = clamp(anchorY - cardHeight / 2, CARD_EDGE_MARGIN, areaHeight - cardHeight - CARD_EDGE_MARGIN);

	const reachesHorizontally = left >= preferredLeft - 1;
	const reachesVertically = anchorY >= top + ARROW_CORNER_INSET && anchorY <= top + cardHeight - ARROW_CORNER_INSET;
	const arrowVisible = reachesHorizontally && reachesVertically;

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
		arrowSide: left > anchorX ? 'left' : 'right',
		docked: false,
		outside
	};
}

function clamp(value, min, max) {
	if (max < min) {
		return min;
	}
	return Math.min(max, Math.max(min, value));
}
