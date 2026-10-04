import { PALETTE, buildPayload, createForm, describeResult, formatHexColor, markClean, parseHexColor } from './editor.js?v=16';
import { placePopover } from './focus.js?v=16';
import { f, t } from './i18n.js?v=16';

/*
 * The editing card: a non-modal MD3 popover anchored to the object being edited on the map.
 *
 * Non-modal on purpose. The game makes you open a screen, and the dashboard should not - the point of
 * putting the fields next to the map is that the visitor can still see the station they are renaming and
 * pan around while deciding. Nothing here traps focus or blocks the map underneath.
 *
 * The placement arithmetic lives in focus.js, which is pure and tested; this file only measures, positions
 * and wires up the controls.
 */

/** Gap to leave below the card when it has to be scrolled, so the last field is not flush with the edge. */
const BODY_PADDING = 20;

const el = {
	card: null,
	title: null,
	body: null,
	status: null,
	save: null,
	cancel: null,
	close: null
};

/** The open editor, or null. Holds the form, the anchor and the callbacks. */
let session = null;

/** Whether a save is in flight, so the buttons can be locked and double submits cannot happen. */
let saving = false;

/**
 * Opens the card for an object.
 *
 * @param {object} options
 * @param {'station'|'route'|'depot'} options.kind
 * @param {object} options.object      the payload, for the starting values.
 * @param {{x: number, y: number}} options.anchor where to point, in map-area coordinates.
 * @param {HTMLElement} options.container the map area, which the card is positioned inside.
 * @param {() => void} options.onSave   called after a successful save, to refresh the page's data.
 * @param {(reason: string) => boolean} options.confirmDiscard asked before dropping unsaved changes.
 * @param {() => void} options.onClose  called when the card closes, whatever the reason.
 */
export function open(options) {
	close({ silent: true });

	const form = createForm(options.kind, options.object);
	if (form.fields.length === 0) {
		// Nothing editable, so there is no card to show. Better than an empty card that looks broken.
		return;
	}

	session = {
		form,
		anchor: options.anchor,
		container: options.container,
		onSubmit: options.onSubmit,
		onSave: options.onSave,
		confirmDiscard: options.confirmDiscard,
		onClose: options.onClose
	};

	build();
	render();
	position();
}

/** @returns {boolean} whether the card is open. */
export function isOpen() {
	return session != null;
}

/**
 * Closes the card.
 *
 * @param {{silent?: boolean, force?: boolean}} [options] `force` skips the unsaved-changes question, for the
 *        cases where the change is already gone (a successful save, or the page reloading).
 * @returns {boolean} whether it closed. False means the visitor chose to keep editing.
 */
export function close(options) {
	const opts = options || {};
	if (session == null) {
		return true;
	}
	if (!opts.force && !opts.silent && hasChanges() && session.confirmDiscard && !session.confirmDiscard('unsaved')) {
		return false;
	}

	if (el.card && el.card.parentNode) {
		el.card.parentNode.removeChild(el.card);
	}
	const onClose = session.onClose;
	session = null;
	saving = false;
	if (onClose && !opts.silent) {
		onClose();
	}
	return true;
}

/** Repositions the card after the map moved or the area resized. */
export function reposition() {
	if (session != null) {
		position();
	}
}

/**
 * Updates the anchor after a flight lands.
 *
 * The anchor is computed against the target view, so it is already where the object will be once the map
 * settles. Re-reading it after the flight keeps the card correct if the view ended up somewhere else.
 */
export function setAnchor(anchor) {
	if (session != null && anchor) {
		session.anchor = anchor;
		position();
	}
}

function hasChanges() {
	return session != null && Object.keys(buildPayload(session.form).fields).length > 0;
}

// ---- structure ----------------------------------------------------------

