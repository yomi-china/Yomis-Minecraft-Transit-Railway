import {
	COLOR_BACKGROUND,
	COLOR_PLAYER_MARKER,
	argbToRgba,
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
} from './mapview.js?v=20';
import { beginEdit, cancelEdit, dragToEdit, endEditDrag, getDraftBounds, isEditing } from './mapedit.js?v=20';
import { planFocus, sameView } from './focus.js?v=20';

const DRAG_THRESHOLD_PX = 4;

export const ZOOM_STEP = 1.5;

const ZOOM_EASING_FACTOR = 0.14;
const ZOOM_SETTLE_RATIO = 0.002;
const SELECTION_RING_COLOUR = argbToRgba(COLOR_PLAYER_MARKER);
const FOCUS_PLAYER_SCALE = 8;

const el = {
	canvas: null,
	pointer: null
};

const mapState = {
	world: null,
	index: null,
	mode: 'all',
	tab: 'stations',
	selectedId: null,
	edit: null
};

const view = createView();

let savedRailGroups = [];
let skippedEntries = 0;

let handlers = {};
let context = null;
let animation = null;
let pointerState = null;
let needsResize = true;
let viewHasBeenPlaced = false;
let zoomTargetScale = null;
let zoomAnchorScreenX = 0;
let zoomAnchorScreenY = 0;
let flightAnimation = null;

export function init(canvas, callbacks) {
	el.canvas = canvas;
	handlers = callbacks || {};
	context = canvas.getContext('2d');

	el.pointer = { x: 0, z: 0, valid: false };

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

export function dispose() {
	cancelFlight();
	if (animation != null) {
		cancelAnimationFrame(animation);
		animation = null;
	}
}

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

export function beginAreaEdit(kind, object) {
	mapState.edit = beginEdit(kind, object.id, object);
	canvasCursor();
	redraw();
	notifyDraft();
}

export function cancelAreaEdit() {
	mapState.edit = cancelEdit();
	canvasCursor();
	redraw();
	notifyDraft();
}

export function isAreaEditing() {
	return isEditing(mapState.edit);
}

export function getAreaDraft() {
	return getDraftBounds(mapState.edit);
}

function canvasCursor() {
	if (el.canvas) {
		const area = el.canvas.parentElement;
		if (area) {
			area.classList.toggle('is-editing', isEditing(mapState.edit));
		}
	}
}

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
	return true;
}

export function getView() {
	return view;
}

export function zoomBy(factor) {
	zoomTargetScale = clampScale((zoomTargetScale != null ? zoomTargetScale : view.scale) * factor);
	zoomAnchorScreenX = view.width / 2;
	zoomAnchorScreenY = view.height / 2;
	startZoomAnimation();
}

function applyView(next) {
	view.centerX = next.centerX;
	view.centerZ = next.centerZ;
	view.scale = next.scale;
	viewHasBeenPlaced = true;
	redraw();
}

function placeInitialView() {
	const player = firstPlayer();
	if (player && isSanePoint(player.x, player.z)) {
		view.centerX = player.x;
		view.centerZ = player.z;
		view.scale = clampScale(1);
		viewHasBeenPlaced = true;
		return;
	}
	const bounds = computeContentBounds();
	if (bounds) {
		const fitted = fitBounds(view, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ);
		view.centerX = fitted.centerX;
		view.centerZ = fitted.centerZ;
		view.scale = fitted.scale;
		viewHasBeenPlaced = true;
		if (skippedEntries > 0) {
			console.warn('[MTR-WebDashboard] left out ' + skippedEntries + ' entr(y/ies) whose coordinates are outside the world boundary');
		}
	}
}

function firstPlayer() {
	const players = mapState.world && Array.isArray(mapState.world.players) ? mapState.world.players : [];
	return players.length > 0 ? players[0] : null;
}

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

export function redraw() {
	if (!context || !el.canvas) {
		return;
	}
	resizeIfNeeded();

	context.setTransform(1, 0, 0, 1, 0, 0);
	context.clearRect(0, 0, el.canvas.width, el.canvas.height);
	context.setTransform(devicePixelRatio(), 0, 0, devicePixelRatio(), 0, 0);

	drawTerrain();
	drawAreas();
	drawSavedRails();
	drawDraft();
	drawLabels();
	drawPlayers();
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

	if (needsResize || el.canvas.width !== deviceWidth || el.canvas.height !== deviceHeight) {
		el.canvas.width = deviceWidth;
		el.canvas.height = deviceHeight;
		needsResize = false;
	}

	view.width = cssWidth;
	view.height = cssHeight;
}

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

