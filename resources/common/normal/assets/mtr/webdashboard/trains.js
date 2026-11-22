import { fetchTrains, DataErrorKind } from './api.js?v=64';
import { getTrainSettings, setTrainSetting, TRAIN_EVENT } from './train-settings.js?v=64';
import { argbToRgba, worldToScreen } from './mapview.js?v=64';
import { isShowingTracks } from './tracks.js?v=64';

const RETRY_BUSY_MS = 1200;
const RETRY_OFFLINE_MS = 5000;
const MIN_POLL_MS = 400;
const NORMAL_FRAME_MS = 16;
const TRACKING_FRAME_MS = 16;
const PREDICT_LIMIT_MS = 900;
const CAR_GAP_RATIO = 0.08;
const CAR_WIDTH_RATIO = 0.22;
const TRACKING_SCALE = 4;
const FLY_MS = 520;

let initialized = false;
let available = false;
let world = null;
let mapView = null;
let settings = getTrainSettings();
let records = new Map();
let selectedId = null;
let trackingId = null;
let total = 0;
let truncated = false;
let abortController = null;
let pollTimer = null;
let pollGeneration = 0;
let inFlight = false;
let frame = null;
let lastFrameAt = 0;
let trackingMissing = 0;
let viewActive = false;

const handlers = {
    redraw: () => {},
    onState: () => {},
    onSelect: () => {},
    onTracking: () => {},
    onTrackingLost: () => {},
    onTrackingMove: null,
    focus: null,
    follow: null,
    onAuthFailure: null
};

function canRun() {
    return initialized
        && settings.enabled
        && available
        && world != null
        && mapView != null
        && mapView.scale >= settings.minScale
        && (typeof document === 'undefined' || document.visibilityState !== 'hidden')
        && isShowingTracks();
}

function markState() {
    handlers.onState({
        enabled: settings.enabled,
        available,
        tracking: trackingId != null,
        selected: selectedId,
        total,
        truncated
    });
}

export function init(options) {
    Object.assign(handlers, options || {});
    initialized = true;
    settings = getTrainSettings();
    if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
        window.addEventListener(TRAIN_EVENT, onSettingsChange);
        document.addEventListener('visibilitychange', onVisibilityChange);
    }
    markState();
}

export function dispose() {
    initialized = false;
    stopPolling();
    stopAnimation();
    if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
        window.removeEventListener(TRAIN_EVENT, onSettingsChange);
        document.removeEventListener('visibilitychange', onVisibilityChange);
    }
}

export function setWorld(nextWorld) {
    const nextDimension = nextWorld && nextWorld.dimension ? nextWorld.dimension : null;
    const currentDimension = world && world.dimension ? world.dimension : null;
    if (nextDimension === currentDimension) {
        world = nextWorld || null;
        return;
    }
    world = nextWorld || null;
    stopPolling();
    records = new Map();
    total = 0;
    truncated = false;
    selectedId = null;
    stopTracking(false);
    handlers.onSelect(null);
    markState();
    schedulePoll(0);
    ensureAnimation();
}

export function setAvailable(nextAvailable) {
    available = Boolean(nextAvailable);
    if (!available) {
        selectedId = null;
        stopTracking(false);
        if (handlers.onSelect) {
            handlers.onSelect(null);
        }
        stopPolling();
        stopAnimation();
    } else {
        schedulePoll(0);
        ensureAnimation();
    }
    markState();
}

export function setEnabled(nextEnabled) {
    setTrainSetting('enabled', Boolean(nextEnabled));
}

export function toggleEnabled() {
    setTrainSetting('enabled', !settings.enabled);
    return settings.enabled;
}

export function isEnabled() {
    return settings.enabled;
}

export function isAvailable() {
    return available;
}

export function hasData() {
    return records.size > 0;
}

export function onViewChange(nextView) {
    mapView = nextView ? {
        centerX: nextView.centerX,
        centerZ: nextView.centerZ,
        scale: nextView.scale,
        width: nextView.width,
        height: nextView.height
    } : null;
    const active = mapView != null
        && mapView.scale >= settings.minScale
        && settings.enabled
        && available
        && world != null
        && isShowingTracks();
    if (active !== viewActive) {
        viewActive = active;
        if (active) {
            schedulePoll(0);
            ensureAnimation();
        } else {
            stopPolling();
            stopAnimation();
        }
    }
}

