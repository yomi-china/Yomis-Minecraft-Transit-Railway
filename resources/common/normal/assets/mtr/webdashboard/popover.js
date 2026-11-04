import { buildPayload, createForm, getDescriptor } from './editor.js?v=43';
import { describeFailure, mountFields } from './formview.js?v=43';
import { mount as mountChildren } from './children.js?v=43';
import { placePopover } from './focus.js?v=43';
import { t } from './i18n.js?v=43';

const BODY_PADDING = 20;
const ARROW_SIZE = 20;
const LAYOUT_FOLLOW_MS = 380;

const el = {
	card: null,
	title: null,
	body: null,
	fields: null,
	children: null,
	status: null,
	save: null,
	cancel: null
};

let session = null;
let formView = null;
let childrenView = null;
let saving = false;
let layoutFrame = 0;
let layoutUntil = 0;

export function open(options) {
	close({ silent: true });

	const form = createForm(options.kind, options.object, options.context);
	if (form.fields.length === 0) {
		return;
	}

	session = {
		form,
		anchor: options.anchor,
		container: options.container,
		meta: options.meta || null,
		childKind: options.childKind || null,
		childObjects: Array.isArray(options.childObjects) ? options.childObjects : [],
		onSubmit: options.onSubmit,
		onSubmitChild: options.onSubmitChild,
		onSave: options.onSave,
		onRedrawArea: options.onRedrawArea,
		onClose: options.onClose,
		onChildFocus: options.onChildFocus,
		onLayoutChange: options.onLayoutChange
	};

	build();
	render();
	position();
	followLayout(LAYOUT_FOLLOW_MS);
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

	stopFollowingLayout();
	if (childrenView) {
		childrenView.destroy();
		childrenView = null;
	}
	if (formView) {
		formView.destroy();
		formView = null;
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

function build() {
	const card = document.createElement('div');
	card.className = 'edit-card';
	card.setAttribute('role', 'dialog');
	card.setAttribute('aria-modal', 'false');

	const glass = document.createElement('div');
	glass.className = 'edit-card__glass';
	glass.setAttribute('aria-hidden', 'true');
	const blur = document.createElement('div');
	blur.className = 'edit-card__blur';
	glass.appendChild(blur);
	card.appendChild(glass);

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
	closeButton.setAttribute('aria-label', t('editClose'));
	closeButton.title = t('editClose');
	closeButton.textContent = '\u00D7';
	closeButton.addEventListener('click', () => close());
	header.appendChild(closeButton);

	card.appendChild(header);

	const body = document.createElement('div');
	body.className = 'edit-card__body';
	card.appendChild(body);

	const fields = document.createElement('div');
	fields.className = 'edit-card__fields';
	body.appendChild(fields);

	const children = document.createElement('div');
	children.className = 'edit-card__children';
	children.hidden = true;
	body.appendChild(children);

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
	el.fields = fields;
	el.children = children;
	el.status = status;
	el.save = save;
	el.cancel = cancel;
}

function render() {
	const form = session.form;
	const descriptor = getDescriptor(form.kind);
	el.title.textContent = t(descriptor ? descriptor.titleKey : 'editStationTitle');
	el.cancel.textContent = t('editCancel');
	el.save.textContent = t(descriptor && descriptor.saveKey ? descriptor.saveKey : 'editSave');

	formView = mountFields(el.fields, form, {
		onChange: updateSaveState,
		onRedrawArea: () => {
			if (session && session.onRedrawArea) {
				session.onRedrawArea();
			}
		}
	});

	if (session.childKind) {
		el.children.hidden = false;
		childrenView = mountChildren(el.children, {
			kind: session.childKind,
			objects: session.childObjects,
			meta: session.meta,
			onSubmit: (kind, id, fields) => session.onSubmitChild(kind, id, fields),
			onFocus: id => {
				if (session && session.onChildFocus) {
					session.onChildFocus(id);
				}
			},
			onLayoutChange: () => {
				if (session) {
					followLayout(LAYOUT_FOLLOW_MS);
				}
			}
		});
	}

	updateSaveState();
}

function updateSaveState() {
	if (!session) {
		return;
	}
	const { fields } = buildPayload(session.form);
	el.save.disabled = saving || Object.keys(fields).length === 0;
}

async function submit() {
	if (session == null || saving) {
		return;
	}

	const { fields, errors } = buildPayload(session.form);
	if (formView) {
		formView.clearErrors();
	}
	if (errors.length > 0) {
		if (formView) {
			formView.showErrors(errors);
		}
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

	try {
		await session.onSubmit(session.form.kind, session.form.id, fields);
	} catch (error) {
		saving = false;
		el.save.textContent = t('editSave');
		handleSaveFailure(error);
		updateSaveState();
		return;
	}

	saving = false;
	const onSave = session.onSave;
	close({ silent: true });
	if (onSave) {
		onSave();
	}
}

function handleSaveFailure(error) {
	const failure = describeFailure(error);
	if (failure.field && formView) {
		formView.showErrors([{ key: failure.field, message: failure.message }]);
	}
	setStatus(failure.status, 'error');
	if (failure.log) {
		console.error('[MTR-WebDashboard] save failed', error);
	}
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

function now() {
	return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();
}

/**
 * Re-places the card on every frame while a child row animates. The card is clipped by the map area, and its
 * height changes over ~260ms, so a single re-place at the end would let it hang off the bottom edge first.
 */
function followLayout(durationMs) {
	layoutUntil = Math.max(layoutUntil, now() + durationMs);
	if (typeof requestAnimationFrame !== 'function') {
		position();
		return;
	}
	if (layoutFrame) {
		return;
	}
	const tick = () => {
		layoutFrame = 0;
		if (!session) {
			return;
		}
		position();
		if (now() < layoutUntil) {
			layoutFrame = requestAnimationFrame(tick);
		}
	};
	layoutFrame = requestAnimationFrame(tick);
}

function stopFollowingLayout() {
	if (layoutFrame && typeof cancelAnimationFrame === 'function') {
		cancelAnimationFrame(layoutFrame);
	}
	layoutFrame = 0;
	layoutUntil = 0;
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
