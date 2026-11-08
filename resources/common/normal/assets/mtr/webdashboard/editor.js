import { f, t } from './i18n.js?v=43';
import { hasOriginCorner, isValidCorner } from './areafit.js?v=43';

/** The longest name a packet can carry, matching the server's own limit. */
export const MAX_NAME_LENGTH = 32767;

/** The largest colour, being 24 bits of RGB with no alpha channel. */
export const MAX_COLOR = 0xFFFFFF;

/** The in-game saved rail editor caps a platform or siding number at ten characters. */
export const SAVED_RAIL_NAME_MAX_LENGTH = 10;

/** Half-second steps, because the model stores dwell and adc times as whole half-seconds. */
export const SAVED_RAIL_TIME_STEP = 0.5;

/** `RailType` only needs the entries whose speed the game's manual-speed slider can pick. */
const MANUAL_SPEED_LIMITS = [20, 40, 60, 80, 120, 160, 200, 300];

const PSD_DISPLAY_MODES = [0, 1, 2];

/**
 * Used only when `/api/meta` could not be read. The cable car is the one mode with continuous movement, and
 * it is also the only mode whose platform/siding settings have to be hidden, so a stale fallback that keeps
 * editing available is worse than a hard-coded mode id here.
 */
const CONTINUOUS_FALLBACK_MODE = 'CABLE_CAR';

export const CHILD_KINDS = { station: 'platform', depot: 'siding' };

export const CHILD_LABELS = {
	platform: { sectionKey: 'sectionPlatforms', emptyKey: 'editNoPlatforms' },
	siding: { sectionKey: 'sectionSidings', emptyKey: 'editNoSidings' }
};

export const DESCRIPTORS = {
	station: {
		kind: 'station',
		titleKey: 'editStationTitle',
		saveKey: 'editSaveStation',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' },
			{ key: 'zone', type: 'integer', labelKey: 'fieldZone' },
			{ key: 'corners', type: 'corners', labelKey: 'fieldCorners' }
		]
	},
	route: {
		kind: 'route',
		titleKey: 'editRouteTitle',
		saveKey: 'editSave',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' }
		]
	},
	depot: {
		kind: 'depot',
		titleKey: 'editDepotTitle',
		saveKey: 'editSaveDepot',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldName', maxLength: MAX_NAME_LENGTH },
			{ key: 'color', type: 'color', labelKey: 'fieldColor' },
			{ key: 'corners', type: 'corners', labelKey: 'fieldCorners' }
		]
	},
	platform: {
		kind: 'platform',
		titleKey: 'editPlatformTitle',
		saveKey: 'editSave',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldPlatformNumber', maxLength: SAVED_RAIL_NAME_MAX_LENGTH },
			{
				key: 'dwellTimeSeconds', type: 'decimal', labelKey: 'fieldDwellTime',
				min: 0.5, max: 600, multipleOf: SAVED_RAIL_TIME_STEP, hideWhenContinuous: true
			},
			{
				key: 'adcTimeSeconds', type: 'decimal', labelKey: 'fieldAdcTime',
				min: 0, max: 600, multipleOf: SAVED_RAIL_TIME_STEP, hideWhenContinuous: true
			},
			{
				key: 'psdDisplayMode', type: 'select', labelKey: 'fieldPsdDisplayMode', numeric: true,
				options: PSD_DISPLAY_MODES.map(value => ({ value, labelKey: 'psdMode' + value })),
				hideWhenContinuous: true
			}
		]
	},
	siding: {
		kind: 'siding',
		titleKey: 'editSidingTitle',
		saveKey: 'editSave',
		fields: [
			{ key: 'name', type: 'text', labelKey: 'fieldSidingNumber', maxLength: SAVED_RAIL_NAME_MAX_LENGTH },
			{ key: 'unlimitedTrains', type: 'boolean', labelKey: 'fieldUnlimitedVehicles', hideWhenContinuous: true },
			{
				key: 'maxTrains', type: 'integer', labelKey: 'fieldMaxVehicles',
				min: 1, max: 999, hideWhenContinuous: true,
				/*
				 * The game's "Maximum Vehicles for Siding" field shows the stored value plus one: simulateTrain()
				 * lets trains spawn while trains.size() <= maxTrains, so a stored 2 really means three vehicles.
				 * The conversion lives here and nowhere else.
				 */
				read: object => String((Number(object.maxTrains) || 0) + 1),
				toPayload: value => value - 1,
				visible: values => values.unlimitedTrains !== true && values.isManual !== true
			},
			{
				key: 'isManual', type: 'boolean', labelKey: 'fieldDrivable', hideWhenContinuous: true,
				visible: values => values.unlimitedTrains !== true
			},
			{
				key: 'maxManualSpeed', type: 'select', labelKey: 'fieldMaxDriveSpeed', numeric: true,
				hideWhenContinuous: true,
				options: MANUAL_SPEED_LIMITS.map((limit, value) => ({ value, label: limit + ' km/h' })),
				fallbackLabelKey: 'speedUnlimited',
				visible: values => values.isManual === true
			},
			{ key: 'enablePredictiveBraking', type: 'boolean', labelKey: 'fieldPredictiveBraking', hideWhenContinuous: true },
			{ key: 'enableDelayedAcceleration', type: 'boolean', labelKey: 'fieldDelayedAcceleration', hideWhenContinuous: true },
			{
				key: 'accelerationMetersPerSecondSquared', type: 'decimal', labelKey: 'fieldAcceleration',
				min: 0.04, max: 20, hideWhenContinuous: true,
				read: (object, form) => formatDecimal((Number(object.accelerationConstant) || 0) * form.accelerationUnit, 3)
			},
			{
				// The braking rate. Older payloads only ever carried the acceleration above, and the server
				// treats a missing deceleration as "same as acceleration", so a page without this field still
				// edits a siding the way it always did.
				key: 'decelerationMetersPerSecondSquared', type: 'decimal', labelKey: 'fieldDeceleration',
				min: 0.04, max: 20, hideWhenContinuous: true,
				read: (object, form) => formatDecimal((Number(object.decelerationConstant) || 0) * form.accelerationUnit, 3)
			},
			{
				key: 'dwellTimeSeconds', type: 'decimal', labelKey: 'fieldDrivableTimeout',
				min: 0.5, max: 600, multipleOf: SAVED_RAIL_TIME_STEP, hideWhenContinuous: true,
				visible: values => values.isManual === true
			}
		]
	}
};