function onSettingsChange(event) {
    const previous = settings;
    settings = getTrainSettings();
    const detail = event && event.detail ? event.detail.key : null;

    if (!settings.enabled) {
        stopPolling();
        stopAnimation();
        selectedId = null;
        stopTracking(false);
        handlers.onSelect(null);
    } else if (detail === 'route' || detail === 'depot' || detail === 'max' || previous.interval !== settings.interval) {
        stopPolling();
        schedulePoll(0);
    } else if (previous.enabled !== settings.enabled) {
        schedulePoll(0);
    }
    markState();
    handlers.redraw();
    if (settings.enabled && available && mapView != null && mapView.scale >= settings.minScale && isShowingTracks()) {
        ensureAnimation();
    }
}

function onVisibilityChange() {
    if (document.visibilityState === 'hidden') {
        stopPolling();
        stopAnimation();
    } else {
        schedulePoll(0);
        ensureAnimation();
    }
}

function schedulePoll(delay) {
    if (!initialized || !settings.enabled) {
        return;
    }
    clearTimeout(pollTimer);
    pollTimer = setTimeout(poll, Math.max(0, delay));
}

function stopPolling() {
    clearTimeout(pollTimer);
    pollTimer = null;
    pollGeneration++;
    if (abortController) {
        abortController.abort();
        abortController = null;
    }
    inFlight = false;
}

async function poll() {
    pollTimer = null;
    if (inFlight || !canRun()) {
        schedulePoll(canRun() ? MIN_POLL_MS : 1000);
        return;
    }
    inFlight = true;
    const generation = ++pollGeneration;
    const controller = new AbortController();
    abortController = controller;
    let retryDelay = settings.interval * 1000;

    try {
        const payload = await fetchTrains({
            world: world.dimension,
            limit: settings.max,
            route: settings.route,
            depot: settings.depot,
            signal: controller.signal
        });
        if (generation !== pollGeneration) {
            return;
        }
        applySnapshot(payload);
    } catch (error) {
        if (generation !== pollGeneration || (error && error.kind === DataErrorKind.ABORTED)) {
            return;
        }
        if (error && (error.kind === DataErrorKind.BUSY || error.kind === DataErrorKind.TIMEOUT)) {
            retryDelay = RETRY_BUSY_MS;
        } else if (error && (error.kind === DataErrorKind.UNREACHABLE || error.kind === DataErrorKind.NO_SERVER)) {
            retryDelay = RETRY_OFFLINE_MS;
        } else if (error && error.kind === DataErrorKind.AUTH && handlers.onAuthFailure) {
            handlers.onAuthFailure();
        } else if (error && error.kind === DataErrorKind.NOT_FOUND) {
            retryDelay = RETRY_OFFLINE_MS;
        }
    } finally {
        if (generation === pollGeneration) {
            inFlight = false;
            abortController = null;
            if (canRun()) {
                schedulePoll(retryDelay);
            } else {
                schedulePoll(1000);
            }
        }
    }
}

