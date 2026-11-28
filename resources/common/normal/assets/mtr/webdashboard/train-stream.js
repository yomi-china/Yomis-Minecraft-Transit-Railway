/**
 * EventSource wrapper for the train stream.
 *
 * The tunnel is the reason this module is defensive: a long-lived response is the one shape the
 * in-game proxy was not originally exercised with. If the stream does not deliver a first `full`
 * within the probe window, or stalls after opening, the caller is told to fall back to polling.
 * Nothing here throws into the page.
 */

const DEFAULT_PROBE_MS = 8000;
const STALL_MS = 12000;
const ERROR_WINDOW_MS = 20000;
const MAX_ERRORS = 3;

function defaultFactory(url) {
	if (typeof EventSource === 'undefined') {
		throw new Error('EventSource is not available');
	}
	return new EventSource(url);
}

function parseEvent(event) {
	try {
		return JSON.parse(event.data);
	} catch (error) {
		return null;
	}
}

/**
 * @param {object} options
 * @param {string} options.url
 * @param {Function} [options.eventSourceFactory]
 * @param {Function} options.onOpen
 * @param {Function} options.onFull
 * @param {Function} options.onFrame
 * @param {Function} [options.onAuth]
 * @param {Function} [options.onError]
 * @param {number} [options.probeMs]
 */
export function openTrainStream(options) {
	const factory = options.eventSourceFactory || defaultFactory;
	const probeMs = options.probeMs == null ? DEFAULT_PROBE_MS : options.probeMs;
	let source = null;
	let closed = false;
	let opened = false;
	let frames = 0;
	let lastSeq = 0;
	let lastFrameAt = 0;
	let probeTimer = null;
	let stallTimer = null;
	const errorTimes = [];

	function clearTimers() {
		clearTimeout(probeTimer);
		probeTimer = null;
		clearTimeout(stallTimer);
		stallTimer = null;
	}

	function close() {
		closed = true;
		clearTimers();
		if (source) {
			try {
				source.close();
			} catch (error) {
				// already closed
			}
			source = null;
		}
	}

	function fail(reason) {
		if (closed) {
			return;
		}
		close();
		if (options.onError) {
			options.onError(reason);
		}
	}

	function armStallTimer() {
		clearTimeout(stallTimer);
		stallTimer = setTimeout(() => fail('stalled'), STALL_MS);
	}

	function accept(event) {
		const payload = parseEvent(event);
		if (!payload) {
			fail('malformed');
			return null;
		}
		if (payload.v != null && payload.v !== 1) {
			fail('protocol');
			return null;
		}
		frames++;
		lastSeq = payload.seq || lastSeq;
		lastFrameAt = Date.now();
		armStallTimer();
		return payload;
	}

	function onMessage(event) {
		if (closed) {
			return;
		}
		opened = true;
		clearTimeout(probeTimer);
		probeTimer = null;
		const payload = accept(event);
		if (payload && options.onFull) {
			options.onFull(payload);
		}
	}

	function onDelta(event) {
		if (closed) {
			return;
		}
		opened = true;
		const payload = accept(event);
		if (payload && options.onFrame) {
			options.onFrame(payload);
		}
	}

	try {
		source = factory(options.url);
	} catch (error) {
		close();
		if (options.onError) {
			options.onError('unsupported');
		}
		return { close, getState };
	}

	source.addEventListener('open', () => {
		if (closed) {
			return;
		}
		opened = true;
		clearTimeout(probeTimer);
		probeTimer = null;
		armStallTimer();
		if (options.onOpen) {
			options.onOpen();
		}
	});
	source.addEventListener('full', onMessage);
	source.addEventListener('frame', onDelta);
	source.addEventListener('ping', () => {
		if (closed) {
			return;
		}
		opened = true;
		lastFrameAt = Date.now();
		armStallTimer();
	});
	source.addEventListener('auth', () => {
		if (closed) {
			return;
		}
		close();
		if (options.onAuth) {
			options.onAuth();
		}
	});
	source.addEventListener('error', () => {
		if (closed) {
			return;
		}
		const now = Date.now();
		errorTimes.push(now);
		while (errorTimes.length > 0 && now - errorTimes[0] > ERROR_WINDOW_MS) {
			errorTimes.shift();
		}
		if (!opened && errorTimes.length >= MAX_ERRORS) {
			fail('connect_failed');
		}
	});

	if (probeMs > 0) {
		probeTimer = setTimeout(() => {
			if (!opened) {
				fail('probe_timeout');
			}
		}, probeMs);
	}

	function getState() {
		return {
			closed,
			opened,
			frames,
			lastSeq,
			lastFrameAt,
			ageMs: lastFrameAt > 0 ? Date.now() - lastFrameAt : null
		};
	}

	return { close, getState };
}