/** @returns {object|null} the descriptor for a kind, or null when it is not editable. */
export function getDescriptor(kind) {
	return Object.prototype.hasOwnProperty.call(DESCRIPTORS, kind) ? DESCRIPTORS[kind] : null;
}

/**
 * @param {object|null} object   the edited object, for its transport mode.
 * @param {object|null} meta     the `/api/meta` payload.
 * @returns {{continuousMovement: boolean, accelerationUnit: number}}
 */
export function contextFor(object, meta) {
	const modes = meta && Array.isArray(meta.transportModes) ? meta.transportModes : [];
	const mode = object ? modes.find(candidate => candidate.id === object.transportMode) : null;
	const unit = Number(meta && meta.limits ? meta.limits.accelerationUnitConversion : NaN);
	return normalizeContext({
		continuousMovement: mode ? Boolean(mode.continuousMovement) : Boolean(object && object.transportMode === CONTINUOUS_FALLBACK_MODE),
		accelerationUnit: unit
	});
}

function normalizeContext(context) {
	const source = context || {};
	const unit = Number(source.accelerationUnit);
	return {
		continuousMovement: Boolean(source.continuousMovement),
		accelerationUnit: Number.isFinite(unit) && unit > 0 ? unit : 400
	};
}

export function isFieldVisible(field, form) {
	if (field.hideWhenContinuous && form.continuousMovement) {
		return false;
	}
	return typeof field.visible !== 'function' || field.visible(form.values, form);
}