function sanitizeTrain(raw) {
    if (!raw || raw.id == null || !Array.isArray(raw.points) || raw.points.length < 2) {
        return null;
    }
    const points = [];
    // The server packs coordinates as [x, z, x, z, ...] to keep the poll payload small; accept the
    // older nested [[x, z], ...] shape too so cached/alternate servers cannot silently drop trains.
    if (!Array.isArray(raw.points[0])) {
        if (raw.points.length < 4 || raw.points.length % 2 !== 0) {
            return null;
        }
        for (let i = 0; i < raw.points.length; i += 2) {
            const x = Number(raw.points[i]);
            const z = Number(raw.points[i + 1]);
            if (!Number.isFinite(x) || !Number.isFinite(z)) {
                return null;
            }
            points.push({ x, z });
        }
    } else {
        for (const point of raw.points) {
            if (!Array.isArray(point) || point.length < 2) {
                return null;
            }
            const x = Number(point[0]);
            const z = Number(point[1]);
            if (!Number.isFinite(x) || !Number.isFinite(z)) {
                return null;
            }
            points.push({ x, z });
        }
    }
    const cars = Math.max(1, Math.min(Number(raw.cars) || 1, points.length - 1));
    return {
        id: String(raw.id),
        sidingId: raw.sidingId == null ? null : String(raw.sidingId),
        model: typeof raw.model === 'string' ? raw.model : '',
        cars,
        spacing: Number(raw.spacing) || 1,
        width: Number(raw.width) || 2,
        color: Number(raw.color) || 0,
        routeId: raw.routeId == null ? null : String(raw.routeId),
        depotId: raw.depotId == null ? null : String(raw.depotId),
        nextPlatformId: raw.nextPlatformId == null ? null : String(raw.nextPlatformId),
        speed: Number(raw.speed) || 0,
        doors: Number(raw.doors) || 0,
        onRoute: Boolean(raw.onRoute),
        manual: Boolean(raw.manual),
        reversed: Boolean(raw.reversed),
        points,
        centers: parseCenters(raw.centers, points)
    };
}

function parseCenters(raw, points) {
    const centers = [];
    if (Array.isArray(raw)) {
        if (!Array.isArray(raw[0])) {
            if (raw.length % 2 === 0) {
                for (let i = 0; i < raw.length; i += 2) {
                    const x = Number(raw[i]);
                    const z = Number(raw[i + 1]);
                    if (Number.isFinite(x) && Number.isFinite(z)) {
                        centers.push({ x, z });
                    }
                }
            }
        } else {
            for (const point of raw) {
                if (Array.isArray(point) && point.length >= 2) {
                    const x = Number(point[0]);
                    const z = Number(point[1]);
                    if (Number.isFinite(x) && Number.isFinite(z)) {
                        centers.push({ x, z });
                    }
                }
            }
        }
    }
    if (centers.length === points.length - 1) {
        return centers;
    }
    const fallback = [];
    for (let i = 0; i < points.length - 1; i++) {
        fallback.push({
            x: (points[i].x + points[i + 1].x) / 2,
            z: (points[i].z + points[i + 1].z) / 2
        });
    }
    return fallback;
}

function applySnapshot(payload) {
    const now = performance.now();
    const nextRecords = new Map();
    const incoming = Array.isArray(payload.trains) ? payload.trains : [];

    for (const rawTrain of incoming) {
        const next = sanitizeTrain(rawTrain);
        if (!next) {
            continue;
        }
        const previous = records.get(next.id);
        // Keep moving for the whole configured interval instead of snapping at the next poll. The
        // measured server displacement gives a speed estimate used only when the next poll is late.
        const duration = clamp(settings.interval * 1000, 300, 15000);
        if (previous && previous.points.length === next.points.length && previous.centers.length === next.centers.length && previous.reversed === next.reversed) {
            const elapsed = Math.max(1, now - (previous.receivedAt || now - duration));
            next.fromCenters = currentCenters(previous, now);
            next.toCenters = next.centers;
            next.start = now;
            next.duration = duration;
            next.centerVelocity = estimateVelocity(previous.toCenters, next.centers, elapsed, next.speed);
        } else {
            next.fromCenters = next.centers;
            next.toCenters = next.centers;
            next.start = now - duration;
            next.duration = duration;
            next.centerVelocity = [];
        }
        next.receivedAt = now;
        nextRecords.set(next.id, next);
    }

    records = nextRecords;
    total = Number(payload.total) || incoming.length;
    truncated = Boolean(payload.truncated);

    if (selectedId && !records.has(selectedId)) {
        selectedId = null;
        handlers.onSelect(null);
    } else if (selectedId) {
        handlers.onSelect(records.get(selectedId));
    }

    if (trackingId && !records.has(trackingId)) {
        trackingMissing++;
        if (trackingMissing >= 2) {
            stopTracking(true);
        }
    } else if (trackingId) {
        trackingMissing = 0;
        const record = records.get(trackingId);
        handlers.onTracking(record);
        if (followRecord(record)) {
            ensureAnimation();
            markState();
            return;
        }
    }

    ensureAnimation();
    handlers.redraw();
    markState();
}

