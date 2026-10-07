import { buildPayload, createForm, markClean } from './editor.js?v=20';
import { createColorPicker } from './colorpicker.js?v=20';
import { placePopover } from './focus.js?v=20';
import { t } from './i18n.js?v=20';

const BODY_PADDING = 20;
const ARROW_SIZE = 20;
const FAILURE_STATUS_KEYS = {
	editor_offline: 'editEditorOffline',
	not_found: 'editObjectGone',
	forbidden: 'editNotPermitted',
	auth: 'editSessionExpired',
	no_server: 'editNoServer'
};

const el = {
	card: null,
	title: null,
	body: null,
	status: null,
	save: null,
	cancel: null
};

let session = null;
let colorPicker = null;
let saving = false;

export function open(options) {
	close({ silent: true });

	const form = createForm(options.kind, options.object);
	if (form.fields.length === 0) {
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

export function close(options) {
	const opts = options || {};
	if (session == null) {
		return true;
	}

	if (el.card && el.card.parentNode) {
		el.card.parentNode.removeChild(el.card);
	}
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

function build() {
	const card = document.createElement('div');
	card.className = 'edit-card';
	card.setAttribute('role', 'dialog');
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

	el.body.addEventListener('input', onFieldInput);
	el.body.addEventListener('change', onFieldInput);
}

function render() {
	const form = session.form;
	el.title.textContent = t(form.kind === 'station' ? 'editStationTitle' : form.kind === 'route' ? 'editRouteTitle' : 'editDepotTitle');
	el.cancel.textContent = t('editCancel');
	el.save.textContent = saving ? t('editSaving') : t('editSave');

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

export function setStatus(text, level) {
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

function renderField(field, value) {
	const wrapper = document.createElement('div');
	wrapper.className = 'edit-field';
	wrapper.dataset.key = field.key;

	if (field.type === 'corners') {
		const button = document.createElement('button');
		button.type = 'button';
		button.className = 'button button--tonal button--full';
		button.dataset.action = 'redraw-area';
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

	colorPicker = createColorPicker({
		color: value,
		onChange: next => {
			if (session == null) {
				return;
			}
			const changed = session.form.values.color !== next;
			session.form.values.color = next;
			clearFieldError('color');
			if (changed) {
				updateSaveState();
			}
		}
	});

	const hex = colorPicker.root.querySelector('.color-picker__hex');
	if (hex) {
		hex.id = inputId;
	}

	container.appendChild(colorPicker.root);
	return container;
}

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

async function submit() {
	if (session == null || saving) {
		return;
	}

	const { fields, errors } = buildPayload(session.form);
	if (errors.length > 0) {
		errors.forEach(error => showFieldError(error.key, error.message));
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
		saving = false;
		render();
		handleSaveFailure(error);
		return;
	}

	saving = false;
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

	if (session.onSave) {
		session.onSave();
	}
	close({ silent: true });
}

function handleSaveFailure(error) {
	const kind = error && error.kind;
	if (kind === 'invalid_field') {
		if (error.field) {
			showFieldError(error.field, error.message || t('editInvalidField'));
			setStatus(t('editFixFields'), 'error');
		} else {
			setStatus(error.message || t('editSaveFailed'), 'error');
		}
		return;
	}
	setStatus(t(FAILURE_STATUS_KEYS[kind] || 'editSaveFailed'), 'error');
	if (!FAILURE_STATUS_KEYS[kind]) {
		console.error('[MTR-WebDashboard] save failed', error);
	}
}

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
		el.card.style.width = placement.width + 'px';
		el.card.style.maxHeight = Math.max(120, area.height - placement.top - 8) + 'px';
	}

	el.arrow.hidden = !placement.arrowVisible;
	el.arrow.dataset.side = placement.arrowSide;
	if (placement.arrowVisible) {
		el.arrow.style.top = (placement.arrowY - ARROW_SIZE / 2) + 'px';
		el.arrow.style.left = placement.arrowSide === 'left' ? (-ARROW_SIZE / 2 + 2) + 'px' : (card.width - ARROW_SIZE / 2 - 2) + 'px';
	}

	if (el.body) {
		el.body.style.paddingBottom = BODY_PADDING + 'px';
	}
}