function drawAreas() {
	const key = areaKeyForTab();
	if (!key) {
		return;
	}

	const bounds = visibleWorldBounds(view, 4);
	(mapState.world && mapState.world[key] ? mapState.world[key] : []).forEach(area => {
		if (!area.hasArea || !isSaneArea(area.corner1, area.corner2)) {
			return;
		}
		if (key === 'depots' && !matchesMode(area)) {
			return;
		}
		if (!isAreaVisible(bounds, Math.min(area.corner1.x, area.corner2.x), Math.min(area.corner1.z, area.corner2.z), Math.max(area.corner1.x, area.corner2.x), Math.max(area.corner1.z, area.corner2.z))) {
			return;
		}

		const selected = area.id === mapState.selectedId;
		const editing = isEditing(mapState.edit) && area.id === mapState.edit.id;

		if (editing) {
			drawAreaShape(area, 'rgba(255, 255, 255, 0.35)', 'rgba(255, 255, 255, 0.06)');
			return;
		}

		const fill = selected ? 'rgba(255, 255, 255, 0.18)' : argbToRgba(0x40000000);
		const outline = rgbToCss(area.color) || 'rgba(255, 255, 255, 0.65)';
		drawAreaShape(area, outline, fill);
	});
}

function areaKeyForTab() {
	if (mapState.tab === 'depots') {
		return 'depots';
	}
	if (mapState.tab === 'stations') {
		return 'stations';
	}
	return null;
}

function matchesMode(object) {
	return mapState.mode === 'all' || object.transportMode === mapState.mode;
}

function drawAreaShape(area, outlineColor, fillColor) {
	const spanX = area.corner2.x - area.corner1.x;
	const spanZ = area.corner2.z - area.corner1.z;
	const width = outlineWorldWidth(view.scale, spanX, spanZ);
	if (!(width > 0)) {
		return;
	}

	const topLeft = worldToScreen(view, Math.min(area.corner1.x, area.corner2.x), Math.min(area.corner1.z, area.corner2.z));
	const bottomRight = worldToScreen(view, Math.max(area.corner1.x, area.corner2.x), Math.max(area.corner1.z, area.corner2.z));
	const rect = snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
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
			const selected = group.items[index].id === mapState.selectedId;
			fillWorldRoundedRect(
				group.pos.x, group.pos.z + slot.zFrom,
				group.pos.x + 1, group.pos.z + slot.zTo,
				selected ? 'rgba(255, 255, 255, 1)' : 'rgba(255, 255, 255, 0.6)',
				SAVED_RAIL_CORNER_RADIUS_PX
			);
		});
	});

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

function drawDraft() {
	const draft = getDraftBounds(mapState.edit);
	if (!draft) {
		return;
	}

	fillWorldRect(draft.minX, draft.minZ, draft.maxX, draft.maxZ, argbToRgba(0x7FFFFFFF));

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

function fillWorldRect(worldX1, worldZ1, worldX2, worldZ2, fillStyle) {
	const rect = worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2);
	context.fillStyle = fillStyle;
	context.fillRect(rect.x, rect.y, rect.width, rect.height);
}

function fillWorldRoundedRect(worldX1, worldZ1, worldX2, worldZ2, fillStyle, radiusPx) {
	const rect = worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2);
	const radius = clampCornerRadius(radiusPx, rect.width, rect.height);
	context.fillStyle = fillStyle;
	context.beginPath();
	context.roundRect(rect.x, rect.y, rect.width, rect.height, radius);
	context.fill();
}

function worldRectToScreen(worldX1, worldZ1, worldX2, worldZ2) {
	const topLeft = worldToScreen(view, Math.min(worldX1, worldX2), Math.min(worldZ1, worldZ2));
	const bottomRight = worldToScreen(view, Math.max(worldX1, worldX2), Math.max(worldZ1, worldZ2));
	return snapRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y);
}

function formatLabel(name) {
	return typeof name === 'string' ? name.replace(/\|/g, ' ').trim() : '';
}