function estimateVelocity(previousPoints, nextPoints, elapsedMs, speed) {
    const limit = Math.max(0, speed) * 20 / 1000 * 1.25;
    if (!(limit > 0)) {
        return [];
    }
    return nextPoints.map((point, index) => {
        const before = previousPoints[index] || point;
        let x = (point.x - before.x) / elapsedMs;
        let z = (point.z - before.z) / elapsedMs;
        const length = Math.hypot(x, z);
        if (length > limit) {
            const scale = limit / length;
            x *= scale;
            z *= scale;
        }
        return Number.isFinite(x) && Number.isFinite(z) ? { x, z } : { x: 0, z: 0 };
    });
}

function currentCenters(record, now) {
    const elapsed = now - record.start;
    if (elapsed >= record.duration) {
        const extra = Math.min(elapsed - record.duration, PREDICT_LIMIT_MS);
        if (extra <= 0 || !record.centerVelocity || record.centerVelocity.length !== record.toCenters.length) {
            return record.toCenters;
        }
        return record.toCenters.map((point, index) => {
            const velocity = record.centerVelocity[index];
            return {
                x: point.x + velocity.x * extra,
                z: point.z + velocity.z * extra
            };
        });
    }
    const t = Math.max(0, elapsed / record.duration);
    const points = new Array(record.toCenters.length);
    for (let i = 0; i < record.toCenters.length; i++) {
        const from = record.fromCenters[i] || record.toCenters[i];
        points[i] = {
            x: from.x + (record.toCenters[i].x - from.x) * t,
            z: from.z + (record.toCenters[i].z - from.z) * t
        };
    }
    return points;
}

function centerOf(record, points) {
    if (settings.consist === 'block' && points.length > 0) {
        return points[Math.min(points.length - 1, Math.floor(points.length / 2))];
    }
    let x = 0;
    let z = 0;
    for (const point of points) {
        x += point.x;
        z += point.z;
    }
    return points.length ? { x: x / points.length, z: z / points.length } : { x: 0, z: 0 };
}

function followRecord(record) {
    if (!record || !handlers.follow || !mapView) {
        return false;
    }
    const center = centerOf(record, currentCenters(record, performance.now()));
    if (handlers.onTrackingMove) {
        const screen = worldToScreen(mapView, center.x, center.z);
        handlers.onTrackingMove(screen.x, screen.y);
    }
    return Boolean(handlers.follow(center.x, center.z));
}

function ensureAnimation() {
    if (frame != null || !initialized || !viewActive || !settings.enabled || records.size === 0) {
        return;
    }
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        return;
    }
    const step = now => {
        frame = null;
        const interval = trackingId != null
            ? TRACKING_FRAME_MS
            : (records.size > 64 ? 33 : NORMAL_FRAME_MS);
        if (now - lastFrameAt >= interval) {
            lastFrameAt = now;
            let followed = false;
            if (trackingId) {
                const record = records.get(trackingId);
                if (record) {
                    followed = followRecord(record);
                }
            }
            if (!followed) {
                handlers.redraw();
            }
        }
        if (initialized && settings.enabled && records.size > 0
            && (typeof document === 'undefined' || document.visibilityState !== 'hidden')) {
            frame = requestAnimationFrame(step);
        }
    };
    frame = requestAnimationFrame(step);
}

function stopAnimation() {
    if (frame != null) {
        cancelAnimationFrame(frame);
        frame = null;
    }
}

export function draw(context, view, mapTheme) {
    if (!context || !view || !records.size || !settings.enabled || !available || view.scale < settings.minScale || !isShowingTracks()) {
        return;
    }
    const now = performance.now();
    const selected = selectedId;
    context.save();
    context.globalAlpha = 1;
    context.lineCap = 'round';
    context.lineJoin = 'round';

    for (const record of records.values()) {
        const centers = currentCenters(record, now);
        if (isVisible(centers, view)) {
            drawRecord(context, view, mapTheme, record, centers, record.id === selected || record.id === trackingId);
        }
    }
    context.restore();
}

