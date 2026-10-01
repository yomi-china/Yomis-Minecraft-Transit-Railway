import { t, applyTranslations } from './i18n.js';
import {
	SessionError,
	SessionFailure,
	clearTokenFromUrl,
	fetchSession,
	fetchStatus,
	login,
	logout,
	readTokenFromUrl
} from './session.js';

/*
 * Stage 2 front end: establish who the visitor is, then show the one view that matches.
 *
 * The order matters. A sign-in token is exchanged first, because everything after it depends on the
 * resulting cookie, and the token is erased from the URL immediately so it cannot be re-used by a
 * refresh or leak through the address bar.
 *
 * There is no editing yet - stage 3 adds it. What this stage proves is that an anonymous visitor is
 * told plainly that they cannot edit, and a signed-in one is recognised.
 */

const elements = {
	card: document.getElementById('dashboard-card'),
	statusBadge: document.getElementById('status-badge'),
	statusLabel: document.querySelector('#status-badge [data-i18n]'),
	accountBadge: document.getElementById('account-badge'),
	accountName: document.getElementById('account-name'),
	meta: document.getElementById('meta'),
	version: document.getElementById('meta-version'),
	port: document.getElementById('meta-port'),
	lanWarning: document.getElementById('lan-warning'),
	signOut: document.getElementById('sign-out')
};

function setStatus(state, labelKey) {
	if (elements.statusBadge) {
		elements.statusBadge.dataset.state = state;
	}
	if (elements.statusLabel) {
		elements.statusLabel.textContent = t(labelKey);
	}
}

/**
 * Switches the card to one view. The views are siblings in the markup and CSS shows exactly the one
 * matching this attribute, so there is no way to end up with two of them visible at once.
 */
function setView(view) {
	if (elements.card) {
		elements.card.dataset.view = view;
	}
}

function setAccount(username) {
	if (!elements.accountBadge || !elements.accountName) {
		return;
	}
	if (username) {
		elements.accountName.textContent = username;
		elements.accountBadge.hidden = false;
	} else {
		elements.accountBadge.hidden = true;
	}
}

function setMeta(status) {
	if (!elements.meta) {
		return;
	}
	elements.meta.hidden = false;
	if (elements.version) {
		elements.version.textContent = status.version || '—';
	}
	if (elements.port) {
		elements.port.textContent = status.port != null ? String(status.port) : '—';
	}
	// Surfaced in the page, not just the log: turning this on is what puts sign-in tokens on the wire
	// in clear text, and the person reading this page is the one who can change it.
	if (elements.lanWarning) {
		elements.lanWarning.hidden = !status.lanAccess;
	}
}

/**
 * Renders whichever of the two signed states applies.
 */
function renderSession(session) {
	setAccount(session.username || null);

	if (session.canEdit) {
		setStatus('ready', 'statusReady');
		setView('signedin');
	} else if (session.authenticated) {
		// Signed in but not permitted. Reachable if access is revoked while a tab is open; the server
		// invalidates the session, so in practice this shows up as anonymous on the next load.
		setStatus('readonly', 'statusReadOnly');
		setView('readonly');
	} else {
		setStatus('readonly', 'statusReadOnly');
		setView('readonly');
	}
}

function renderOffline(reason) {
	setStatus('offline', 'statusOffline');
	setView('offline');
	// Logged rather than shown: the visitor cannot act on an HTTP status, but it is the first thing
	// worth knowing when the page will not load.
	console.warn('[MTR-WebDashboard] Could not reach the service:', reason);
}

async function main() {
	applyTranslations(document);

	if (elements.signOut) {
		elements.signOut.addEventListener('click', async () => {
			// Disabled first so a double click cannot fire two sign-outs and leave the button looking
			// live while the first request is still in flight.
			elements.signOut.disabled = true;
			await logout();
			window.location.reload();
		});
	}

	// A token in the fragment means the in-game button produced this page load. Burn it immediately.
	const token = readTokenFromUrl();
	if (token) {
		try {
			const session = await login(token);
			clearTokenFromUrl();
			renderSession(session);
			// Then confirm with a normal session read, so what is displayed comes from the same path an
			// ordinary reload would take rather than from the sign-in response alone.
			renderSession(await fetchSession());
			await refreshMeta();
			return;
		} catch (error) {
			clearTokenFromUrl();
			if (error instanceof SessionFailure && error.kind === SessionError.REJECTED) {
				// Expired or already used. Distinct from "unreachable", because the fix is different.
				setStatus('offline', 'statusOffline');
				setView('expired');
				await refreshMeta();
				return;
			}
			renderOffline(error);
			return;
		}
	}

	try {
		renderSession(await fetchSession());
		await refreshMeta();
	} catch (error) {
		renderOffline(error);
	}
}

/**
 * Service details, fetched separately so a failure here does not change which view is shown - the
 * page is still usable without the version and port.
 */
async function refreshMeta() {
	try {
		setMeta(await fetchStatus());
	} catch (error) {
		console.warn('[MTR-WebDashboard] Could not read service status:', error);
	}
}

main();
