import { f, t } from './i18n.js?v=65';

let elements = null;
let handlers = {};
let index = { routeById: new Map(), depotById: new Map(), sidingById: new Map(), platformById: new Map(), stationById: new Map() };
let currentTrain = null;
let tracked = false;
let lastStatusKey = null;
let lastNextStopId = null;
let bannerTimer = null;
let toastSize = null;
let lastLeft = null;
let lastTop = null;

function el() {
    if (elements) {
        return elements;
    }
    const toast = document.getElementById('train-toast');
    if (!toast) {
        return null;
    }
    elements = {
        toast,
        title: document.getElementById('train-toast-title'),
        close: document.getElementById('train-toast-close'),
        status: document.getElementById('train-toast-status'),
        speed: document.getElementById('train-toast-speed'),
        route: document.getElementById('train-toast-route'),
        origin: document.getElementById('train-toast-origin'),
        destination: document.getElementById('train-toast-destination'),
        next: document.getElementById('train-toast-next'),
        depot: document.getElementById('train-toast-depot'),
        formation: document.getElementById('train-toast-formation'),
        id: document.getElementById('train-toast-id'),
        action: document.getElementById('train-toast-action'),
        banner: document.getElementById('train-banner')
    };
    return elements;
}

export function init(options) {
    handlers = options || {};
    const nodes = el();
    if (!nodes) {
        return;
    }
    nodes.close.addEventListener('click', () => {
        hide();
        if (handlers.onClose) {
            handlers.onClose();
        }
    });
    nodes.action.addEventListener('click', () => {
        if (!currentTrain) {
            return;
        }
        if (tracked) {
            if (handlers.onStop) {
                handlers.onStop();
            }
            setTracked(false);
        } else if (handlers.onTrack) {
            handlers.onTrack(currentTrain.id);
        }
    });
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && isOpen()) {
            hide();
            if (handlers.onClose) {
                handlers.onClose();
            }
        }
    });
}

export function setIndex(nextIndex) {
    index = nextIndex || index;
}

function cleanName(value) {
    return typeof value === 'string' ? value.replace(/\|/g, ' ').trim() : '';
}

function routeName(train) {
    const route = train.routeId ? index.routeById.get(train.routeId) : null;
    return route && cleanName(route.name) ? cleanName(route.name) : t('trainNoRoute');
}

function depotName(train) {
    const depot = train.depotId ? index.depotById.get(train.depotId) : null;
    return depot && cleanName(depot.name) ? cleanName(depot.name) : t('trainNoDepot');
}

function sidingName(train) {
    const siding = train.sidingId ? index.sidingById.get(train.sidingId) : null;
    const name = siding ? cleanName(siding.name) : '';
    return name || t('trainTitle');
}

function stopName(platformId) {
    const platform = platformId ? index.platformById.get(String(platformId)) : null;
    if (!platform) {
        return t('trainNoStop');
    }
    const station = platform.stationId ? index.stationById.get(platform.stationId) : null;
    return cleanName(station && station.name) || cleanName(platform.name) || t('trainNoStop');
}

function routeTermini(train) {
    const route = train.routeId ? index.routeById.get(train.routeId) : null;
    const platforms = route && Array.isArray(route.platformIds) ? route.platformIds : [];
    if (platforms.length === 0) {
        return { origin: t('trainNoStop'), destination: t('trainNoStop') };
    }
    const idOf = entry => entry && typeof entry === 'object' ? (entry.platformId ?? entry) : entry;
    const first = stopName(idOf(platforms[0]));
    const last = stopName(idOf(platforms[platforms.length - 1]));
    return train.reversed ? { origin: last, destination: first } : { origin: first, destination: last };
}

function nextStopId(train) {
	return train.nextStopId != null ? train.nextStopId : (train.nextPlatformId == null ? null : String(train.nextPlatformId));
}

function nextStopName(train) {
	const id = nextStopId(train);
	if (!id) {
		return t('trainNoStop');
	}
	if (train.nextStopKind === 'siding') {
		const siding = index.sidingById.get(String(id));
		const name = siding ? cleanName(siding.name) : '';
		return f('trainDepotStop', name || t('trainNoDepot'));
	}
	const platform = index.platformById.get(String(id));
	if (!platform) {
		return t('trainNoStop');
	}
	const station = platform.stationId ? index.stationById.get(platform.stationId) : null;
	return cleanName(station && station.name) || cleanName(platform.name) || t('trainNoStop');
}

function statusKey(train) {
    if (train.doors > 0.05) {
        return 'doors';
    }
    if (train.manual) {
        return 'manual';
    }
    if (train.speed > 0.001) {
        return 'running';
    }
    return train.onRoute ? 'stopped' : 'idle';
}

function statusText(train) {
    switch (statusKey(train)) {
        case 'doors':
            return t('trainStatusDoors');
        case 'manual':
            return t('trainStatusManual');
        case 'running':
            return t('trainStatusRunning');
        case 'idle':
            return t('trainStatusStopped');
        default:
            return t('trainStatusStopped');
    }
}