function isVisible(points, view) {
    const margin = 120;
    for (const point of points) {
        const screen = worldToScreen(view, point.x, point.z);
        if (screen.x >= -margin && screen.x <= view.width + margin && screen.y >= -margin && screen.y <= view.height + margin) {
            return true;
        }
    }
    return false;
}

function drawRecord(context, view, theme, record, points, highlighted) {
    // Route colours are stored both as RGB and ARGB by different MTR versions; an RGB value has a
    // zero alpha byte and would otherwise draw a fully transparent train.
    const argb = record.color >>> 0;
    const opaque = (argb >>> 24) === 0 ? (argb | 0xFF000000) : argb;
    const color = record.color !== 0 ? argbToRgba(opaque) : (theme.accent || 'rgba(255, 255, 255, 0.9)');
    if (settings.consist === 'block') {
        drawBlock(context, view, record, points, color, highlighted, theme);
        return;
    }
    const outline = theme.light ? 'rgba(24, 27, 31, 0.9)' : 'rgba(255, 255, 255, 0.55)';
    const spacingPx = Math.max(1, record.spacing * view.scale);
    if (record.cars > 1 && spacingPx < 6) {
        const center = centerOf(record, points);
        const screen = worldToScreen(view, center.x, center.z);
        const angle = carAngle(record, points, 0, view);
        const length = Math.max(3, spacingPx * record.cars * 0.82);
        drawCar(context, screen.x, screen.y, angle, length, Math.max(2, length * CAR_WIDTH_RATIO), color, outline, highlighted);
        return;
    }
    const gap = Math.min(3, Math.max(1, spacingPx * CAR_GAP_RATIO));
    const screens = points.map(point => worldToScreen(view, point.x, point.z));
    let lastX = NaN;
    let lastY = NaN;
    for (let i = 0; i < points.length; i++) {
        if (Math.abs(screens[i].x - lastX) < 1 && Math.abs(screens[i].y - lastY) < 1) {
            continue;
        }
        lastX = screens[i].x;
        lastY = screens[i].y;
        const previous = screens[i - 1];
        const next = screens[i + 1];
        let neighbor = spacingPx;
        if (previous || next) {
            neighbor = Math.min(
                previous ? Math.hypot(screens[i].x - previous.x, screens[i].y - previous.y) : Infinity,
                next ? Math.hypot(next.x - screens[i].x, next.y - screens[i].y) : Infinity
            );
        }
        const carLength = Math.max(2.5, Math.min(spacingPx, neighbor) - gap);
        const carWidth = Math.max(2, carLength * CAR_WIDTH_RATIO);
        const angle = carAngle(record, points, i, view);
        drawCar(context, screens[i].x, screens[i].y, angle, carLength, carWidth, color, outline, highlighted);
    }
}

function carAngle(record, centers, index, view) {
    let first;
    let second;
    if (centers.length > 1) {
        first = centers[Math.max(0, index - 1)];
        second = centers[Math.min(centers.length - 1, index + 1)];
        if (index === 0) {
            first = centers[0];
            second = centers[1];
        } else if (index === centers.length - 1) {
            first = centers[centers.length - 2];
            second = centers[centers.length - 1];
        }
    } else {
        first = record.points[0] || centers[0];
        second = record.points[1] || centers[0];
    }
    const a = worldToScreen(view, first.x, first.z);
    const b = worldToScreen(view, second.x, second.z);
    return Math.atan2(b.y - a.y, b.x - a.x);
}

function drawCar(context, centerX, centerY, angle, length, width, color, outline, highlighted) {
    const radius = Math.max(1, width * 0.2);

    context.save();
    context.translate(centerX, centerY);
    context.rotate(angle);
    if (highlighted) {
        context.beginPath();
        context.roundRect(-length / 2 - 3, -width / 2 - 3, length + 6, width + 6, radius + 2);
        context.fillStyle = 'rgba(255, 255, 255, 0.12)';
        context.fill();
        context.lineWidth = 1;
        context.strokeStyle = 'rgba(255, 255, 255, 0.8)';
        context.stroke();
    }
    context.beginPath();
    context.roundRect(-length / 2, -width / 2, length, width, radius);
    context.fillStyle = color;
    context.fill();
    context.lineWidth = 1;
    context.strokeStyle = highlighted ? 'rgba(255, 255, 255, 0.95)' : outline;
    context.stroke();
    context.restore();
}

