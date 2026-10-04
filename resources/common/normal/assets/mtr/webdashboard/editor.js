/*
 * What can be edited, and how. Pure logic: no DOM, no fetch, no state.
 *
 * Stage 3.4.1 covers a name and a colour, on stations, routes and depots. Later sub-stages add groups of
 * fields by extending the descriptors here - the form renderer walks whatever `fields` says, so adding a
 * field never means touching the renderer or the save path.
 *
 * Why the descriptor is declarative rather than a hand-written form per resource: the three resources in
 * this stage differ only in which fields they have, and the ones arriving later (dwell times, frequencies,
 * stop lists) differ in the same way. A per-resource builder function would be three near-identical copies
 * of the same code, and the fourth would drift from the others.
 */

import { t } from './i18n.js?v=16';

/**
 * The colour palette.
 *
 * The same sixteen values the in-game colour selector offers, so a colour picked here is one a player
 * could have picked there - which matters because a station's colour is how it appears on both maps, and
 * an arbitrary hex would look out of place next to the built rail network.
 */
export const PALETTE = [
	0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF,
	0xFF5555, 0xFF8800, 0xFFFF55, 0x55FF55,
	0x00AA00, 0x55FFFF, 0x00AAAA, 0x5555FF,
	0xAA00AA, 0xFF55FF, 0xFFAAFF, 0x8B4513
];

/** The longest name a packet can carry, matching the server's own limit. */
export const MAX_NAME_LENGTH = 32767;

/** The largest colour, being 24 bits of RGB with no alpha channel. */
export const MAX_COLOR = 0xFFFFFF;

/**
 * Descriptors per resource.
 *
 * `patchPath` is the URL segment the server knows the resource by, kept next to the fields so the two
 * cannot disagree about what is being edited.
 */
export const DESCRIPTORS = {
	station: {
		kind: 'station',
		patchPath: 'station',
		titleKey: 'editStationTitle',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' },
			{ key: 'zone', type: 'integer', labelKey: 'fieldZone' }
		]
	},
	route: {
		kind: 'route',
		patchPath: 'route',
		titleKey: 'editRouteTitle',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' }
		]
	},
	depot: {
		kind: 'depot',
		patchPath: 'depot',
		titleKey: 'editDepotTitle',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' }
		]
	}
};

/** @returns {object|null} the descriptor for a kind, or null when it is not editable. */
export function getDescriptor(kind) {
	return Object.prototype.hasOwnProperty.call(DESCRIPTORS, kind) ? DESCRIPTORS[kind] : null;
}

/** @returns {string[]} the kinds this build can edit, for diagnostics and tests. */
export function editableKinds() {
	return Object.keys(DESCRIPTORS);
}

/**
 * A field's value in the form's own terms.
 *
 * The form works in strings for text and integers, and in an integer for the colour, because those are what
 * the controls produce. The conversion to what the server expects happens in {@link buildPayload}, so a
 * renderer never has to think about the wire format.
 */
export function readFieldValue(field, object) {
	if (!object) {
		return '';
	}
	const raw = object[field.key];
	if (field.type === 'color') {
		return typeof raw === 'number' && Number.isFinite(raw) ? raw & MAX_COLOR : 0;
	}
	return raw == null ? '' : String(raw);
}

/**
 * Seeds a form from an object.
 *
 * The starting values become the baseline immediately, so a freshly opened form is not dirty. After a save
 * the caller calls {@link markClean} again with the server's echoed values, which is what stops a clamped
 * value from leaving the form permanently dirty against the number the user typed.
 *
 * @returns {{kind: string, id: string, fields: object[], values: object, initial: object}} the session.
 */
export function createForm(kind, object) {
	const descriptor = getDescriptor(kind);
	if (!descriptor || !object) {
		return { kind, id: object && object.id != null ? String(object.id) : '', fields: [], values: {}, initial: {} };
	}

	const fields = descriptor.fields.map(field => ({ ...field, label: t(field.labelKey) }));
	const values = {};
	fields.forEach(field => {
		values[field.key] = readFieldValue(field, object);
	});

	return markClean({ kind, id: String(object.id), fields, values });
}

/** @returns {string[]} the keys whose current value differs from the baseline, in field order. */
export function dirtyKeys(form) {
	if (!form || !form.initial) {
		return [];
	}
	return form.fields.filter(field => form.values[field.key] !== form.initial[field.key]).map(field => field.key);
}

/**
 * Makes the current values the baseline, so the form stops being dirty.
 *
 * Called once when the form opens, and again after a save with the values the server echoed back - which is
 * what stops a clamped value from leaving the form permanently dirty against the number the user typed.
 */