function speedText(train) {
    return Math.round(train.speed * 20 * 3.6) + ' km/h';
}

function formationText(train) {
    return f('trainCarsValue', train.cars);
}

function setText(node, value) {
    if (node && node.textContent !== value) {
        node.textContent = value;
    }
}

function updateContents(train) {
    const nodes = el();
    if (!nodes || !train) {
        return;
    }
    const termini = routeTermini(train);
    setText(nodes.title, sidingName(train));
    setText(nodes.status, statusText(train));
    setText(nodes.speed, speedText(train));
    setText(nodes.formation, formationText(train));
    setText(nodes.route, routeName(train));
    setText(nodes.depot, depotName(train));
    setText(nodes.origin, termini.origin);
    setText(nodes.destination, termini.destination);
    setText(nodes.next, nextStopName(train));
    setText(nodes.id, train.id);
    nodes.toast.dataset.status = statusKey(train);
    nodes.action.textContent = tracked ? t('trainStopTracking') : t('trainTrack');
}

export function show(train, anchor) {
    const nodes = el();
    if (!nodes || !train) {
        return;
    }
    currentTrain = train;
    tracked = false;
    lastStatusKey = statusKey(train);
    lastNextStopId = nextStopId(train);
    toastSize = null;
    lastLeft = null;
    lastTop = null;
    updateContents(train);
    nodes.toast.hidden = false;
    nodes.toast.classList.add('is-open');
    position(anchor);
}

export function update(train) {
    if (!currentTrain || !train || train.id !== currentTrain.id) {
        return;
    }
    currentTrain = train;
    updateContents(train);
    const next = statusKey(train);
    if (tracked && (next !== lastStatusKey || nextStopId(train) !== lastNextStopId)) {
        flash(train);
    }
    lastStatusKey = next;
    lastNextStopId = nextStopId(train);
}

export function setTracked(nextTracked) {
    tracked = Boolean(nextTracked);
    const nodes = el();
    if (nodes) {
        nodes.action.textContent = tracked ? t('trainStopTracking') : t('trainTrack');
        if (currentTrain) {
            nodes.toast.dataset.status = statusKey(currentTrain);
        }
    }
}

export function hide() {
    const nodes = el();
    currentTrain = null;
    tracked = false;
    if (!nodes) {
        return;
    }
    nodes.toast.classList.remove('is-open');
    nodes.toast.hidden = true;
    clearTimeout(bannerTimer);
    nodes.banner.hidden = true;
    nodes.banner.classList.remove('is-visible');
}

export function isOpen() {
    const nodes = el();
    return Boolean(nodes && !nodes.toast.hidden);
}

export function getTrainId() {
    return currentTrain ? currentTrain.id : null;
}

export function flash(train) {
    const nodes = el();
    if (!nodes || !train) {
        return;
    }
    const next = statusKey(train);
    const message = nextStopId(train) && nextStopId(train) !== lastNextStopId
        ? f('trainBannerNext', nextStopName(train))
        : f('trainBannerStatus', statusText(train), speedText(train));
    nodes.banner.textContent = message;
    nodes.banner.hidden = false;
    nodes.banner.classList.add('is-visible');
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => {
        nodes.banner.classList.remove('is-visible');
        setTimeout(() => {
            if (!nodes.banner.classList.contains('is-visible')) {
                nodes.banner.hidden = true;
            }
        }, 220);
    }, 2400);
    lastStatusKey = next;
    lastNextStopId = nextStopId(train);
}

export function moveTo(anchor) {
    if (currentTrain && isOpen()) {
        position(anchor);
    }
}

function position(anchor) {
    const nodes = el();
    if (!nodes) {
        return;
    }
    const toast = nodes.toast;
    if (!anchor) {
        toast.style.left = '';
        toast.style.top = '';
        lastLeft = null;
        lastTop = null;
        return;
    }
    const margin = 12;
    const minTop = 72;
    // Measure once per open: reading offsetWidth/offsetHeight on every tracked frame forces a layout.
    if (!toastSize) {
        toastSize = { width: toast.offsetWidth || 300, height: toast.offsetHeight || 220 };
    }
    const width = toastSize.width;
    const height = toastSize.height;
    let left = anchor.clientX + 18;
    let top = anchor.clientY + 16;
    if (left + width > window.innerWidth - margin) {
        left = anchor.clientX - width - 18;
    }
    if (top + height > window.innerHeight - margin) {
        top = anchor.clientY - height - 16;
    }
    const nextLeft = Math.max(margin, left);
    const nextTop = Math.max(minTop, top);
    if (nextLeft !== lastLeft) {
        toast.style.left = nextLeft + 'px';
        lastLeft = nextLeft;
    }
    if (nextTop !== lastTop) {
        toast.style.top = nextTop + 'px';
        lastTop = nextTop;
    }
}