function drawBlock(context, view, record, points, color, highlighted, theme) {
    const center = centerOf(record, points);
    const screen = worldToScreen(view, center.x, center.z);
    const index = Math.min(record.cars - 1, Math.floor(record.cars / 2));
    const angle = carAngle(record, points, index, view);
    const length = Math.max(4, record.spacing * record.cars * view.scale * 0.82);
    const width = Math.max(2, length * CAR_WIDTH_RATIO);

    context.save();
    context.translate(screen.x, screen.y);
    context.rotate(angle);
    context.beginPath();
    context.roundRect(-length / 2, -width / 2, length, width, 2.5);
    context.fillStyle = color;
    context.fill();
    context.lineWidth = highlighted ? 2.5 : 1.2;
    context.strokeStyle = highlighted ? 'rgba(255, 255, 255, 0.95)' : (theme.light ? 'rgba(24, 27, 31, 0.9)' : 'rgba(255, 255, 255, 0.85)');
    context.stroke();
    context.restore();
}

export function trySelect(detail) {
    if (!detail || !mapView || !records.size || !canRun()) {
        return false;
    }
    const now = performance.now();
    let best = null;
    let bestDistance = Infinity;
    for (const record of records.values()) {
        const centers = currentCenters(record, now);
        const threshold = settings.consist === 'block'
            ? Math.max(10, record.spacing * record.cars * mapView.scale * 0.42)
            : Math.max(6, record.spacing * mapView.scale * 0.58);
        for (const center of centers) {
            const screen = worldToScreen(mapView, center.x, center.z);
            const distance = Math.hypot(detail.x - screen.x, detail.y - screen.y);
            if (distance <= threshold && distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
    }
    if (!best) {
        return false;
    }
    const switchTracking = trackingId != null && trackingId !== best.id;
    selectTrain(best.id);
    if (switchTracking) {
        track(best.id);
    }
    return true;
}

export function selectTrain(id) {
    const record = records.get(String(id));
    if (!record) {
        return null;
    }
    selectedId = record.id;
    handlers.onSelect(record);
    handlers.redraw();
    markState();
    return record;
}

export function getSelected() {
    return selectedId ? records.get(selectedId) || null : null;
}

export function clearSelection() {
    if (!selectedId) {
        return false;
    }
    selectedId = null;
    handlers.redraw();
    markState();
    return true;
}

export function track(id) {
    const record = records.get(String(id));
    if (!record) {
        return false;
    }
    trackingId = record.id;
    selectedId = record.id;
    trackingMissing = 0;
    handlers.onSelect(record);
    handlers.onTracking(record);
    if (handlers.focus) {
        const center = centerOf(record, currentCenters(record, performance.now()));
        handlers.focus(center.x, center.z, Math.max(TRACKING_SCALE, mapView ? mapView.scale : TRACKING_SCALE), FLY_MS);
    }
    ensureAnimation();
    markState();
    handlers.redraw();
    return true;
}

export function stopTracking(notify) {
    const wasTracking = trackingId != null;
    trackingId = null;
    trackingMissing = 0;
    if (wasTracking) {
        markState();
        handlers.redraw();
        if (notify) {
            selectedId = null;
            handlers.onSelect(null);
            handlers.onTrackingLost();
        }
    }
}

export function isTracking() {
    return trackingId != null;
}

export function isTrackingId(id) {
    return trackingId != null && trackingId === String(id);
}

export function invalidate() {
    stopPolling();
    schedulePoll(0);
}

export function getDebugState() {
    return {
        enabled: settings.enabled,
        available,
        world: world ? world.dimension : null,
        total,
        truncated,
        rendered: records.size,
        tracking: trackingId,
        selected: selectedId,
        inFlight,
        settings: { ...settings }
    };
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
}
