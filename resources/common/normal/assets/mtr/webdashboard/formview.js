import { createColorPicker } from './colorpicker.js?v=43';
import { createSelect } from './select.js?v=43';
import { isFieldVisible } from './editor.js?v=43';
import { t } from './i18n.js?v=43';

const FAILURE_STATUS_KEYS = {
	editor_offline: 'editEditorOffline',
	not_found: 'editObjectGone',
	forbidden: 'editNotPermitted',
	auth: 'editSessionExpired',
	no_server: 'editNoServer'
};

let nextFieldId = 0;

/**
 * Renders a form's fields into a dedicated host and keeps its values in `form.values`.
 *
 * The caller owns the save buttons and the status line. Fields that can disappear through a dependency
 * (`visible`, `hideWhenContinuous`) are wrapped in a collapsing layer so the change animates instead of
 * jumping, and they are marked inert while collapsed so a hidden control cannot be tabbed into.
 *
 * @returns {{destroy: Function, refreshVisibility: Function, showErrors: Function, clearErrors: Function}}
 */
export function mountFields(host, form, options) {
	const handlers = options || {};
	const entries = [];
	let colorPicker = null;

	host.textContent = '';

	form.fields.forEach(field => {
		const collapsible = Boolean(field.hideWhenContinuous) || typeof field.visible === 'function';
		const built = buildField(field, form, collapsible);
		entries.push({ field, wrapper: built.wrapper, collapsible, select: built.select || null });
		host.appendChild(built.wrapper);
	});

	form.fields.forEach(field => {
		if (field.type !== 'color') {
			return;
		}
		const wrapper = findWrapper(field.key);
		if (!wrapper) {
			return;
		}
		colorPicker = createColorPicker({
			color: form.values[field.key],
			onChange: next => {
				const previous = form.values[field.key];
				form.values[field.key] = next;
				clearError(field.key);
				if (previous !== next) {
					notifyChange();
				}
			}
		});
		const hex = colorPicker.root.querySelector('.color-picker__hex');
		if (hex) {
			hex.id = wrapper.dataset.inputId;
		}
		const hostElement = wrapper.querySelector('.edit-color');
		if (hostElement) {
			hostElement.appendChild(colorPicker.root);
		}
	});

	function findWrapper(key) {
		const entry = entries.find(candidate => candidate.field.key === key);
		return entry ? entry.wrapper : null;
	}

	function notifyChange() {
		const layoutChanged = refreshVisibility();
		if (handlers.onChange) {
			handlers.onChange(layoutChanged);
		}
	}

	function onControlEvent(event) {
		const target = event.target;
		if (!target || typeof target.closest !== 'function') {
			return;
		}
		const wrapper = target.closest('.edit-field');
		if (!wrapper) {
			return;
		}
		const entry = entries.find(candidate => candidate.wrapper === wrapper);
		if (!entry || entry.field.type === 'color' || entry.field.type === 'select' || entry.field.type === 'corners') {
			return;
		}

		form.values[entry.field.key] = readControl(entry.field, wrapper);
		clearError(entry.field.key);
		notifyChange();
	}

	/** @returns {boolean} whether anything actually collapsed or expanded, so only then is the card re-placed. */
	function refreshVisibility() {
		let layoutChanged = false;
		entries.forEach(entry => {
			const visible = isFieldVisible(entry.field, form);
			if (!visible && entry.select) {
				entry.select.close();
			}
			if (!entry.collapsible) {
				const nowHidden = !visible;
				layoutChanged = layoutChanged || entry.wrapper.hidden !== nowHidden;
				entry.wrapper.hidden = nowHidden;
				return;
			}
			const nowCollapsed = !visible;
			layoutChanged = layoutChanged || (entry.wrapper.dataset.collapsed === 'true') !== nowCollapsed;
			entry.wrapper.dataset.collapsed = String(nowCollapsed);
			entry.wrapper.inert = nowCollapsed;
			entry.wrapper.setAttribute('aria-hidden', String(nowCollapsed));
		});
		return layoutChanged;
	}

	function clearError(key) {
		const wrapper = findWrapper(key);
		if (!wrapper) {
			return;
		}
		const error = wrapper.querySelector('.edit-field__error');
		if (error) {
			error.hidden = true;
			error.textContent = '';
		}
		wrapper.removeAttribute('data-invalid');
	}

	function showErrors(errors) {
		(errors || []).forEach(error => {
			const wrapper = findWrapper(error.key);
			if (!wrapper) {
				return;
			}
			const element = wrapper.querySelector('.edit-field__error');
			if (element) {
				element.textContent = error.message;
				element.hidden = false;
			}
			wrapper.setAttribute('data-invalid', 'true');
		});
	}

	function clearErrors() {
		entries.forEach(entry => clearError(entry.field.key));
	}

	host.addEventListener('input', onControlEvent);
	host.addEventListener('change', onControlEvent);
	refreshVisibility();

	return {
		refreshVisibility,
		showErrors,
		clearErrors,
		clearError,
		destroy() {
			host.removeEventListener('input', onControlEvent);
			host.removeEventListener('change', onControlEvent);
			entries.forEach(entry => {
				if (entry.select) {
					entry.select.destroy();
				}
			});
			if (colorPicker) {
				colorPicker.dispose();
				colorPicker = null;
			}
			host.textContent = '';
		}
	};

	function buildField(field, form, collapsible) {
		const wrapper = document.createElement('div');
		wrapper.className = 'edit-field' + (collapsible ? ' edit-field--collapsible' : '');
		wrapper.dataset.key = field.key;
		if (collapsible) {
			wrapper.dataset.collapsed = isFieldVisible(field, form) ? 'false' : 'true';
		}

		const inner = collapsible ? document.createElement('div') : wrapper;
		if (collapsible) {
			inner.className = 'edit-field__inner';
			wrapper.appendChild(inner);
		}

		if (field.type === 'corners') {
			inner.appendChild(renderCornersButton(handlers));
			inner.appendChild(errorElement());
			return { wrapper };
		}

		const inputId = 'edit-field-' + (++nextFieldId);
		wrapper.dataset.inputId = inputId;
		let select = null;

		if (field.type === 'boolean') {
			wrapper.classList.add('edit-field--inline');
			const label = document.createElement('label');
			label.className = 'edit-field__label';
			label.htmlFor = inputId;
			label.textContent = field.label;
			inner.appendChild(label);
			inner.appendChild(renderSwitch(field, form, inputId));
		} else {
			const label = document.createElement('label');
			label.className = 'edit-field__label';
			label.htmlFor = inputId;
			label.textContent = field.label;
			inner.appendChild(label);

			if (field.type === 'select') {
				select = createSelect({
					id: inputId,
					options: field.options,
					value: form.values[field.key],
					onChange: next => {
						form.values[field.key] = next;
						clearError(field.key);
						notifyChange();
					}
				});
				inner.appendChild(select.root);
			} else if (field.type === 'color') {
				const hostElement = document.createElement('div');
				hostElement.className = 'edit-color';
				inner.appendChild(hostElement);
			} else {
				const input = document.createElement('input');
				input.id = inputId;
				input.className = 'edit-field__input';
				input.type = 'text';
				if (field.type === 'integer' || field.type === 'decimal') {
					input.inputMode = field.type === 'integer' ? 'numeric' : 'decimal';
				}
				input.value = form.values[field.key] == null ? '' : String(form.values[field.key]);
				if (field.maxLength) {
					input.maxLength = field.maxLength;
				}
				inner.appendChild(input);
			}
		}

		inner.appendChild(errorElement());
		return { wrapper, select };
	}
}

