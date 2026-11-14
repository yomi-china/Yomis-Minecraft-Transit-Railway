let nextSelectId = 0;

const MENU_GAP = 6;
const VIEWPORT_MARGIN = 8;

/**
 * A select-only combobox for the dashboard. The native <select> popup is rendered by the operating system and
 * cannot follow the glass/MD3 language, so the trigger, the listbox and the keyboard handling are all ours.
 *
 * The listbox is portalled to <body>: the edit card's body is a scroll container, and anything inside it would
 * be clipped at the card's edge. The trigger keeps focus (aria-activedescendant), which is the pattern
 * screen readers expect for a select-only combobox.
 *
 * @param {{options: Array<{value: string|number, label: string}>, value: string|number, id?: string,
 *          onChange?: (value: string) => void}} options
 */
export function createSelect(options) {
	const settings = options || {};
	const items = (settings.options || []).map(option => ({ value: String(option.value), label: option.label }));
	const onChange = settings.onChange;
	const id = settings.id || 'edit-select-' + (++nextSelectId);

	let value = settings.value == null ? '' : String(settings.value);
	let open = false;
	let activeIndex = Math.max(0, items.findIndex(item => item.value === value));

	const root = document.createElement('div');
	root.className = 'select';

	const trigger = document.createElement('button');
	trigger.type = 'button';
	trigger.className = 'select__trigger';
	trigger.id = id;
	trigger.setAttribute('role', 'combobox');
	trigger.setAttribute('aria-haspopup', 'listbox');
	trigger.setAttribute('aria-expanded', 'false');
	trigger.setAttribute('aria-controls', id + '-menu');

	const label = document.createElement('span');
	label.className = 'select__label';

	const chevron = icon('M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z');
	chevron.classList.add('select__chevron');

	trigger.append(label, chevron);
	root.appendChild(trigger);

	const menu = document.createElement('ul');
	menu.className = 'select__menu';
	menu.id = id + '-menu';
	menu.setAttribute('role', 'listbox');
	menu.hidden = true;
	document.body.appendChild(menu);

	trigger.addEventListener('click', event => {
		event.preventDefault();
		if (open) {
			close();
		} else {
			openMenu();
		}
	});
	trigger.addEventListener('keydown', onKeyDown);

	refreshLabel();

	function refreshLabel() {
		const current = items.find(item => item.value === value);
		label.textContent = current ? current.label : '';
	}

	function buildOptions() {
		menu.replaceChildren(...items.map((item, index) => {
			const option = document.createElement('li');
			option.className = 'select__option';
			option.id = id + '-option-' + index;
			option.dataset.value = item.value;
			option.setAttribute('role', 'option');
			option.setAttribute('aria-selected', String(item.value === value));

			const text = document.createElement('span');
			text.className = 'select__option-label';
			text.textContent = item.label;

			const check = icon('M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z');
			check.classList.add('select__check');

			option.append(text, check);
			option.addEventListener('click', event => {
				event.preventDefault();
				choose(index);
			});
			option.addEventListener('pointermove', () => setActive(index, false));
			return option;
		}));
	}

	function setActive(index, scroll) {
		if (items.length === 0) {
			return;
		}
		activeIndex = (index + items.length) % items.length;
		Array.from(menu.children).forEach((option, position) => {
			option.dataset.active = String(position === activeIndex);
		});
		trigger.setAttribute('aria-activedescendant', id + '-option-' + activeIndex);
		if (scroll) {
			const option = menu.children[activeIndex];
			if (option) {
				const top = option.offsetTop;
				const bottom = top + option.offsetHeight;
				if (top < menu.scrollTop) {
					menu.scrollTop = top;
				} else if (bottom > menu.scrollTop + menu.clientHeight) {
					menu.scrollTop = bottom - menu.clientHeight;
				}
			}
		}
	}

	function openMenu() {
		if (open) {
			return;
		}
		open = true;
		activeIndex = Math.max(0, items.findIndex(item => item.value === value));
		buildOptions();
		menu.hidden = false;
		trigger.setAttribute('aria-expanded', 'true');
		root.classList.add('is-open');
		position();
		setActive(activeIndex, true);
		addDocumentListener('pointerdown', onOutsidePointerDown, true);
		addDocumentListener('scroll', onDocumentScroll, true);
		addWindowListener('resize', close);
		addWindowListener('blur', close);
	}

	function close() {
		if (!open) {
			return;
		}
		open = false;
		menu.hidden = true;
		trigger.setAttribute('aria-expanded', 'false');
		trigger.removeAttribute('aria-activedescendant');
		root.classList.remove('is-open');
		removeDocumentListener('pointerdown', onOutsidePointerDown, true);
		removeDocumentListener('scroll', onDocumentScroll, true);
		removeWindowListener('resize', close);
		removeWindowListener('blur', close);
	}

	function choose(index) {
		const item = items[index];
		if (!item) {
			return;
		}
		const changed = item.value !== value;
		value = item.value;
		refreshLabel();
		close();
		if (typeof trigger.focus === 'function') {
			trigger.focus();
		}
		if (changed && onChange) {
			onChange(value);
		}
	}

	function onOutsidePointerDown(event) {
		const target = event.target;
		if (!target) {
			return;
		}
		const insideTrigger = typeof root.contains === 'function' && root.contains(target);
		const insideMenu = typeof menu.contains === 'function' && menu.contains(target);
		if (!insideTrigger && !insideMenu) {
			close();
		}
	}

	function onDocumentScroll(event) {
		// Scrolling inside the listbox is ours; any other scroll moves the trigger away from the menu.
		const target = event.target;
		if (target && typeof menu.contains === 'function' && menu.contains(target)) {
			return;
		}
		close();
	}

	function onKeyDown(event) {
		switch (event.key) {
			case 'ArrowDown':
				event.preventDefault();
				if (!open) {
					openMenu();
				} else {
					setActive(activeIndex + 1, true);
				}
				return;
			case 'ArrowUp':
				event.preventDefault();
				if (!open) {
					openMenu();
				} else {
					setActive(activeIndex - 1, true);
				}
				return;
			case 'Home':
				if (open) {
					event.preventDefault();
					setActive(0, true);
				}
				return;
			case 'End':
				if (open) {
					event.preventDefault();
					setActive(items.length - 1, true);
				}
				return;
			case 'Enter':
			case ' ':
				event.preventDefault();
				if (open) {
					choose(activeIndex);
				} else {
					openMenu();
				}
				return;
			case 'Escape':
				if (open) {
					event.preventDefault();
					close();
				}
				return;
			case 'Tab':
				close();
				return;
			default:
		}
	}

	function position() {
		if (typeof trigger.getBoundingClientRect !== 'function') {
			return;
		}
		const rect = trigger.getBoundingClientRect();
		const menuHeight = typeof menu.getBoundingClientRect === 'function' ? menu.getBoundingClientRect().height : 0;
		const hasWindow = typeof window !== 'undefined';
		const viewportWidth = hasWindow && Number.isFinite(window.innerWidth) ? window.innerWidth : 0;
		const viewportHeight = hasWindow && Number.isFinite(window.innerHeight) ? window.innerHeight : 0;

		const width = Math.max(160, rect.width);
		let left = rect.left;
		if (viewportWidth > 0) {
			left = Math.min(Math.max(VIEWPORT_MARGIN, left), Math.max(VIEWPORT_MARGIN, viewportWidth - width - VIEWPORT_MARGIN));
		}

		let top = rect.bottom + MENU_GAP;
		if (viewportHeight > 0 && top + menuHeight > viewportHeight - VIEWPORT_MARGIN) {
			const above = rect.top - menuHeight - MENU_GAP;
			top = above >= VIEWPORT_MARGIN ? above : Math.max(VIEWPORT_MARGIN, viewportHeight - menuHeight - VIEWPORT_MARGIN);
		}

		menu.style.left = left + 'px';
		menu.style.top = top + 'px';
		menu.style.width = width + 'px';
	}

	return {
		root,
		trigger,
		get value() {
			return value;
		},
		close,
		destroy() {
			close();
			trigger.removeEventListener('keydown', onKeyDown);
			const parent = menu.parentNode || menu.parent;
			if (parent && typeof parent.removeChild === 'function') {
				parent.removeChild(menu);
			}
		}
	};
}

function addWindowListener(type, handler) {
	if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
		window.addEventListener(type, handler);
	}
}

function removeWindowListener(type, handler) {
	if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
		window.removeEventListener(type, handler);
	}
}

function addDocumentListener(type, handler, capture) {
	if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
		document.addEventListener(type, handler, capture);
	}
}

function removeDocumentListener(type, handler, capture) {
	if (typeof document !== 'undefined' && typeof document.removeEventListener === 'function') {
		document.removeEventListener(type, handler, capture);
	}
}

function icon(pathData) {
	const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	svg.setAttribute('viewBox', '0 0 24 24');
	svg.setAttribute('width', '18');
	svg.setAttribute('height', '18');
	svg.setAttribute('aria-hidden', 'true');
	svg.setAttribute('focusable', 'false');
	const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
	path.setAttribute('d', pathData);
	svg.appendChild(path);
	return svg;
}
