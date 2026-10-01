import { t, applyTranslations } from './i18n.js';

/*
 * Stage 1 front end: confirm the service is reachable and show what this visitor may do.
 *
 * There is no sign-in yet - stage 2 adds it. Until then every visitor is anonymous, which is exactly
 * the shipped rule: opening the URL by hand never grants editing, and the page says so instead of
 * pretending to be an editor.
 */

const STATUS_ENDPOINT = '/api/status';
const REQUEST_TIMEOUT_MS = 5000;

const elements = {
	badge: document.getElementById('status-badge'),
	badgeLabel: document.querySelector('#status-badge [data-i18n]'),
	message: document.getElementById('page-message'),
	meta: document.getElementById('meta'),
	version: document.getElementById('meta-version'),
	port: document.getElementById('meta-port')
};

function setStatus(state, labelKey) {
	if (elements.badge) {
		elements.badge.dataset.state = state;
	}
	if (elements.badgeLabel) {
		elements.badgeLabel.textContent = t(labelKey);
	}
}

function setMessage(key) {
	if (elements.message) {
		elements.message.textContent = t(key);
	}
}

/**
 * Wraps fetch with a timeout. Without one a browser will happily wait minutes on a half-open
 * connection, leaving the page stuck on "checking" with no way for the user to tell what happened.
 */
async function fetchStatus() {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

	try {
		const response = await fetch(STATUS_ENDPOINT, { cache: 'no-store', signal: controller.signal });
		if (!response.ok) {
			throw new Error(`HTTP ${response.status}`);
		}
		return await response.json();
	} finally {
		clearTimeout(timer);
	}
}

function showReady(status) {
	setStatus('ready', 'statusReady');
	// status.canEdit is always false in stage 1, so there is nothing to branch on yet; stage 2 fills
	// it in and this becomes a real choice between the read-only message and an editing view.
	setMessage('messageNoEdit');

	if (elements.meta) {
		elements.meta.hidden = false;
	}
	if (elements.version) {
		elements.version.textContent = status.version || '—';
	}
	if (elements.port) {
		elements.port.textContent = status.port != null ? String(status.port) : '—';
	}
}

function showOffline(reason) {
	setStatus('offline', 'statusOffline');
	setMessage('messageOffline');
	// Logged rather than shown: the visitor cannot act on an HTTP status, but it is the first thing
	// worth knowing when the page will not load.
	console.warn('[MTR-WebDashboard] /api/status failed:', reason);
}

async function main() {
	applyTranslations(document);

	try {
		showReady(await fetchStatus());
	} catch (error) {
		showOffline(error);
	}
}

main();
