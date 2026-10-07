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

import { t } from './i18n.js?v=20';
import { hasOriginCorner, isValidCorner } from './areafit.js?v=20';

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
			{ key: 'zone', type: 'integer', labelKey: 'fieldZone' },
			// Not rendered as an input. A selection is drawn on the map, not typed, so this field exists only
			// so the patch payload and the dirty check treat it like any other value. See readFieldValue.
			{ key: 'corners', type: 'corners', labelKey: 'fieldCorners' }
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
			{ key: 'color', type: 'color', labelKey: 'fieldColor' },
			{ key: 'corners', type: 'corners', labelKey: 'fieldCorners' }
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
		return field.type === 'corners' ? null : '';
	}
	const raw = object[field.key];
	if (field.type === 'color') {
		return typeof raw === 'number' && Number.isFinite(raw) ? raw & MAX_COLOR : 0;
	}
	if (field.type === 'corners') {
		// Held as an object, and null when the object has no selection - which is a legal state, not a zero.
		return raw && raw.corner1 && raw.corner2
			? { corner1: { x: raw.corner1.x, z: raw.corner1.z }, corner2: { x: raw.corner2.x, z: raw.corner2.z } }
			: null;
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

/**
 * @returns {string[]} the keys whose current value differs from the baseline, in field order.
 *
 * A selection is an object, so it is compared by value rather than by identity - `!==` on two corners
 * objects is always true and would report every open form as dirty.
 */
export function dirtyKeys(form) {
	if (!form || !form.initial) {
		return [];
	}
	return form.fields.filter(field => !sameValue(form.values[field.key], form.initial[field.key])).map(field => field.key);
}

function sameValue(a, b) {
	if (a === b) {
		return true;
	}
	if (a == null || b == null) {
		return false;
	}
	if (typeof a === 'number' || typeof b === 'number') {
		return a === b;
	}
	if (typeof a === 'object' && typeof b === 'object') {
		return JSON.stringify(a) === JSON.stringify(b);
	}
	return false;
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
			case 'corners': {
				if (value == null) {
					// Clearing a selection is not offered: a station with no area is a state the game can hold,
					// but getting there by accident would orphan every platform it had. Left unset and unreported.
					return;
				}
				if (!isStorableSelection(value)) {
					errors.push({ key: field.key, message: t('errorSelectionNotStorable') });
					return;
				}
				fields[field.key] = { corner1: { x: value.corner1.x, z: value.corner1.z }, corner2: { x: value.corner2.x, z: value.corner2.z } };
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
 * Whether a selection can be stored at all.
 *
 * Two ways it cannot, and both are refusals rather than corrections: a corner that is not a whole block
 * (nothing the mod can act on), and a corner on the world origin. `AreaBase.setCorners` reads a corner of
 * (0, 0) as "no selection set" and nulls it, so storing one would clear the selection while reporting
 * success. The page keeps the visitor away from both while they drag, so this is a backstop.
 */
export function isStorableSelection(value) {
	return Boolean(value)
		&& isValidCorner(value.corner1)
		&& isValidCorner(value.corner2)
		&& !hasOriginCorner(value.corner1, value.corner2);
}
