import {
	CHILD_LABELS,
	buildPayload,
	contextFor,
	createForm,
	summarizeSavedRail
} from './editor.js?v=43';
import { describeFailure, mountFields } from './formview.js?v=43';
import { t } from './i18n.js?v=43';

/**
 * Mounts the child rows of a parent card (platforms under a station, sidings under a depot).
 *
 * One row expands at a time and every row saves itself: a platform is one PATCH, a siding is another, and a
 * failure in one must not discard what was typed in the others. Collapsing a row, or opening a different one,
 * drops its draft silently - the same rule the selection editor follows.
 *
 * @returns {{destroy: Function}}
 */
export function mount(host, options) {
	const settings = options || {};
	const childKind = settings.kind;
	const meta = settings.meta || null;
	const labels = CHILD_LABELS[childKind] || { sectionKey: 'sectionPlatforms', emptyKey: '' };
	const objects = Array.isArray(settings.objects) ? settings.objects : [];

	host.textContent = '';

	const section = document.createElement('section');
	section.className = 'child-section';

	const head = document.createElement('div');
	head.className = 'child-section__head';
	const title = document.createElement('h3');
	title.className = 'child-section__title';
	title.textContent = t(labels.sectionKey);
	const count = document.createElement('span');
	count.className = 'child-section__count';
	count.textContent = String(objects.length);
	head.append(title, count);
	section.appendChild(head);

	const list = document.createElement('div');
	list.className = 'child-list';
	section.appendChild(list);

	const empty = document.createElement('p');
	empty.className = 'child-section__empty';
	empty.textContent = labels.emptyKey ? t(labels.emptyKey) : '';
	empty.hidden = objects.length > 0;
	section.appendChild(empty);

	host.appendChild(section);

	const rows = objects.map(object => createRow(object));
	rows.forEach(row => list.appendChild(row.root));

	let openRow = null;
	let revealFrame = 0;

	function closeRow(row) {
		if (!row || row.root.dataset.open !== 'true') {
			return;
		}
		stopReveal();
		row.root.dataset.open = 'false';
		row.head.setAttribute('aria-expanded', 'false');
		row.body.dataset.open = 'false';
		row.body.inert = true;
		if (settings.onFocus) {
			settings.onFocus(null);
		}
		announceLayout();
	}

	function openNewRow(row) {
		closeRow(openRow);
		openRow = row;
		row.prepare();
		row.root.dataset.open = 'true';
		row.head.setAttribute('aria-expanded', 'true');
		row.body.dataset.open = 'true';
		row.body.inert = false;
		if (settings.onFocus) {
			settings.onFocus(row.object.id);
		}
		reveal();
		announceLayout();
	}

	/**
	 * The colour picker already makes a station card tall, so an expanded row's buttons can sit below the body's
	 * fold. Scrolling the section to the top, however, used to happen in one frame once the row had finished
	 * opening, which snapped the scrollbar. This animates the scroll alongside the 260ms height change instead,
	 * re-reading the maximum scroll each frame because the card is still growing.
	 */
	function reveal() {
		stopReveal();
		const scroller = scrollParent(section);
		if (!scroller) {
			return;
		}

		const start = scroller.scrollTop;
		const scrollerRect = scroller.getBoundingClientRect();
		const sectionRect = section.getBoundingClientRect();
		const target = start + (sectionRect.top - scrollerRect.top) - 12;
		if (target <= start + 1) {
			return;
		}

		const reduced = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
			&& window.matchMedia('(prefers-reduced-motion: reduce)').matches;
		if (reduced || typeof requestAnimationFrame !== 'function') {
			scroller.scrollTop = target;
			return;
		}

		const startedAt = performance.now();
		const step = now => {
			const progress = Math.min(1, (now - startedAt) / 300);
			const eased = 1 - Math.pow(1 - progress, 3);
			const maximum = scroller.scrollHeight - scroller.clientHeight;
			scroller.scrollTop = start + (Math.min(target, maximum) - start) * eased;
			revealFrame = progress < 1 ? requestAnimationFrame(step) : 0;
		};
		revealFrame = requestAnimationFrame(step);
	}

	function stopReveal() {
		if (revealFrame && typeof cancelAnimationFrame === 'function') {
			cancelAnimationFrame(revealFrame);
		}
		revealFrame = 0;
	}

	function scrollParent(element) {
		let node = element.parentNode;
		while (node && node.nodeType === 1) {
			const style = typeof window !== 'undefined' && typeof window.getComputedStyle === 'function'
				? window.getComputedStyle(node)
				: null;
			if (style && (style.overflowY === 'auto' || style.overflowY === 'scroll')) {
				return node;
			}
			node = node.parentNode;
		}
		return null;
	}

	function announceLayout() {
		if (settings.onLayoutChange) {
			settings.onLayoutChange();
		}
	}

	function createRow(object) {
		let context = contextFor(object, meta);
		let form = null;
		let view = null;
		let saving = false;
		let statusLevel = 'info';

		const root = document.createElement('article');
		root.className = 'child';
		root.dataset.id = object.id;
		root.dataset.open = 'false';

		const rowHead = document.createElement('button');
		rowHead.type = 'button';
		rowHead.className = 'child__head';
		rowHead.setAttribute('aria-expanded', 'false');

		const rowTitle = document.createElement('span');
		rowTitle.className = 'child__title';
		rowTitle.textContent = object.name || t('untitled');

		const summary = document.createElement('span');
		summary.className = 'child__summary';
		summary.textContent = summarizeSavedRail(childKind, object, context);

		const chevron = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
		chevron.setAttribute('viewBox', '0 0 24 24');
		chevron.setAttribute('width', '18');
		chevron.setAttribute('height', '18');
		chevron.setAttribute('aria-hidden', 'true');
		chevron.setAttribute('focusable', 'false');
		chevron.classList.add('child__chevron');
		const chevronPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
		chevronPath.setAttribute('d', 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z');
		chevron.appendChild(chevronPath);

		rowHead.append(rowTitle, summary, chevron);
		root.appendChild(rowHead);

		const body = document.createElement('div');
		body.className = 'child__body';
		body.dataset.open = 'false';
		body.inert = true;
		const bodyInner = document.createElement('div');
		bodyInner.className = 'child__body-inner';
		body.appendChild(bodyInner);
		root.appendChild(body);

		const fields = document.createElement('div');
		fields.className = 'child__fields';
		const status = document.createElement('p');
		status.className = 'child__status';
		status.hidden = true;
		const actions = document.createElement('div');
		actions.className = 'child__actions';

		const cancel = document.createElement('button');
		cancel.type = 'button';
		cancel.className = 'button button--text button--small';
		cancel.textContent = t('editCancel');
		cancel.addEventListener('click', () => closeRow(row));

		const save = document.createElement('button');
		save.type = 'button';
		save.className = 'button button--filled button--small';
		save.textContent = t('editSave');
		save.disabled = true;
		save.addEventListener('click', () => submit());

		actions.append(cancel, save);
		bodyInner.append(fields, status, actions);

		rowHead.addEventListener('click', () => {
			if (root.dataset.open === 'true') {
				closeRow(row);
			} else {
				openNewRow(row);
			}
		});

		const row = {
			root,
			head: rowHead,
			body,
			object,
			prepare() {
				context = contextFor(object, meta);
				rowTitle.textContent = object.name || t('untitled');
				summary.textContent = summarizeSavedRail(childKind, object, context);
				rebuild();
				setStatus('', null);
				refreshSaveState();
			},
			destroy() {
				if (view) {
					view.destroy();
					view = null;
				}
				form = null;
			}
		};

		function rebuild() {
			if (view) {
				view.destroy();
			}
			form = createForm(childKind, object, context);
			view = mountFields(fields, form, { onChange: onFormChange });
		}

		function onFormChange(layoutChanged) {
			if (statusLevel === 'success' || statusLevel === 'error') {
				setStatus('', null);
			}
			refreshSaveState();
			if (layoutChanged) {
				announceLayout();
			}
		}

		function refreshSaveState() {
			if (!form || saving || !view) {
				save.disabled = true;
				return;
			}
			const { fields: payload } = buildPayload(form);
			save.disabled = Object.keys(payload).length === 0;
		}

		function setStatus(text, level) {
			statusLevel = level || 'info';
			if (!text) {
				status.hidden = true;
				status.textContent = '';
				delete status.dataset.level;
				return;
			}
			status.textContent = text;
			status.dataset.level = statusLevel;
			status.hidden = false;
		}

		async function submit() {
			if (!form || !view || saving) {
				return;
			}

			const { fields: payload, errors } = buildPayload(form);
			view.clearErrors();
			if (errors.length > 0) {
				view.showErrors(errors);
				setStatus(t('editFixFields'), 'error');
				announceLayout();
				return;
			}
			if (Object.keys(payload).length === 0) {
				return;
			}

			saving = true;
			save.disabled = true;
			cancel.disabled = true;
			save.textContent = t('editSaving');
			setStatus(t('editSaving'), 'info');

			try {
				const result = await settings.onSubmit(childKind, object.id, payload);
				if (result && result.object) {
					Object.assign(object, result.object);
				}
				rebuild();
				rowTitle.textContent = object.name || t('untitled');
				summary.textContent = summarizeSavedRail(childKind, object, context);
				setStatus(t('editSaved'), 'success');
			} catch (error) {
				const failure = describeFailure(error);
				if (failure.field) {
					view.showErrors([{ key: failure.field, message: failure.message }]);
				}
				setStatus(failure.status, 'error');
				if (failure.log) {
					console.error('[MTR-WebDashboard] could not save ' + childKind + ' ' + object.id, error);
				}
			} finally {
				saving = false;
				cancel.disabled = false;
				save.textContent = t('editSave');
				refreshSaveState();
				announceLayout();
			}
		}

		return row;
	}

	return {
		destroy() {
			rows.forEach(row => row.destroy());
			host.textContent = '';
		}
	};
}
