/** The mode filter value meaning "do not filter". */
export const MODE_ALL = 'all';

const nameCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

function compareByNameThenColor(a, b) {
	const nameA = a.name || '';
	const nameB = b.name || '';

	const byName = nameCollator.compare(nameA, nameB);
	if (byName !== 0) {
		return byName;
	}
	if (nameA !== nameB) {
		return nameA < nameB ? -1 : 1;
	}

	return (a.color || 0) - (b.color || 0);
}

/**
 * @returns {number|null} the name as a number, or null when it is not purely numeric.
 */
function parseNumericName(name) {
	if (typeof name !== 'string') {
		return null;
	}
	const trimmed = name.trim();
	if (trimmed === '') {
		return null;
	}
	const value = Number(trimmed);
	return Number.isFinite(value) ? value : null;
}

/**
 * Replaces the game's `|` name separator with a space, mirroring `IGui.formatStationName`.
 */
export function formatName(name) {
	return typeof name === 'string' ? name.replace(/\|/g, ' ').trim() : '';
}

export function buildIndex(world) {
	const index = {
		stationById: new Map(),
		platformById: new Map(),
		routeById: new Map(),
		depotById: new Map(),
		sidingById: new Map(),
		modeCounts: { routes: new Map(), depots: new Map(), platforms: new Map(), sidings: new Map() },
		platformCountByStationId: new Map()
	};

	if (!world) {
		return index;
	}

	(world.stations || []).forEach(station => index.stationById.set(station.id, station));
	(world.platforms || []).forEach(platform => index.platformById.set(platform.id, platform));
	(world.routes || []).forEach(route => index.routeById.set(route.id, route));
	(world.depots || []).forEach(depot => index.depotById.set(depot.id, depot));
	(world.sidings || []).forEach(siding => index.sidingById.set(siding.id, siding));

	countByMode(index.modeCounts.routes, world.routes || []);
	countByMode(index.modeCounts.depots, world.depots || []);
	countByMode(index.modeCounts.platforms, world.platforms || []);
	countByMode(index.modeCounts.sidings, world.sidings || []);

	(world.platforms || []).forEach(platform => {
		if (platform.stationId) {
			index.platformCountByStationId.set(platform.stationId, (index.platformCountByStationId.get(platform.stationId) || 0) + 1);
		}
	});

	return index;
}

function countByMode(target, objects) {
	objects.forEach(object => target.set(object.transportMode, (target.get(object.transportMode) || 0) + 1));
}

function buildSidingToDepotId(world) {
	const map = new Map();
	const relations = (world && world.depotIdToSidingIds) || {};
	Object.keys(relations).forEach(depotId => {
		(relations[depotId] || []).forEach(sidingId => {
			if (!map.has(sidingId)) {
				map.set(sidingId, depotId);
			}
		});
	});
	return map;
}

/**
 * @param {object} world   a world object from `/api/data`, or null.
 * @param {object} index   the result of {@link buildIndex}.
 * @param {string} tab     'stations' | 'routes' | 'depots'.
 * @param {string} mode    a transport mode id, or {@link MODE_ALL}.
 * @param {string} search  the raw search text.
 * @returns {Array<{id: string, name: string, color: number, mode: string|null, summary: Array, raw: object}>}
 */
export function buildRows(world, index, tab, mode, search) {
	if (!world) {
		return [];
	}

	let rows;
	if (tab === 'routes') {
		rows = buildRouteRows(world, index, mode);
	} else if (tab === 'depots') {
		rows = buildDepotRows(world, index, mode);
	} else {
		rows = buildStationRows(world, index);
	}

	rows.sort(compareByNameThenColor);

	const query = (search || '').trim().toLowerCase();
	if (query === '') {
		return rows;
	}
	return rows.filter(row => (row.raw.name || '').toLowerCase().includes(query));
}

function buildStationRows(world, index) {
	return (world.stations || []).map(station => ({
		id: station.id,
		name: formatName(station.name),
		color: station.color,
		mode: null,
		summary: [
			{ key: 'summaryPlatforms', value: index.platformCountByStationId.get(station.id) || 0 },
			{ key: 'summaryZone', value: station.zone || 0 }
		],
		raw: station
	}));
}

function buildRouteRows(world, index, mode) {
	return (world.routes || [])
		.filter(route => mode === MODE_ALL || route.transportMode === mode)
		.map(route => {
			const summary = [
				{ key: 'summaryStops', value: (route.platformIds || []).length }
			];
			const depot = route.depotId ? index.depotById.get(route.depotId) : null;
			if (depot) {
				summary.push({ literal: formatName(depot.name) || null });
			}
			return {
				id: route.id,
				name: formatName(route.name),
				color: route.color,
				mode: route.transportMode,
				routeType: route.routeType,
				isLightRailRoute: route.isLightRailRoute,
				lightRailRouteNumber: route.lightRailRouteNumber,
				summary,
				raw: route
			};
		});
}

function buildDepotRows(world, index, mode) {
	const sidingToDepotId = buildSidingToDepotId(world);
	const sidingCountByDepotId = new Map();
	sidingToDepotId.forEach(depotId => {
		sidingCountByDepotId.set(depotId, (sidingCountByDepotId.get(depotId) || 0) + 1);
	});

	return (world.depots || [])
		.filter(depot => mode === MODE_ALL || depot.transportMode === mode)
		.map(depot => ({
			id: depot.id,
			name: formatName(depot.name),
			color: depot.color,
			mode: depot.transportMode,
			summary: [
				{ key: 'summarySidings', value: sidingCountByDepotId.get(depot.id) || 0 },
				{ key: 'summaryRoutes', value: (depot.routeIds || []).length }
			],
			raw: depot
		}));
}

/**
 * @returns {number|null}
 */
export function countForMode(index, tab, mode) {
	if (tab === 'stations') {
		return null;
	}
	const counts = tab === 'depots' ? index.modeCounts.depots : index.modeCounts.routes;
	if (mode === MODE_ALL) {
		let total = 0;
		counts.forEach(count => {
			total += count;
		});
		return total;
	}
	return counts.get(mode) || 0;
}

/** The transport modes present in this world, in the order the game's enum declares them. */
export const MODES = ['TRAIN', 'BOAT', 'CABLE_CAR', 'AIRPLANE'];