export function readFieldValue(field, object, form) {
	if (!object) {
		return field.type === 'corners' ? null : field.type === 'boolean' ? false : '';
	}
	if (typeof field.read === 'function') {
		return field.read(object, form);
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
	if (field.type === 'boolean') {
		return Boolean(raw);
	}
	return raw == null ? '' : String(raw);
}

export function createForm(kind, object, context) {
	const descriptor = getDescriptor(kind);
	const normalized = normalizeContext(context);
	if (!descriptor || !object) {
		return {
			kind,
			id: object && object.id != null ? String(object.id) : '',
			fields: [],
			values: {},
			initial: {},
			...normalized
		};
	}

	const form = {
		kind,
		id: String(object.id),
		...normalized,
		fields: descriptor.fields.map(field => ({ ...field, label: t(field.labelKey) })),
		values: {},
		initial: {}
	};

	form.fields.forEach(field => {
		form.values[field.key] = readFieldValue(field, object, form);
	});
	form.fields.forEach(field => {
		if (field.type === 'select') {
			const resolved = resolveSelect(field, form.values[field.key]);
			field.options = resolved.options;
			form.values[field.key] = resolved.value;
		}
	});

	return markClean(form);
}

/** @returns {string[]} the keys whose current value differs from the baseline, in field order. */
export function dirtyKeys(form) {
	return form.fields
		.filter(field => isFieldVisible(field, form) && !sameValue(form.values[field.key], form.initial[field.key]))
		.map(field => field.key);
}

function isDirty(field, form) {
	return !sameValue(form.values[field.key], form.initial[field.key]);
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

	form.fields.forEach(field => {
		if (!isFieldVisible(field, form) || !isDirty(field, form)) {
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
				const rangeError = checkRange(field, parsed);
				if (rangeError) {
					errors.push({ key: field.key, message: rangeError });
					return;
				}
				fields[field.key] = applyToPayload(field, parsed);
				return;
			}
			case 'decimal': {
				const parsed = parseDecimal(value);
				if (parsed == null) {
					errors.push({ key: field.key, message: t('errorNumber') });
					return;
				}
				const rangeError = checkRange(field, parsed);
				if (rangeError) {
					errors.push({ key: field.key, message: rangeError });
					return;
				}
				if (field.multipleOf && !isMultipleOf(parsed, field.multipleOf)) {
					errors.push({ key: field.key, message: f('errorMultiple', field.multipleOf) });
					return;
				}
				fields[field.key] = applyToPayload(field, parsed);
				return;
			}
			case 'boolean': {
				fields[field.key] = value === true;
				return;
			}
			case 'select': {
				if (!Array.isArray(field.options) || !field.options.some(option => String(option.value) === String(value))) {
					errors.push({ key: field.key, message: t('errorUnsupportedField') });
					return;
				}
				fields[field.key] = field.numeric ? Number(value) : value;
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

	normalizeSidingPayload(form.kind, fields);
	return { fields, errors };
}

function applyToPayload(field, value) {
	return typeof field.toPayload === 'function' ? field.toPayload(value) : value;
}

function checkRange(field, value) {
	if (field.min != null && value < field.min) {
		return t('errorTooSmall');
	}
	if (field.max != null && value > field.max) {
		return t('errorTooLarge');
	}
	return null;
}

function isMultipleOf(value, step) {
	const steps = value / step;
	return Math.abs(steps - Math.round(steps)) < 1e-6;
}

/**
 * The game lets a siding be either unlimited, drivable (which implies exactly one vehicle) or a fixed fleet.
 * The web controls hide the irrelevant field, so the stored values have to be written coherently too.
 */
function normalizeSidingPayload(kind, fields) {
	if (kind !== 'siding') {
		return;
	}
	if (fields.unlimitedTrains === true) {
		fields.isManual = false;
		fields.maxTrains = 0;
	} else if (fields.isManual === true) {
		fields.unlimitedTrains = false;
		fields.maxTrains = 0;
	}
}

function resolveSelect(field, rawValue) {
	const value = rawValue == null ? '' : String(rawValue);
	const options = field.options.map(option => ({
		value: option.value,
		label: option.labelKey ? t(option.labelKey) : String(option.label)
	}));

	if (options.some(option => String(option.value) === value)) {
		return { value, options };
	}
	if (field.fallbackLabelKey) {
		options.unshift({ value, label: t(field.fallbackLabelKey) });
		return { value, options };
	}
	return { value: String(options[0].value), options };
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

/** @returns {number|null} the decimal a string represents, or null when it is not one. */
export function parseDecimal(value) {
	if (typeof value === 'number') {
		return Number.isFinite(value) ? value : null;
	}
	if (typeof value !== 'string') {
		return null;
	}
	const trimmed = value.trim();
	if (!/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(trimmed)) {
		return null;
	}
	const parsed = Number(trimmed);
	return Number.isFinite(parsed) ? parsed : null;
}

/** @returns {string} a number with at most `places` decimals and no trailing zeros. */
export function formatDecimal(value, places) {
	if (!Number.isFinite(value)) {
		return '';
	}
	const fixed = value.toFixed(places);
	return fixed.includes('.') ? fixed.replace(/\.?0+$/, '') : fixed;
}

export function formatSeconds(value) {
	return formatDecimal(Number(value), 1);
}

/** @returns {string} the one-line summary shown on a platform or siding row. */
export function summarizeSavedRail(kind, object, context) {
	if (!object) {
		return '';
	}
	const normalized = normalizeContext(context);
	if (normalized.continuousMovement) {
		return '';
	}

	if (kind === 'platform') {
		const dwell = f('summaryDwell', formatSeconds(object.dwellTimeSeconds));
		return [dwell, t('psdMode' + normalizePsdMode(object.psdDisplayMode))].join(' · ');
	}
	if (kind === 'siding') {
		const parts = [object.unlimitedTrains === true ? t('summaryUnlimited') : f('summaryVehicles', (Number(object.maxTrains) || 0) + 1)];
		if (object.isManual === true) {
			parts.push(t('summaryManual'));
		}
		const acceleration = Number(object.accelerationConstant);
		if (Number.isFinite(acceleration)) {
			parts.push(f('summaryAcceleration', formatDecimal(acceleration * normalized.accelerationUnit, 3)));
		}
		const deceleration = Number(object.decelerationConstant);
		if (Number.isFinite(deceleration) && deceleration !== acceleration) {
			parts.push(f('summaryDeceleration', formatDecimal(deceleration * normalized.accelerationUnit, 3)));
		}
		return parts.join(' · ');
	}
	return '';
}

function normalizePsdMode(value) {
	const mode = Number(value);
	return mode === 1 || mode === 2 ? mode : 0;
}

export function isStorableSelection(value) {
	return Boolean(value)
		&& isValidCorner(value.corner1)
		&& isValidCorner(value.corner2)
		&& !hasOriginCorner(value.corner1, value.corner2);
}