function renderSwitch(field, form, inputId) {
	const control = document.createElement('span');
	control.className = 'switch';

	const input = document.createElement('input');
	input.id = inputId;
	input.className = 'switch__input';
	input.type = 'checkbox';
	input.checked = form.values[field.key] === true;

	const track = document.createElement('span');
	track.className = 'switch__track';
	track.setAttribute('aria-hidden', 'true');
	const thumb = document.createElement('span');
	thumb.className = 'switch__thumb';
	track.appendChild(thumb);

	control.append(input, track);
	return control;
}

function renderCornersButton(handlers) {
	const button = document.createElement('button');
	button.type = 'button';
	button.className = 'button button--ghost';
	button.dataset.action = 'redraw-area';
	const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	icon.setAttribute('viewBox', '0 0 24 24');
	icon.setAttribute('width', '15');
	icon.setAttribute('height', '15');
	icon.setAttribute('aria-hidden', 'true');
	icon.setAttribute('focusable', 'false');
	const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
	path.setAttribute('d', 'M12 2a7 7 0 0 0-7 7c0 5.2 7 13 7 13s7-7.8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 14.5 9 2.5 2.5 0 0 1 12 11.5z');
	icon.appendChild(path);
	button.appendChild(icon);
	const label = document.createElement('span');
	label.textContent = t('editRedrawArea');
	button.appendChild(label);
	button.addEventListener('click', () => {
		if (handlers.onRedrawArea) {
			handlers.onRedrawArea();
		}
	});
	return button;
}

function errorElement() {
	const error = document.createElement('p');
	error.className = 'edit-field__error';
	error.hidden = true;
	return error;
}

function readControl(field, wrapper) {
	if (field.type === 'boolean') {
		const input = wrapper.querySelector('.switch__input');
		return Boolean(input && input.checked);
	}
	const input = wrapper.querySelector('.edit-field__input');
	return input ? input.value : '';
}

/**
 * @returns {{field: string|null, message: string|null, status: string, log: boolean}} how a save failure
 *          should be shown. Shared by the card and by the inline rows so both explain the same error the
 *          same way.
 */
export function describeFailure(error) {
	const kind = error && error.kind;
	if (kind === 'invalid_field') {
		if (error.field) {
			return { field: error.field, message: error.message || t('editInvalidField'), status: t('editFixFields'), log: false };
		}
		return { field: null, message: null, status: error.message || t('editSaveFailed'), log: false };
	}
	const key = FAILURE_STATUS_KEYS[kind];
	return {
		field: null,
		message: null,
		status: t(key || 'editSaveFailed'),
		log: !key
	};
}
