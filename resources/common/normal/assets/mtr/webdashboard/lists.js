/*
 * Turns the read-only API payload into the flat rows the sidebar renders.
 *
 * Pure functions only: no DOM, no state, no fetching. Keeping the derivation separate from the
 * rendering is what makes the filter rules testable by reading them, and they are the part most
 * likely to drift from the game.
 *
 * The filter rule is copied from the game rather than reinvented. In the mod,
 * `NameColorDataBase.isTransportMode(mode)` returns true when the object has no transport mode of
 * its own, and `Station.hasTransportMode()` returns false - so stations match every mode and are
 * never filtered, while routes and depots are shown only for their own mode. The game's dashboard
 * does exactly this:
 *
 *     case STATIONS: setData(ClientData.STATIONS, ...)                                   // unfiltered
 *     case ROUTES:   setData(getFilteredDataSet(transportMode, ClientData.ROUTES), ...)
 *     case DEPOTS:   setData(getFilteredDataSet(transportMode, ClientData.DEPOTS), ...)
 *
 * The web page adds an "all" option the game has no equivalent for, because one page manages all
 * four modes at once.
 */

/** The mode filter value meaning "do not filter". */
export const MODE_ALL = 'all';

export const TABS = ['stations', 'routes', 'depots'];

/**
 * Sorts like `NameColorDataBase.compareTo`: case-insensitive by name, then by colour. The collator
 * is created once because constructing one per comparison is measurably slow on a few hundred rows.
 *
 * `sensitivity: 'base'` makes accented and unaccented letters compare equal, which is what a
 * name sort wants. The catch is that it also declares unrelated scripts equal, so "中环" and "Ferry"
 * both come out as 0 and the order would otherwise fall back on insertion order. The explicit
 * code-point tiebreak below settles those deterministically.
 */
const nameCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

function compareByNameThenColor(a, b) {
	const nameA = a.name || '';
	const nameB = b.name || '';

	const byName = nameCollator.compare(nameA, nameB);
	if (byName !== 0) {
		return byName;
	}
	// Only reached when the collator considers the names equivalent, which for mixed scripts means
	// "different but equally weighted" rather than "the same". A code-point comparison keeps the order
	// stable and puts ASCII before CJK, which is the conventional arrangement.
	if (nameA !== nameB) {
		return nameA < nameB ? -1 : 1;
	}

	return (a.color || 0) - (b.color || 0);
}

/**
 * Sorts like `SavedRailBase.compareTo`: a purely numeric name sorts by value and before any
 * non-numeric name, so platforms read 1, 2, 10 rather than 1, 10, 2. Not used by this stage, which
 * shows no platform or siding list, but written now so stage 3.4 does not reintroduce the bug.
 */
export function compareSavedRails(a, b) {
	const aNumber = parseNumericName(a.name);
	const bNumber = parseNumericName(b.name);

	if (aNumber !== null && bNumber !== null) {
		const byValue = aNumber - bNumber;
		return byValue !== 0 ? byValue : compareByNameThenColor(a, b);
	}
	if (aNumber !== null) {
		return -1;
	}
	if (bNumber !== null) {
		return 1;
	}
	return compareByNameThenColor(a, b);
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
 *
 * MTR stores bilingual names as "中环|Central". The in-game lists flatten that to "中环 Central"; the
 * web page does the same so a name reads the same in both places. Editing will need the raw form
 * back, which is preserved untouched in the API payload.
 */
export function formatName(name) {
	return typeof name === 'string' ? name.replace(/\|/g, ' ').trim() : '';
}

/**
 * Builds the lookups the row builders need. Done once per payload rather than per row: without it
 * every row would scan the whole platform list, which is quadratic in a large world.
 */
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

	// Derived from the flat platform list rather than from each station's own platformIds, so the
	// count reflects where platforms actually are rather than what the station last cached.
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

/**
 * The depot-to-siding relation published once per world, inverted into siding-to-depot so a siding
 * row can name its depot without scanning every depot.
 */
function buildSidingToDepotId(world) {
	const map = new Map();
	const relations = (world && world.depotIdToSidingIds) || {};
	Object.keys(relations).forEach(depotId => {
		(relations[depotId] || []).forEach(sidingId => {
			// A siding should belong to one depot; if selections overlap, the first one wins, matching the
			// single-owner assumption the model itself makes.
			if (!map.has(sidingId)) {
				map.set(sidingId, depotId);
			}
		});
	});
	return map;
}

/**
 * Rows for the current tab and filter, sorted and ready to render.
 *
 * @param {object} world   a world object from `/api/data`, or null.
 * @param {object} index   the result of {@link buildIndex}.
 * @param {string} tab     one of {@link TABS}.
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
		// Stations are deliberately not mode-filtered: they are shared by all four modes in the model.
		rows = buildStationRows(world, index);
	}

	rows.sort(compareByNameThenColor);

	const query = (search || '').trim().toLowerCase();
	if (query === '') {
		return rows;
	}
	// Substring match on the raw name, case-insensitively - the same test the game's search box uses.
	return rows.filter(row => (row.raw.name || '').toLowerCase().includes(query));
}

function buildStationRows(world, index) {
	return (world.stations || []).map(station => ({
		id: station.id,
		name: formatName(station.name),
		color: station.color,
		mode: null,
		// Counts and the zone; rendered as separate chips and translated by the view.
		summary: [
			{ key: 'summaryPlatforms', value: index.platformCountByStationId.get(station.id) || 0 },
			...(station.zone ? [{ key: 'summaryZone', value: station.zone }] : [])
		],
		raw: station
	}));
}

function buildRouteRows(world, index, mode) {
	return (world.routes || [])
		.filter(route => mode === MODE_ALL || route.transportMode === mode)
		.map(route => {
			const rows = [
				{ key: 'summaryStops', value: (route.platformIds || []).length }
			];
			// The depot that runs this route, when one does. Named rather than left as an id, because an id
			// means nothing on screen. This is a literal name, not something to translate, so it goes
			// through the literal part of the summary shape.
			const depot = route.depotId ? index.depotById.get(route.depotId) : null;
			if (depot) {
				rows.push({ literal: formatName(depot.name) || null });
			}
			return {
				id: route.id,
				name: formatName(route.name),
				color: route.color,
				mode: route.transportMode,
				routeType: route.routeType,
				isLightRailRoute: route.isLightRailRoute,
				lightRailRouteNumber: route.lightRailRouteNumber,
				summary: rows,
				raw: route
			};
		});
}

function buildDepotRows(world, index, mode) {
	const sidingToDepotId = buildSidingToDepotId(world);
	// Counted from the relation rather than by scanning sidings per depot, which would be quadratic.
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
 * How many rows a mode filter would leave on the current tab, for the count on each filter chip.
 *
 * Stations return null because they are not filtered: showing the same number on all five chips
 * would suggest a filter that does nothing.
 *
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

/**
 * The transport modes present in this world, in the order the game's enum declares them, so the
 * filter chips read the same way round as the game's own lists.
 */
export const MODES = ['TRAIN', 'BOAT', 'CABLE_CAR', 'AIRPLANE'];
