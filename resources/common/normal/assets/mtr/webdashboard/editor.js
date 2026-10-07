import { t } from './i18n.js?v=20';
import { hasOriginCorner, isValidCorner } from './areafit.js?v=20';

/** The longest name a packet can carry, matching the server's own limit. */
export const MAX_NAME_LENGTH = 32767;

/** The largest colour, being 24 bits of RGB with no alpha channel. */
export const MAX_COLOR = 0xFFFFFF;

export const DESCRIPTORS = {
	station: {
		kind: 'station',
		patchPath: 'station',
		titleKey: 'editStationTitle',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' },
			{ key: 'zone', type: 'integer', labelKey: 'fieldZone' },
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

export function readFieldValue(field, object) {
	if (!object) {
		return field.type === 'corners' ? null : '';
	}
	const raw = object[field.key];
	if (field.type === 'color') {
		return typeof raw === 'number' && Number.isFinite(raw) ? raw & MAX_COLOR : 0;
	}
	if (field.type === 'corners') {
		return raw && raw.corner1 && raw.corner2
			? { corner1: { x: raw.corner1.x, z: raw.corner1.z }, corner2: { x: raw.corner2.x, z: raw.corner2.z } }
			: null;
	}
	return raw == null ? '' : String(raw);
}

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

export function markClean(form) {
	form.initial = { ...form.values };
	return form;
}

/**
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
				errors.push({ key: field.key, message: t('errorUnsupportedField') });
		}
	});

	return { fields, errors };
}

/**
 * @returns {number|null} the integer a string represents, or null when it is not one.
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

export function isStorableSelection(value) {
	return Boolean(value)
		&& isValidCorner(value.corner1)
		&& isValidCorner(value.corner2)
		&& !hasOriginCorner(value.corner1, value.corner2);
}