function build() {
	const card = document.createElement('div');
	card.className = 'edit-card';
	card.setAttribute('role', 'dialog');
	// Not aria-modal: the whole point is that the map stays usable while this is open.
	card.setAttribute('aria-modal', 'false');

	const arrow = document.createElement('div');
	arrow.className = 'edit-card__arrow';
	card.appendChild(arrow);

	const header = document.createElement('div');
	header.className = 'edit-card__header';

	const title = document.createElement('h2');
	title.className = 'edit-card__title';
	header.appendChild(title);

	const closeButton = document.createElement('button');
	closeButton.type = 'button';
	closeButton.className = 'icon-button icon-button--small';
	closeButton.dataset.i18nTitle = 'editClose';
	closeButton.dataset.i18nAriaLabel = 'editClose';
	closeButton.setAttribute('aria-label', t('editClose'));
	closeButton.title = t('editClose');
	closeButton.textContent = '\u00D7';
	closeButton.addEventListener('click', () => close());
	header.appendChild(closeButton);

	card.appendChild(header);

	const body = document.createElement('div');
	body.className = 'edit-card__body';
	card.appendChild(body);

	const status = document.createElement('p');
	status.className = 'edit-card__status';
	status.hidden = true;
	card.appendChild(status);

	const actions = document.createElement('div');
	actions.className = 'edit-card__actions';

	const cancel = document.createElement('button');
	cancel.type = 'button';
	cancel.className = 'button button--text';
	cancel.addEventListener('click', () => close());
	actions.appendChild(cancel);

	const save = document.createElement('button');
	save.type = 'button';
	save.className = 'button button--filled';
	save.addEventListener('click', () => submit());
	actions.appendChild(save);

	card.appendChild(actions);
	// A click inside must not reach the map, which would pan or clear the selection.
	card.addEventListener('pointerdown', event => event.stopPropagation());
	card.addEventListener('wheel', event => event.stopPropagation());

	session.container.appendChild(card);

	el.card = card;
	el.arrow = arrow;
	el.title = title;
	el.body = body;
	el.status = status;
	el.save = save;
	el.cancel = cancel;
	el.close = closeButton;

	el.body.addEventListener('input', onFieldInput);
	el.body.addEventListener('change', onFieldInput);
}

function render() {
	const form = session.form;
	el.title.textContent = t(titleKeyFor(form.kind));
	el.cancel.textContent = t('editCancel');
	el.save.textContent = saving ? t('editSaving') : t('editSave');

	el.body.textContent = '';
	form.fields.forEach(field => {
		el.body.appendChild(renderField(field, form.values[field.key]));
	});

	updateSaveState();
}

function titleKeyFor(kind) {
	return kind === 'station' ? 'editStationTitle' : kind === 'route' ? 'editRouteTitle' : 'editDepotTitle';
}

function renderField(field, value) {
	const wrapper = document.createElement('div');
	wrapper.className = 'edit-field';
	wrapper.dataset.key = field.key;

	const label = document.createElement('label');
	label.className = 'edit-field__label';
	label.textContent = field.label;
	const inputId = 'edit-field-' + field.key;
	label.htmlFor = inputId;
	wrapper.appendChild(label);

	if (field.type === 'color') {
		wrapper.appendChild(renderColorInput(field, value, inputId));
	} else {
		const input = document.createElement('input');
		input.id = inputId;
		input.className = 'edit-field__input';
		input.type = 'text';
		// inputmode rather than type=number: a number input changes its value when the wheel scrolls over it,
		// which is easy to do accidentally while scrolling the card.
		if (field.type === 'integer') {
			input.inputMode = 'numeric';
		}
		input.value = value == null ? '' : String(value);
		if (field.maxLength) {
			input.maxLength = field.maxLength;
		}
		wrapper.appendChild(input);
	}

	const error = document.createElement('p');
	error.className = 'edit-field__error';
	error.hidden = true;
	wrapper.appendChild(error);

	return wrapper;
}

