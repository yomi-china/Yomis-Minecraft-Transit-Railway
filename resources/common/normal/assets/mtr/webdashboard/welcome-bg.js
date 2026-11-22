// Draws the drifting metro-network backdrop of the visitor welcome screen.
//
// The geometry is generated once per resize in an artwork coordinate space, so the 45 degree
// segments stay at 45 degrees regardless of the window aspect. It is then rasterised into an
// offscreen canvas and only blitted each frame; the per-frame work is one drawImage plus a few
// gradient strokes for the moving highlights. That keeps the main thread cheap and lets the
// 30fps cap be a real cap rather than an aspiration.

const FRAME_INTERVAL = 1000 / 30;
const MAX_DPR = 2;
const THEME_EVENT = 'mtr-themechange';
const ART_WIDTH = 2400;
const ART_HEIGHT = 1500;
const PADDING = 48;
const HUB_COLUMNS = 14;
const HUB_ROWS = 9;
const HUB_MARGIN = 80;
const ROUTE_COUNT = 26;
const ROUTE_MIN_HUBS = 6;
const ROUTE_MAX_HUBS = 8;
const FLOW_COUNT = 20;
const MIN_PARALLEL_GAP = 38;
const MIN_PARALLEL_OVERLAP = 18;
const MIN_SEGMENT = 50;
const MIN_DIAGONAL = 50;
const DIRECTIONS = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];

// Exported for the test bench, which asserts the density and spacing the renderer promises.
export const NETWORK_LINE_COUNT = ROUTE_COUNT;
export const NETWORK_FLOW_COUNT = FLOW_COUNT;
export const NETWORK_MIN_GAP = 30;

function parseColor(value) {
	if (typeof value !== 'string') {
		return null;
	}
	const text = value.trim();
	const hex = text.replace(/^#/, '');
	if (/^[0-9a-f]{6}$/i.test(hex)) {
		return {
			r: parseInt(hex.slice(0, 2), 16),
			g: parseInt(hex.slice(2, 4), 16),
			b: parseInt(hex.slice(4, 6), 16)
		};
	}
	const rgb = text.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i);
	if (rgb) {
		return { r: Number(rgb[1]), g: Number(rgb[2]), b: Number(rgb[3]) };
	}
	return null;
}

function rgba(value, alpha) {
	const parsed = parseColor(value);
	if (!parsed) {
		return value;
	}
	return `rgba(${Math.round(parsed.r)}, ${Math.round(parsed.g)}, ${Math.round(parsed.b)}, ${alpha})`;
}

function cssColor(name, fallback) {
	if (typeof document === 'undefined') {
		return fallback;
	}
	const value = getComputedStyle(document.body || document.documentElement).getPropertyValue(name).trim();
	return value || fallback;
}

function readPalette() {
	const light = typeof document !== 'undefined' && document.body?.dataset.theme === 'light';
	const accent = cssColor('--ui-accent', '#90caf9');
	const accentText = cssColor('--ui-accent-text', accent);
	return {
		light,
		background: light ? '#f2f4f6' : '#121212',
		line: light ? '35, 58, 86' : '255, 255, 255',
		lineScale: light ? 1.55 : 1,
		tickLine: light ? '35, 58, 86' : '255, 255, 255',
		node: light ? 'rgba(35, 58, 86, 0.18)' : 'rgba(255, 255, 255, 0.18)',
		glow: accentText,
		glowAlpha: light ? 0.14 : 0.12,
		flow: accentText,
		flowAlphaScale: light ? 0.9 : 1,
		train: accentText,
		blend: light ? 'source-over' : 'lighter',
		shadow: rgba(accentText, light ? 0.28 : 0.55)
	};
}