export function markClean(form) {
	if (form) {
		form.initial = { ...form.values };
	}
	return form;
}

/**
 * Turns a form into a request body.
 *
 * Only dirty fields are sent. That is not an optimisation: the depot resource carries its whole state in
 * one packet, so sending a field that did not change is at best pointless and at worst a way to overwrite a
 * concurrent edit with a stale value.
 *
 * @returns {{fields: object, errors: Array<{key: string, message: string}>}} the body to PATCH, plus any
 *          field the client can already tell is unusable. A non-empty `errors` means do not send.
 */
export function buildPayload(form) {
	const fields = {};
	const errors = [];
	const dirty = dirtyKeys(form);

	form.fields.forEach(field => {
		if (!dirty.includes(field.key)) {
			return;
		}
		const value = form.values[field.key];

		switch (field.type) {
			case 'text': {
				const text = value == null ? '' : String(value);
				if (text.length > (field.maxLength || MAX_NAME_LENGTH)) {
					errors.push({ key: field.key, message: t('errorNameTooLong') });
					return;
				}
				// An empty name is allowed: the game permits an unnamed object, and the list already has a
				// fallback label for one.
				fields[field.key] = text;
				return;
			}
			case 'integer': {
				const parsed = parseInteger(value);
				if (parsed == null) {
					errors.push({ key: field.key, message: t('errorWholeNumber') });
					return;
				}
				if (field.min != null && parsed < field.min) {
					errors.push({ key: field.key, message: t('errorTooSmall') });
					return;
				}
				if (field.max != null && parsed > field.max) {
					errors.push({ key: field.key, message: t('errorTooLarge') });
					return;
				}
				fields[field.key] = parsed;
				return;
			}
			case 'color': {
				if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > MAX_COLOR) {
					errors.push({ key: field.key, message: t('errorColorRange') });
					return;
				}
				fields[field.key] = Math.round(value);
				return;
			}
			default:
				// A field type with no case here is a bug in the descriptor, not user input. Reported as an
				// error rather than skipped, because silently dropping it would make Save look like it worked.
				errors.push({ key: field.key, message: t('errorUnsupportedField') });
		}
	});

	return { fields, errors };
}

/**
 * @returns {number|null} the integer a string represents, or null when it is not one.
 *
 * Stricter than `parseInt`, which accepts "12abc" as 12 and "" as NaN. A zone typed as "12abc" should be
 * refused rather than quietly saved as 12.
 */
export function parseInteger(value) {
	if (typeof value === 'number') {
		return Number.isFinite(value) && Number.isInteger(value) ? value : null;
	}
	if (typeof value !== 'string') {
		return null;
	}
	const trimmed = value.trim();
	if (!/^[+-]?\d+$/.test(trimmed)) {
		return null;
	}
	const parsed = Number(trimmed);
	return Number.isSafeInteger(parsed) ? parsed : null;
}

/**
 * Parses a hex colour from a text input.
 *
 * @returns {number|null} the colour, or null for anything that is not 1 to 6 hex digits, optionally with a
 *          leading '#'.
 */
export function parseHexColor(value) {
	if (typeof value !== 'string') {
		return null;
	}
	const trimmed = value.trim().replace(/^#/, '');
	if (!/^[0-9a-fA-F]{1,6}$/.test(trimmed)) {
		return null;
	}
	return parseInt(trimmed, 16);
}

/** @returns {string} a colour as a six-digit lowercase hex string with no leading '#'. */
export function formatHexColor(color) {
	const bounded = Math.max(0, Math.min(MAX_COLOR, Number(color) || 0));
	return bounded.toString(16).padStart(6, '0');
}

/**
 * A summary of what the server did, for the card's status line.
 *
 * The server reports values it had to adjust in `warnings`, and the fields it wrote in `changed`. Both are
 * surfaced: "Saved" alone would hide that a colour was clamped, which is exactly the case where the page
 * and the server could otherwise disagree about what was stored.
 */
export function describeResult(result) {
	const changed = result && Array.isArray(result.changed) ? result.changed : [];
	const warnings = result && Array.isArray(result.warnings) ? result.warnings : [];
	if (warnings.length > 0) {
		return { key: 'editSavedWithWarnings', values: [warnings.join(' ')], level: 'warning' };
	}
	if (changed.length === 0) {
		return { key: 'editSavedNothing', values: [], level: 'warning' };
	}
	return { key: 'editSaved', values: [], level: 'success' };
}