function renderColorInput(field, value, inputId) {
	const container = document.createElement('div');
	container.className = 'edit-color';

	const swatches = document.createElement('div');
	swatches.className = 'edit-color__swatches';
	PALETTE.forEach(color => {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'edit-color__swatch';
		button.style.backgroundColor = '#' + formatHexColor(color);
		button.dataset.color = String(color);
		button.title = '#' + formatHexColor(color);
		button.setAttribute('aria-label', '#' + formatHexColor(color));
		if (color === value) {
			button.dataset.selected = 'true';
		}
		button.addEventListener('click', () => setColor(color));
		swatches.appendChild(button);
	});
	container.appendChild(swatches);

	const row = document.createElement('div');
	row.className = 'edit-color__row';

	const preview = document.createElement('span');
	preview.className = 'edit-color__preview';
	preview.id = 'edit-color-preview';
	preview.style.backgroundColor = '#' + formatHexColor(value);
	row.appendChild(preview);

	const hex = document.createElement('input');
	hex.id = inputId;
	hex.className = 'edit-field__input edit-color__hex';
	hex.type = 'text';
	hex.inputMode = 'text';
	hex.spellcheck = false;
	hex.maxLength = 7;
	hex.value = formatHexColor(value);
	hex.setAttribute('aria-label', field.label);
	row.appendChild(hex);

	container.appendChild(row);
	return container;
}

function setColor(color) {
	if (session == null) {
		return;
	}
	session.form.values.color = color;
	const hex = el.body.querySelector('.edit-color__hex');
	if (hex) {
		hex.value = formatHexColor(color);
	}
	const preview = el.body.querySelector('.edit-color__preview');
	if (preview) {
		preview.style.backgroundColor = '#' + formatHexColor(color);
	}
	el.body.querySelectorAll('.edit-color__swatch').forEach(button => {
		if (Number(button.dataset.color) === color) {
			button.dataset.selected = 'true';
		} else {
			delete button.dataset.selected;
		}
	});
	clearFieldError('color');
	updateSaveState();
}

// ---- input --------------------------------------------------------------

function onFieldInput(event) {
	if (session == null) {
		return;
	}
	const wrapper = event.target.closest('.edit-field');
	if (!wrapper) {
		return;
	}
	const key = wrapper.dataset.key;
	const field = session.form.fields.find(candidate => candidate.key === key);
	if (!field) {
		return;
	}

	if (field.type === 'color') {
		// The hex box is parsed as it is typed, but only applied when it is a complete colour - otherwise
		// every intermediate keystroke would reset the swatch highlight.
		const parsed = parseHexColor(event.target.value);
		if (parsed != null) {
			setColor(parsed);
		}
		return;
	}

	session.form.values[key] = event.target.value;
	clearFieldError(key);
	updateSaveState();
}

function updateSaveState() {
	if (session == null) {
		return;
	}
	const { fields, errors } = buildPayload(session.form);
	const dirty = Object.keys(fields).length > 0;
	el.save.disabled = saving || !dirty || errors.length > 0;
}

function clearFieldError(key) {
	if (!el.body) {
		return;
	}
	const wrapper = el.body.querySelector('.edit-field[data-key="' + key + '"]');
	if (wrapper) {
		const error = wrapper.querySelector('.edit-field__error');
		if (error) {
			error.hidden = true;
			error.textContent = '';
		}
	}
}

function showFieldError(key, message) {
	if (!el.body) {
		return;
	}
	const wrapper = el.body.querySelector('.edit-field[data-key="' + key + '"]');
	if (wrapper) {
		const error = wrapper.querySelector('.edit-field__error');
		if (error) {
			error.textContent = message;
			error.hidden = false;
		}
	}
}

function setStatus(text, level) {
	if (!el.status) {
		return;
	}
	if (!text) {
		el.status.hidden = true;
		el.status.textContent = '';
		return;
	}
	el.status.textContent = text;
	el.status.dataset.level = level || 'info';
	el.status.hidden = false;
}

// ---- saving -------------------------------------------------------------