function mulberry32(seed) {
	let value = seed >>> 0;
	return function random() {
		value = (value + 0x6D2B79F5) | 0;
		let t = Math.imul(value ^ (value >>> 15), 1 | value);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

function fitToViewport(width, height) {
	const scale = Math.max(width / ART_WIDTH, height / ART_HEIGHT) * 1.04;
	return {
		scale,
		offsetX: (width - ART_WIDTH * scale) / 2,
		offsetY: (height - ART_HEIGHT * scale) / 2
	};
}

function toScreen(point, fit) {
	return {
		x: fit.offsetX + point.x * fit.scale,
		y: fit.offsetY + point.y * fit.scale
	};
}

function addSegments(path) {
	let total = 0;
	const segments = [];
	for (let index = 1; index < path.points.length; index++) {
		const a = path.points[index - 1];
		const b = path.points[index];
		const length = Math.hypot(b.x - a.x, b.y - a.y);
		segments.push({ a, b, start: total, end: total + length });
		total += length;
	}
	path.segments = segments;
	path.total = total;
	return path;
}

function orientationOf(segment) {
	const dx = segment.b.x - segment.a.x;
	const dy = segment.b.y - segment.a.y;
	if (Math.abs(dy) < 1e-6) {
		return 0;
	}
	if (Math.abs(dx) < 1e-6) {
		return 1;
	}
	return dx * dy > 0 ? 2 : 3;
}

/** @returns {number|null} perpendicular distance when the segments are parallel, else null. */
function parallelDistance(a, b) {
	const oa = orientationOf(a);
	if (oa !== orientationOf(b)) {
		return null;
	}
	if (oa === 0) {
		return Math.abs(a.a.y - b.a.y);
	}
	if (oa === 1) {
		return Math.abs(a.a.x - b.a.x);
	}
	const sign = oa === 2 ? 1 : -1;
	return Math.abs((a.a.y - sign * a.a.x) - (b.a.y - sign * b.a.x)) / Math.SQRT2;
}

function parallelOverlap(a, b) {
	const orientation = orientationOf(a);
	if (orientation >= 2) {
		const a0 = Math.min(a.a.x, a.b.x);
		const a1 = Math.max(a.a.x, a.b.x);
		const b0 = Math.min(b.a.x, b.b.x);
		const b1 = Math.max(b.a.x, b.b.x);
		return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
	}
	const axis = orientation === 0 ? 'x' : 'y';
	const a0 = Math.min(a.a[axis], a.b[axis]);
	const a1 = Math.max(a.a[axis], a.b[axis]);
	const b0 = Math.min(b.a[axis], b.b[axis]);
	const b1 = Math.max(b.a[axis], b.b[axis]);
	return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
}

function conflictsWithPlaced(candidate, placed) {
	for (const other of placed) {
		const distance = parallelDistance(candidate, other);
		if (distance !== null && distance < MIN_PARALLEL_GAP && parallelOverlap(candidate, other) > MIN_PARALLEL_OVERLAP) {
			return true;
		}
	}
	return false;
}

function shuffle(items, random) {
	for (let index = items.length - 1; index > 0; index--) {
		const swap = Math.floor(random() * (index + 1));
		[items[index], items[swap]] = [items[swap], items[index]];
	}
	return items;
}

function edgeKey(a, b) {
	const first = a.row * HUB_COLUMNS + a.column;
	const second = b.row * HUB_COLUMNS + b.column;
	return first < second ? first + ':' + second : second + ':' + first;
}

/** Octant index of a concrete segment, used to keep every bend at 45° or 0°. */
function directionIndex(a, b) {
	const dx = Math.sign(b.x - a.x);
	const dy = Math.sign(b.y - a.y);
	return DIRECTIONS.findIndex(([x, y]) => x === dx && y === dy);
}

/**
 * Connects two hubs with metro geometry: a single straight run for a row/column neighbour, or a
 * 45° leg plus a straight remainder. Deliberately no L-shaped fallback: a 90° bend is below the
 * 100° minimum the welcome canvas allows, so an unsuitable offset is rejected instead.
 *
 * @returns {{segments: Array, points: Array}|null}
 */
function makeConnection(a, b) {
	const dx = b.x - a.x;
	const dy = b.y - a.y;
	const adx = Math.abs(dx);
	const ady = Math.abs(dy);

	if (adx < 1 || ady < 1) {
		return { segments: [{ a, b }], points: [b] };
	}

	const diagonal = Math.min(adx, ady);
	const remainder = Math.max(adx, ady) - diagonal;
	if (diagonal < MIN_DIAGONAL || remainder < MIN_SEGMENT) {
		return null;
	}

	const sx = Math.sign(dx);
	const sy = Math.sign(dy);
	const corner = { x: a.x + sx * diagonal, y: a.y + sy * diagonal };
	return {
		segments: [{ a, b: corner }, { a: corner, b }],
		points: [corner, b]
	};
}

/**
 * Builds a connected metro map on a jittered hub lattice. Routes share hubs (interchanges) but
 * never reuse the same hub-to-hub edge, so the network reads as a system rather than a pile of
 * unrelated strokes. A spacing pass keeps parallel runs away from each other.
 *
 * @param {number} seed
 * @param {number} width viewport width in CSS pixels
 * @param {number} height viewport height in CSS pixels
 * @returns {{paths: Array, flows: Array}}
 */
export function buildNetwork(seed, width, height) {
	const random = mulberry32(seed);
	const fit = fitToViewport(Math.max(1, width), Math.max(1, height));
	const step = Math.min(
		(ART_WIDTH - HUB_MARGIN * 2) / (HUB_COLUMNS - 0.5),
		(ART_HEIGHT - HUB_MARGIN * 2) / (HUB_ROWS - 1)
	);
	const hubs = [];

	for (let row = 0; row < HUB_ROWS; row++) {
		hubs[row] = [];
		for (let column = 0; column < HUB_COLUMNS; column++) {
			hubs[row][column] = {
				x: HUB_MARGIN + column * step + (row % 2) * step * 0.5,
				y: HUB_MARGIN + row * step
			};
		}
	}

	const placed = [];
	const usedEdges = new Set();
	const routes = [];

	for (let routeIndex = 0; routeIndex < ROUTE_COUNT; routeIndex++) {
		const startRow = Math.floor(random() * HUB_ROWS);
		const startColumn = Math.floor(random() * HUB_COLUMNS);
		const hubPath = [{ row: startRow, column: startColumn }];
		const visited = new Set([startRow * HUB_COLUMNS + startColumn]);
		const artPoints = [hubs[startRow][startColumn]];
		const targetHubs = ROUTE_MIN_HUBS + Math.floor(random() * (ROUTE_MAX_HUBS - ROUTE_MIN_HUBS + 1));
		let incoming = null;

		while (hubPath.length < targetHubs) {
			const current = hubPath[hubPath.length - 1];
			const straight = [];
			const turning = [];

			for (const [dr, dc] of DIRECTIONS) {
				const row = current.row + dr;
				const column = current.column + dc;
				if (row < 0 || row >= HUB_ROWS || column < 0 || column >= HUB_COLUMNS) {
					continue;
				}
				const neighbour = { row, column };
				if (visited.has(row * HUB_COLUMNS + column) || usedEdges.has(edgeKey(current, neighbour))) {
					continue;
				}
				const a = hubs[current.row][current.column];
				const b = hubs[neighbour.row][neighbour.column];
				const connection = makeConnection(a, b);
				if (!connection) {
					continue;
				}
				if (connection.segments.some(segment => conflictsWithPlaced(segment, placed))) {
					continue;
				}

				let turn = 0;
				if (incoming !== null) {
					const first = directionIndex(connection.segments[0].a, connection.segments[0].b);
					const delta = Math.abs(first - incoming);
					turn = Math.min(delta, DIRECTIONS.length - delta);
					if (turn > 1) {
						continue;
					}
				}

				(turn === 0 ? straight : turning).push({ neighbour, connection });
			}

			// Prefer a straight continuation, but do take the gentle 45° bend when one is
			// available. That avoids both the busy CPU-board zigzag and a dead-straight grid.
			let pool;
			if (straight.length > 0 && turning.length > 0) {
				pool = random() < 0.55 ? straight : turning;
			} else {
				pool = straight.length > 0 ? straight : turning;
			}
			if (pool.length === 0) {
				break;
			}

			const accepted = pool[Math.floor(random() * pool.length)];
			for (const segment of accepted.connection.segments) {
				placed.push(segment);
			}
			usedEdges.add(edgeKey(current, accepted.neighbour));
			visited.add(accepted.neighbour.row * HUB_COLUMNS + accepted.neighbour.column);
			hubPath.push(accepted.neighbour);
			artPoints.push(...accepted.connection.points);

			const last = artPoints[artPoints.length - 1];
			const previous = artPoints[artPoints.length - 2];
			incoming = directionIndex(previous, last);
		}

		if (hubPath.length >= 4) {
			routes.push({ hubPath, artPoints });
		}
	}

	const paths = [];
	for (const route of routes) {
		const points = route.artPoints.map(point => toScreen(point, fit));
		paths.push(addSegments({
			points,
			stations: [],
			nodes: [],
			alpha: 0.16 + random() * 0.08,
			width: 0.9 + random() * 0.8
		}));
	}

	const flowPaths = paths.filter(path => path.total >= 100);
	const source = shuffle(flowPaths.length > 0 ? flowPaths : paths.slice(), random);
	const flows = [];
	for (let index = 0; index < FLOW_COUNT && source.length > 0; index++) {
		const path = source[index % source.length];
		const span = Math.min(path.total * 0.7, 90 + random() * 120);
		flows.push({
			path,
			offset: random() * path.total,
			span,
			speed: 14 + random() * 14,
			alpha: 0.32 + random() * 0.2,
			cycle: path.total + span + 140
		});
	}

	return { paths, flows };
}

function pointAt(segment, distance) {
	const length = segment.end - segment.start || 1;
	const ratio = Math.min(1, Math.max(0, (distance - segment.start) / length));
	return {
		x: segment.a.x + (segment.b.x - segment.a.x) * ratio,
		y: segment.a.y + (segment.b.y - segment.a.y) * ratio
	};
}

function pointAtDistance(path, distance) {
	for (const segment of path.segments) {
		if (distance >= segment.start && distance <= segment.end) {
			return pointAt(segment, distance);
		}
	}
	return null;
}

/**
 * @param {HTMLCanvasElement} canvas
 * @returns {{setActive: (active: boolean) => void, resize: () => void, destroy: () => void}}
 */
export function mount(canvas) {
	const noop = { setActive() {}, resize() {}, destroy() {} };
	if (!canvas || typeof canvas.getContext !== 'function') {
		return noop;
	}

	const ctx = canvas.getContext('2d');
	if (!ctx) {
		return noop;
	}

	const seed = (Math.random() * 0xFFFFFFFF) >>> 0;
	const motionQuery = typeof window !== 'undefined' && window.matchMedia
		? window.matchMedia('(prefers-reduced-motion: reduce)')
		: null;

	let reduceMotion = Boolean(motionQuery && motionQuery.matches);
	let width = 0;
	let height = 0;
	let dpr = 1;
	let network = null;
	let staticCanvas = null;
	let enabled = false;
	let running = false;
	let destroyed = false;
	let rafId = 0;
	let lastTime = 0;
	let resizeTimer = 0;
	let resizeObserver = null;
	let palette = readPalette();

	function resize() {
		if (destroyed) {
			return;
		}

		const rect = canvas.getBoundingClientRect();
		const nextWidth = Math.max(1, Math.round(rect.width));
		const nextHeight = Math.max(1, Math.round(rect.height));
		const nextDpr = Math.min(MAX_DPR, window.devicePixelRatio || 1);

		if (nextWidth === width && nextHeight === height && nextDpr === dpr && staticCanvas) {
			return;
		}

		width = nextWidth;
		height = nextHeight;
		dpr = nextDpr;
		canvas.width = Math.round(width * dpr);
		canvas.height = Math.round(height * dpr);
		ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

		network = buildNetwork(seed, width, height);
		renderStatic();

		if (reduceMotion && enabled) {
			renderFrame(0);
		}
	}

	function renderStatic() {
		const paintWidth = width + PADDING * 2;
		const paintHeight = height + PADDING * 2;

		staticCanvas = document.createElement('canvas');
		staticCanvas.width = Math.max(1, Math.round(paintWidth * dpr));
		staticCanvas.height = Math.max(1, Math.round(paintHeight * dpr));

		const surface = staticCanvas.getContext('2d');
		if (!surface) {
			staticCanvas = null;
			return;
		}

		surface.setTransform(dpr, 0, 0, dpr, 0, 0);
		surface.save();
		surface.translate(PADDING, PADDING);
		surface.lineJoin = 'round';
		surface.lineCap = 'round';

		// A very large, soft pool of light behind the bottom sheet. Thin lines disappear under a
		// 24px backdrop blur; this is wide enough that the glass visibly has something behind it.
		const glow = surface.createRadialGradient(
			width * 0.5, height * 0.92, 0,
			width * 0.5, height * 0.92, Math.max(width, height) * 0.62
		);
		glow.addColorStop(0, rgba(palette.glow, palette.glowAlpha));
		glow.addColorStop(0.45, rgba(palette.glow, palette.glowAlpha * 0.35));
		glow.addColorStop(1, rgba(palette.glow, 0));
		surface.fillStyle = glow;
		surface.fillRect(0, 0, width, height);

		for (const path of network.paths) {
			surface.beginPath();
			path.points.forEach((point, index) => {
				if (index === 0) {
					surface.moveTo(point.x, point.y);
				} else {
					surface.lineTo(point.x, point.y);
				}
			});
			surface.strokeStyle = 'rgba(' + palette.line + ', ' + Math.min(0.72, path.alpha * palette.lineScale).toFixed(3) + ')';
			surface.lineWidth = path.width;
			surface.stroke();

			surface.strokeStyle = 'rgba(' + palette.tickLine + ', ' + Math.min(0.72, path.alpha * palette.lineScale + 0.06).toFixed(3) + ')';
			surface.lineWidth = 1.2;
			for (const station of path.stations) {
				surface.beginPath();
				surface.moveTo(station.x, station.y);
				surface.lineTo(station.x + station.px * station.size, station.y + station.py * station.size);
				surface.stroke();
			}

			surface.fillStyle = palette.node;
			for (const node of path.nodes) {
				surface.beginPath();
				surface.arc(node.x, node.y, 2.4, 0, Math.PI * 2);
				surface.fill();
			}
		}

		surface.restore();
	}

	function drawFlowSpan(flow, tail, from, to, offsetX, offsetY) {
		for (const segment of flow.path.segments) {
			const start = Math.max(from, segment.start);
			const end = Math.min(to, segment.end);
			if (end <= start) {
				continue;
			}

			const a = pointAt(segment, start);
			const b = pointAt(segment, end);
			const u0 = Math.min(1, Math.max(0, (start - tail) / flow.span));
			const u1 = Math.min(1, Math.max(0, (end - tail) / flow.span));
			const a0 = flow.alpha * palette.flowAlphaScale * (0.12 + 0.88 * Math.pow(u0, 1.4));
			const a1 = flow.alpha * palette.flowAlphaScale * (0.12 + 0.88 * Math.pow(u1, 1.4));

			const accent = parseColor(palette.flow);
			const flowRgba = accent
				? (alpha) => `rgba(${Math.round(accent.r)}, ${Math.round(accent.g)}, ${Math.round(accent.b)}, ${alpha})`
				: (alpha) => `rgba(158, 203, 255, ${alpha})`;
			const gradient = ctx.createLinearGradient(a.x + offsetX, a.y + offsetY, b.x + offsetX, b.y + offsetY);
			gradient.addColorStop(0, flowRgba(a0.toFixed(3)));
			gradient.addColorStop(1, flowRgba(a1.toFixed(3)));

			// A wide, faint halo first: it is large enough to survive the 24px panel blur, so the
			// moving light still glows through the glass.
			ctx.strokeStyle = flowRgba((a0 * 0.16).toFixed(3));
			ctx.lineWidth = 8;
			ctx.beginPath();
			ctx.moveTo(a.x + offsetX, a.y + offsetY);
			ctx.lineTo(b.x + offsetX, b.y + offsetY);
			ctx.stroke();

			ctx.strokeStyle = gradient;
			ctx.lineWidth = 1.8;
			ctx.beginPath();
			ctx.moveTo(a.x + offsetX, a.y + offsetY);
			ctx.lineTo(b.x + offsetX, b.y + offsetY);
			ctx.stroke();
		}
	}

	function drawFlows(time, offsetX, offsetY) {
		if (!network) {
			return;
		}

		ctx.save();
		ctx.globalCompositeOperation = palette.blend;
		ctx.lineCap = 'round';
		ctx.lineJoin = 'round';
		ctx.shadowBlur = 18;
		ctx.shadowColor = palette.shadow;

		for (const flow of network.flows) {
			const head = (time * flow.speed + flow.offset) % flow.cycle;
			const tail = head - flow.span;
			if (head <= 0 || tail >= flow.path.total) {
				continue;
			}

			drawFlowSpan(flow, tail, Math.max(0, tail), Math.min(flow.path.total, head), offsetX, offsetY);

			if (head > 0 && head < flow.path.total) {
				const point = pointAtDistance(flow.path, head);
				if (point) {
					ctx.shadowBlur = 20;
					ctx.fillStyle = palette.train;
					ctx.beginPath();
					ctx.arc(point.x + offsetX, point.y + offsetY, 2.4, 0, Math.PI * 2);
					ctx.fill();
					ctx.shadowBlur = 18;
				}
			}
		}

		ctx.restore();
	}

	function renderFrame(now) {
		const time = now / 1000;
		const offsetX = Math.sin(time * 0.062) * 18;
		const offsetY = Math.cos(time * 0.049) * 14;

		ctx.clearRect(0, 0, width, height);

		if (staticCanvas) {
			ctx.drawImage(
				staticCanvas,
				offsetX - PADDING,
				offsetY - PADDING,
				width + PADDING * 2,
				height + PADDING * 2
			);
		}

		drawFlows(time, offsetX, offsetY);
	}

	function loop(now) {
		if (!running) {
			return;
		}

		rafId = requestAnimationFrame(loop);

		const nextDpr = Math.min(MAX_DPR, window.devicePixelRatio || 1);
		if (nextDpr !== dpr) {
			resize();
		}

		if (now - lastTime < FRAME_INTERVAL) {
			return;
		}
		lastTime = now;
		renderFrame(now);
	}

	function start() {
		if (running || destroyed) {
			return;
		}
		if (reduceMotion) {
			renderFrame(performance.now());
			return;
		}
		if (document.hidden) {
			return;
		}
		running = true;
		lastTime = performance.now();
		rafId = requestAnimationFrame(loop);
	}

	function stop() {
		if (!running) {
			return;
		}
		running = false;
		if (rafId) {
			cancelAnimationFrame(rafId);
			rafId = 0;
		}
	}

	function setActive(active) {
		if (destroyed) {
			return;
		}
		const next = Boolean(active);
		if (enabled === next) {
			if (next && !running) {
				start();
			}
			return;
		}
		enabled = next;
		if (next) {
			resize();
			start();
		} else {
			stop();
		}
	}

	function scheduleResize() {
		clearTimeout(resizeTimer);
		resizeTimer = setTimeout(() => {
			resize();
			if (reduceMotion && enabled) {
				renderFrame(0);
			}
		}, 180);
	}

	function onVisibilityChange() {
		if (document.hidden) {
			stop();
		} else if (enabled) {
			start();
		}
	}

	function onMotionPreferenceChange(event) {
		reduceMotion = event.matches;
		if (reduceMotion) {
			stop();
			renderFrame(performance.now());
		} else if (enabled) {
			start();
		}
	}

	function onThemeChange() {
		palette = readPalette();
		if (destroyed || !network) {
			return;
		}
		renderStatic();
		if (enabled || reduceMotion) {
			renderFrame(performance.now());
		}
	}

	function destroy() {
		destroyed = true;
		enabled = false;
		stop();
		clearTimeout(resizeTimer);
		if (resizeObserver) {
			resizeObserver.disconnect();
			resizeObserver = null;
		}
		window.removeEventListener('resize', scheduleResize);
		window.removeEventListener(THEME_EVENT, onThemeChange);
		document.removeEventListener('visibilitychange', onVisibilityChange);
		if (motionQuery && typeof motionQuery.removeEventListener === 'function') {
			motionQuery.removeEventListener('change', onMotionPreferenceChange);
		}
		network = null;
		staticCanvas = null;
	}

	window.addEventListener('resize', scheduleResize);
	window.addEventListener(THEME_EVENT, onThemeChange);
	document.addEventListener('visibilitychange', onVisibilityChange);
	if (motionQuery && typeof motionQuery.addEventListener === 'function') {
		motionQuery.addEventListener('change', onMotionPreferenceChange);
	}
	if (typeof ResizeObserver === 'function') {
		resizeObserver = new ResizeObserver(scheduleResize);
		resizeObserver.observe(canvas);
	}

	resize();

	return { setActive, resize, destroy };
}