function rebuildGroups() {
	savedRailGroups = [];
	if (!mapState.world) {
		return;
	}

	const source = mapState.tab === 'depots' ? mapState.world.sidings : mapState.world.platforms;
	const byBlock = new Map();

	(source || []).forEach(savedRail => {
		if (mapState.mode !== 'all' && savedRail.transportMode !== mapState.mode) {
			return;
		}
		if (!isSanePoint(savedRail.midX, savedRail.midZ)) {
			return;
		}
		const key = savedRail.midX + ',' + savedRail.midZ;
		if (!byBlock.has(key)) {
			byBlock.set(key, { pos: { x: savedRail.midX, z: savedRail.midZ }, items: [] });
		}
		byBlock.get(key).items.push(savedRail);
	});

	savedRailGroups = [...byBlock.values()].sort((a, b) => (a.pos.x - b.pos.x) || (a.pos.z - b.pos.z));
	savedRailGroups.forEach(group => group.items.sort((a, b) => String(a.id).localeCompare(String(b.id))));
}

function toCanvasPoint(event) {
	const rect = el.canvas.getBoundingClientRect();
	return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

function onPointerDown(event) {
	const point = toCanvasPoint(event);
	const editing = isEditing(mapState.edit);
	const pans = event.button === 1 || (!editing && event.button === 0) || (editing && event.button === 2);

	if (pans) {
		pointerState = { kind: 'pan', lastX: point.x, lastY: point.y, moved: false };
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
			notifyDraft();
		}
	} else {
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
		const kept = endEditDrag(mapState.edit);
		notifyDraft();
		const draft = getDraftBounds(mapState.edit);
		if (kept && draft && handlers.onEditArea) {
			handlers.onEditArea(snapAreaCorners(
				{ x: draft.minX, z: draft.minZ },
				{ x: draft.maxX, z: draft.maxZ }
			));
		}
		redraw();
		return;
	}

	if (state.kind === 'pan' && !state.moved && event.button === 0) {
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
	const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;

	cancelFlight();

	zoomTargetScale = clampScale((zoomTargetScale != null ? zoomTargetScale : view.scale) * factor);
	zoomAnchorScreenX = point.x;
	zoomAnchorScreenY = point.y;
	startZoomAnimation();
}

function startZoomAnimation() {
	if (animation != null) {
		return;
	}

	const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
	if (reduceMotion) {
		applyZoomFrame(1);
		zoomTargetScale = null;
		return;
	}

	const step = () => {
		animation = null;
		const remaining = applyZoomFrame(ZOOM_EASING_FACTOR);
		if (remaining) {
			animation = requestAnimationFrame(step);
		} else {
			zoomTargetScale = null;
		}
	};
	animation = requestAnimationFrame(step);
}

function applyZoomFrame(fraction) {
	const target = zoomTargetScale;
	if (target == null) {
		return false;
	}

	const anchorWorld = screenToWorld(view, zoomAnchorScreenX, zoomAnchorScreenY);
	const next = view.scale + (target - view.scale) * fraction;
	const done = Math.abs(target - next) / target < ZOOM_SETTLE_RATIO;
	const scale = clampScale(done ? target : next);

	view.scale = scale;
	view.centerX = anchorWorld.x - (zoomAnchorScreenX - view.width / 2) / scale;
	view.centerZ = anchorWorld.z - (zoomAnchorScreenY - view.height / 2) / scale;
	viewHasBeenPlaced = true;
	redraw();
	return !done;
}

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
	zoomTargetScale = null;

	const from = { ...view };
	const duration = durationMs != null ? durationMs : FLY_DURATION_MS;
	const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

	if (reduceMotion || !(duration > 0)) {
		applyView(to);
		return true;
	}

	const startedAt = performance.now();
	const step = () => {
		flightAnimation = null;
		const elapsed = performance.now() - startedAt;
		const progress = Math.min(1, elapsed / duration);
		applyView(interpolateView(from, to, progress));
		if (progress < 1) {
			flightAnimation = requestAnimationFrame(step);
		}
	};
	flightAnimation = requestAnimationFrame(step);
	return true;
}

export function cancelFlight() {
	if (flightAnimation != null) {
		cancelAnimationFrame(flightAnimation);
		flightAnimation = null;
	}
}

export function focusOnTarget(kind, object, index) {
	const plan = planFocus(kind, object, index, view);
	if (plan.targetView) {
		flyTo(plan.targetView);
	}
	return { animate: plan.animate, anchorScreen: plan.anchorScreen, hasArea: plan.hasArea, isEmpty: plan.isEmpty };
}

function notifyPointer() {
	if (handlers.onPointerMove) {
		handlers.onPointerMove(el.pointer.valid ? { x: el.pointer.x, z: el.pointer.z } : null);
	}
}

function notifyDraft() {
	if (handlers.onDraftChange) {
		handlers.onDraftChange(getDraftBounds(mapState.edit));
	}
}
