import { buildPayload, createForm, markClean } from './editor.js?v=20';
import { createColorPicker } from './colorpicker.js?v=20';
import { placePopover } from './focus.js?v=20';
import { t } from './i18n.js?v=20';

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

/** The mounted colour picker, or null. Held so its queued animation frame can be cancelled on close. */
let colorPicker = null;

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
 * @param {() => void} options.onRedrawArea called when the selection button is pressed, to start drawing.
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
		onRedrawArea: options.onRedrawArea,
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
 * Dismissing always discards, without asking. The form holds three small values, and a dialog standing in
 * front of "I changed my mind" costs more than retyping a name - so `options.force` exists only for the
 * internal paths that are rebuilding the card anyway, and there is no confirmation anywhere.
 *
 * @param {{silent?: boolean, force?: boolean}} [options] `silent` skips the onClose callback.
 * @returns {boolean} whether it closed, which is always true.
 */
export function close(options) {
	const opts = options || {};
	if (session == null) {
		return true;
	}

	if (el.card && el.card.parentNode) {
		el.card.parentNode.removeChild(el.card);
	}
	// Before the reference is dropped: a picker left mid-drag holds a queued animation frame that would run
	// against a detached element.
	if (colorPicker) {
		colorPicker.dispose();
		colorPicker = null;
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

	// The picker is rebuilt along with the rest of the body, so the old one's queued frame is dropped first.
	if (colorPicker) {
		colorPicker.dispose();
		colorPicker = null;
	}
	el.body.textContent = '';
	form.fields.forEach(field => {
		el.body.appendChild(renderField(field, form.values[field.key]));
	});

	updateSaveState();
}

function titleKeyFor(kind) {
	return kind === 'station' ? 'editStationTitle' : kind === 'route' ? 'editRouteTitle' : 'editDepotTitle';
}

/**
 * Replaces the form's selection and re-renders just that field.
 *
 * Called when a rectangle has been drawn on the map. Only the one field is rebuilt: re-rendering the whole
 * card would throw away the colour picker's state, including the hue the visitor had chosen, which has no
 * other home.
 */
export function setSelection(value) {
	if (session == null) {
		return;
	}
	const field = session.form.fields.find(candidate => candidate.type === 'corners');
	if (!field) {
		return;
	}

	session.form.values[field.key] = value;
	const existing = el.body.querySelector('.edit-field[data-key="' + field.key + '"]');
	if (existing) {
		existing.parentNode.replaceChild(renderField(field, value), existing);
	}
	clearFieldError(field.key);
	updateSaveState();
}

/** @returns {boolean} whether the open form holds a selection at all, so the caller knows to offer one. */
export function hasSelectionField() {
	return session != null && session.form.fields.some(field => field.type === 'corners');
}

/**
 * Writes a line into the card's status area.
 *
 * Exported so a caller that finishes something after the card has been rebuilt can still report the outcome.
 * Saving a selection is exactly that case: the world is reloaded first, so the card the visitor was looking at
 * is gone by the time the server's answer arrives, and a message set on the old one would go with it.
 */
export function setStatus(text, level) {
	applyStatus(text, level);
}

function renderField(field, value) {
	const wrapper = document.createElement('div');
	wrapper.className = 'edit-field';
	wrapper.dataset.key = field.key;

	// A selection is drawn on the map rather than typed, so this field renders as an action instead of an
	// input. It still takes part in the form, which is what lets the drawn rectangle go through the same
	// dirty check and patch path as everything else.
	if (field.type === 'corners') {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'button button--tonal button--full';
		button.dataset.action = 'redraw-area';
		// One label, not two. An earlier version switched to "draw" when there was no selection yet and added
		// a line underneath saying so, which was both unwanted and redundant - the map already shows whether
		// an area exists.
		button.textContent = t('editRedrawArea');
		button.addEventListener('click', () => {
			if (session && session.onRedrawArea) {
				session.onRedrawArea();
			}
		});
		wrapper.appendChild(button);
		return wrapper;
	}

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

/**
 * Mounts the continuous picker.
 *
 * It owns its own display, so nothing here writes a swatch or a colour value back into it - the only job of
 * the callback is to move the value into the form so the dirty check and the save button see it. Doing it
 * the other way round is what makes a picker jump about while it is being dragged.
 */
function renderColorInput(field, value, inputId) {
	const container = document.createElement('div');
	container.className = 'edit-color';

	colorPicker = createColorPicker({
		color: value,
		onChange: next => {
			if (session == null) {
				return;
			}
			const changed = session.form.values.color !== next;
			session.form.values.color = next;
			clearFieldError('color');
			// The save state is only recomputed when the value actually differs, so dragging within one
			// colour step does not rebuild the payload on every frame.
			if (changed) {
				updateSaveState();
			}
		}
	});

	// The picker's own hex field must carry the form's field id, so the label written by renderField points
	// at it and clicking the label focuses the input.
	const hex = colorPicker.root.querySelector('.color-picker__hex');
	if (hex) {
		hex.id = inputId;
	}

	container.appendChild(colorPicker.root);
	return container;
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

	// A colour field is driven by the picker, which reports through its own callback rather than through a
	// bubbling input event. Falling through here would write the hex field's raw text into the form.
	if (field.type === 'color') {
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

function applyStatus(text, level) {
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
		applyStatus(t('editFixFields'), 'error');
		return;
	}
	if (Object.keys(fields).length === 0) {
		return;
	}

	saving = true;
	el.save.disabled = true;
	el.save.textContent = t('editSaving');
	applyStatus(t('editSaving'), 'info');

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

	// The card closes on success. The status line was being written and then immediately hidden behind a
	// card the visitor had to dismiss by hand, which made every save feel like it had not finished.
	if (session.onSave) {
		session.onSave();
	}
	close({ force: true, silent: true });
}

function handleSaveFailure(error) {
	const kind = error && error.kind;
	if (kind === 'editor_offline') {
		applyStatus(t('editEditorOffline'), 'error');
		return;
	}
	if (kind === 'not_found') {
		applyStatus(t('editObjectGone'), 'error');
		return;
	}
	if (kind === 'forbidden') {
		applyStatus(t('editNotPermitted'), 'error');
		return;
	}
	if (kind === 'auth') {
		applyStatus(t('editSessionExpired'), 'error');
		return;
	}
	if (kind === 'invalid_field') {
		// The server named the field, so the message goes next to it rather than in the status line.
		if (error.field) {
			showFieldError(error.field, error.message || t('editInvalidField'));
			applyStatus(t('editFixFields'), 'error');
		} else {
			applyStatus(error.message || t('editSaveFailed'), 'error');
		}
		return;
	}
	if (kind === 'no_server') {
		applyStatus(t('editNoServer'), 'error');
		return;
	}
	applyStatus(t('editSaveFailed'), 'error');
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