async function submit() {
	if (session == null || saving) {
		return;
	}

	const { fields, errors } = buildPayload(session.form);
	if (errors.length > 0) {
		errors.forEach(error => showFieldError(error.key, error.message));
		// The save button is already disabled for client-side errors, so reaching here means a field changed
		// between the check and the click. Saying so beats a silently dead button.
		setStatus(t('editFixFields'), 'error');
		return;
	}
	if (Object.keys(fields).length === 0) {
		return;
	}

	saving = true;
	el.save.disabled = true;
	el.save.textContent = t('editSaving');
	setStatus(t('editSaving'), 'info');

	let result;
	try {
		result = await session.onSubmit(session.form.kind, session.form.id, fields);
	} catch (error) {
		// The card stays open and the typed values stay in it. Closing on failure would throw away work on
		// the one occasion the visitor most needs it back.
		saving = false;
		render();
		handleSaveFailure(error);
		return;
	}

	saving = false;
	// The server may have adjusted a value, so the baseline is taken from what it echoed rather than from
	// what was sent - otherwise a clamped colour would leave the form dirty against the typed value.
	if (result && result.object) {
		const fieldKeys = session.form.fields.map(field => field.key);
		fieldKeys.forEach(key => {
			if (result.object[key] !== undefined) {
				session.form.values[key] = session.form.fields.find(field => field.key === key).type === 'color'
					? Number(result.object[key])
					: String(result.object[key]);
			}
		});
	}
	markClean(session.form);
	render();

	const summary = describeResult(result);
	setStatus(f(summary.key, ...summary.values), summary.level);

	if (session.onSave) {
		session.onSave();
	}
}

function handleSaveFailure(error) {
	const kind = error && error.kind;
	if (kind === 'editor_offline') {
		setStatus(t('editEditorOffline'), 'error');
		return;
	}
	if (kind === 'not_found') {
		setStatus(t('editObjectGone'), 'error');
		return;
	}
	if (kind === 'forbidden') {
		setStatus(t('editNotPermitted'), 'error');
		return;
	}
	if (kind === 'auth') {
		setStatus(t('editSessionExpired'), 'error');
		return;
	}
	if (kind === 'invalid_field') {
		// The server named the field, so the message goes next to it rather than in the status line.
		if (error.field) {
			showFieldError(error.field, error.message || t('editInvalidField'));
			setStatus(t('editFixFields'), 'error');
		} else {
			setStatus(error.message || t('editSaveFailed'), 'error');
		}
		return;
	}
	if (kind === 'no_server') {
		setStatus(t('editNoServer'), 'error');
		return;
	}
	setStatus(t('editSaveFailed'), 'error');
	console.error('[MTR-WebDashboard] save failed', error);
}

// ---- placement ----------------------------------------------------------

/**
 * Puts the card next to its anchor, clamped into the map area.
 *
 * Measured after the content is in the DOM, because the card's height depends on how many fields the
 * resource has and its width depends on the longest label.
 */
function position() {
	if (session == null || !el.card) {
		return;
	}
	const area = session.container.getBoundingClientRect();
	const card = el.card.getBoundingClientRect();

	const placement = placePopover(session.anchor, { width: card.width, height: card.height }, { width: area.width, height: area.height });

	el.card.style.left = placement.left + 'px';
	el.card.style.top = placement.top + 'px';
	if (placement.docked) {
		// The card could not sit beside the anchor, so it is pinned along the bottom and its height is capped.
		el.card.style.width = placement.width + 'px';
		el.card.style.maxHeight = Math.max(120, area.height - placement.top - 8) + 'px';
	}

	el.arrow.hidden = !placement.arrowVisible;
	el.arrow.dataset.side = placement.arrowSide;
	const arrowSize = 20;
	if (placement.arrowVisible) {
		// arrowY is the anchor's offset within the card; the rotate(45deg) in CSS is about the arrow's centre.
		el.arrow.style.top = (placement.arrowY - arrowSize / 2) + 'px';
		el.arrow.style.left = placement.arrowSide === 'left' ? (-arrowSize / 2 + 2) + 'px' : (card.width - arrowSize / 2 - 2) + 'px';
	}

	// The body scrolls rather than the card growing past the map, so a card with many fields stays usable.
	if (el.body) {
		el.body.style.paddingBottom = BODY_PADDING + 'px';
	}
}
