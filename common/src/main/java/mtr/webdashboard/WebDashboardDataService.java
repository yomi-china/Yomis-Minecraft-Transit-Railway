package mtr.webdashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.Keys;
import mtr.data.AreaBase;
import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.IGui;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.RouteType;
import mtr.data.SavedRailBase;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.TransportMode;
import mtr.mappings.Text;
import mtr.mappings.Utilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the server's railway data into the read-only JSON the web dashboard consumes.
 * <p>
 * <b>Every method here must run on the game thread.</b> {@code RailwayData}'s collections and its
 * {@code DataCache} are plain {@code HashMap}s and {@code HashSet}s mutated by the game thread during
 * play; iterating them from a Jetty thread would risk a {@code ConcurrentModificationException} and,
 * worse, could read a half-applied update. {@link mtr.webdashboard.servlet.WebDashboardAsyncServlet}
 * is what guarantees the calling context.
 * <p>
 * Two kinds of relationship are computed here that the client side gets lazily and the server side has
 * no cache for at all:
 * <ul>
 *     <li>depot to its sidings - the game's {@code ClientCache.requestDepotIdToSidings} equivalent;</li>
 *     <li>station to its platforms - the game's {@code ClientCache.requestStationIdToPlatforms}
 *         equivalent.</li>
 * </ul>
 * Both use the same membership rule as {@code DataCache.mapSavedRailIdToStation}: a saved rail belongs
 * to an area when the area accepts that transport mode and the rail's midpoint falls inside it.
 * <p>
 * Ids are emitted as JSON strings. See {@link WebDashboardJson#addId}.
 */
public final class WebDashboardDataService {

	/**
	 * How long a data request may wait for the game thread before giving up. Generous: a tick can take
	 * a while on a busy integrated server, and a slow answer beats a spurious error.
	 */
	public static final long REQUEST_TIMEOUT_MILLIS = 8000L;

	/**
	 * Whether the one-time row-count diagnostic has been logged. Static, so it survives for the life of
	 * the JVM and resets on restart.
	 */
	private static volatile boolean firstBuildLogged = false;

	private WebDashboardDataService() {
	}

	// ---- metadata ---------------------------------------------------------

	/**
	 * Builds {@code /api/meta}: the enum vocabularies and limits the front end would otherwise have to
	 * hard-code.
	 * <p>
	 * Mode behaviour is reported rather than described, because the consequences are not obvious:
	 * {@code continuousMovement} true means no timetable at all, a forced dwell of one, no ADC time and
	 * unlimited vehicles. The front end reads these flags instead of re-deriving them.
	 */
	public static JsonObject buildMeta(MinecraftServer server) {
		final JsonObject root = new JsonObject();
		root.addProperty("version", Keys.MOD_VERSION);
		root.addProperty("serverTimeMillis", System.currentTimeMillis());

		final JsonArray transportModes = new JsonArray();
		for (final TransportMode mode : TransportMode.values()) {
			final JsonObject modeObject = new JsonObject();
			modeObject.addProperty("id", mode.toString());
			modeObject.addProperty("maxLength", mode.maxLength);
			modeObject.addProperty("continuousMovement", mode.continuousMovement);
			modeObject.addProperty("hasRouteTypeVariation", mode.hasRouteTypeVariation);
			modeObject.addProperty("hasPitchAscending", mode.hasPitchAscending);
			modeObject.addProperty("hasPitchDescending", mode.hasPitchDescending);
			modeObject.addProperty("railOffset", mode.railOffset);
			// The name the game shows, so a mode reads the same in the browser as in the depot screen.
			modeObject.addProperty("name", getModeName(mode));
			transportModes.add(modeObject);
		}
		root.add("transportModes", transportModes);

		final JsonArray routeTypes = new JsonArray();
		for (final RouteType routeType : RouteType.values()) {
			routeTypes.add(routeType.toString());
		}
		root.add("routeTypes", routeTypes);

		final JsonArray circularStates = new JsonArray();
		for (final Route.CircularState circularState : Route.CircularState.values()) {
			circularStates.add(circularState.toString());
		}
		root.add("circularStates", circularStates);

		final JsonObject limits = new JsonObject();
		limits.addProperty("maxDwellTime", SavedRailBase.MAX_DWELL_TIME);
		limits.addProperty("maxAdcTime", SavedRailBase.MAX_ADC_TIME);
		limits.addProperty("defaultDwellTime", SavedRailBase.DEFAULT_DWELL_TIME);
		limits.addProperty("hoursInDay", Depot.HOURS_IN_DAY);
		limits.addProperty("frequencyMultiplier", Depot.TRAIN_FREQUENCY_MULTIPLIER);
		limits.addProperty("defaultCruisingAltitude", Depot.DEFAULT_CRUISING_ALTITUDE);
		limits.addProperty("millisecondsPerDay", Depot.MILLISECONDS_PER_DAY);
		limits.addProperty("ticksPerHour", Depot.TICKS_PER_HOUR);
		// The front end needs this to convert the model's m/tick² acceleration into m/s² for display.
		limits.addProperty("accelerationUnitConversion", WebDashboardJson.ACCELERATION_UNIT_CONVERSION);
		root.add("limits", limits);

		// PSD display mode is a plain int in the model with no enum, so the valid range is reported here.
		final JsonArray psdDisplayModes = new JsonArray();
		psdDisplayModes.add(0);
		psdDisplayModes.add(1);
		psdDisplayModes.add(2);
		root.add("psdDisplayModes", psdDisplayModes);

		final JsonObject messages = new JsonObject();
		messages.addProperty("untitled", IGui.textOrUntitled(""));
		root.add("messages", messages);

		root.addProperty("serverMotd", server == null ? null : server.getMotd());

		return root;
	}

	/**
	 * @return the localised name for a transport mode. The enum carries no display name of its own, so
	 *         it comes from a translation key.
	 *         <p>
	 *         {@code transport_mode_*} is used rather than {@code route_type_<mode>_normal} because the
	 *         latter only exists for train and boat - cable car and airplane have no route-type variants,
	 *         so those keys were never added. The value read is the game's own screen text, which happens
	 *         to be prefixed with "Type: "; it is passed through rather than trimmed, so the browser shows
	 *         whatever the game shows for that language.
	 *         <p>
	 *         Falls back to the constant name on any failure: a missing translation or an unloaded
	 *         language must not take the whole response down.
	 */
	private static String getModeName(TransportMode mode) {
		final String key = "gui.mtr.transport_mode_" + mode.toString().toLowerCase(Locale.ENGLISH);
		try {
			final String name = Text.translatable(key).getString();
			// Component#getString returns the key itself when nothing is registered for it.
			return name == null || name.isEmpty() || key.equals(name) ? mode.toString() : name;
		} catch (Exception e) {
			return mode.toString();
		}
	}

	// ---- full snapshot ----------------------------------------------------

	/**
	 * Builds {@code /api/data}: every loaded world, with everything the stage 3 screens need.
	 *
	 * @param server the running server; must not be null.
	 */
	public static JsonObject buildData(MinecraftServer server) {
		final long startNanos = System.nanoTime();
		final JsonArray worlds = new JsonArray();
		int fingerprint = 1;

		// getAllLevels() exposes a live view that the server mutates as players change dimension, so it
		// is copied before being walked.
		final List<ServerLevel> levels = new ArrayList<>();
		server.getAllLevels().forEach(levels::add);
		// Sorted by dimension id so the response is stable across requests, which makes two payloads
		// diffable while debugging and keeps the content fingerprint below reproducible.
		levels.sort(Comparator.comparing(level -> level.dimension().location().toString()));

		for (final ServerLevel level : levels) {
			try {
				final RailwayData railwayData = RailwayData.getInstance(level);
				if (railwayData == null) {
					continue;
				}
				// Refreshing first means the id maps and the derived lookups this builder reads - and hashes -
				// are current. In practice the game already keeps them in sync; this closes the window
				// between an edit landing and the next scheduled sync.
				final DataCache dataCache = railwayData.dataCache;
				dataCache.sync();

				fingerprint = 31 * fingerprint + getContentFingerprint(railwayData);
				worlds.add(buildWorld(level, railwayData, dataCache));
			} catch (Exception e) {
				// One broken world must not take the whole response down; the others are still useful.
				System.out.println("[MTR-WebDashboard] Could not serialise the railway data of " + level.dimension().location() + ": " + e);
				e.printStackTrace();
			}
		}

		logFirstBuild(levels);

		final JsonObject root = new JsonObject();
		root.addProperty("version", Keys.MOD_VERSION);
		root.addProperty("serverTimeMillis", System.currentTimeMillis());
		// A hash of the data itself rather than a timestamp, so it is stable while nothing changes and
		// different as soon as anything the page displays does. The front end can poll this instead of
		// re-diffing the whole payload.
		root.addProperty("dataRevision", fingerprint);
		root.add("worlds", worlds);

		// Logged because the two questions worth asking about this endpoint are "did it run" and "how
		// expensive was it", and neither is visible from the browser.
		System.out.println("[MTR-WebDashboard] /api/data built " + worlds.size() + " world(s) in "
				+ (System.nanoTime() - startNanos) / 1_000_000L + " ms");
		return root;
	}

	private static JsonObject buildWorld(ServerLevel level, RailwayData railwayData, DataCache dataCache) {
		final JsonObject world = new JsonObject();
		world.addProperty("dimension", level.dimension().location().toString());

		// Pruned to drop routes pointing at platforms that no longer exist, so the depot-to-siding and
		// station-to-platform mappings below cannot reference something already gone.
		prune(railwayData);

		final Map<Long, Collection<Long>> stationIdToPlatformIds = getStationIdToPlatformIds(railwayData);
		final Map<Long, Collection<Long>> depotIdToSidingIds = getDepotIdToSidingIds(railwayData);

		world.add("stations", buildStations(railwayData, dataCache, stationIdToPlatformIds));
		world.add("platforms", buildPlatforms(railwayData, dataCache));
		world.add("routes", buildRoutes(railwayData, dataCache));
		world.add("depots", buildDepots(railwayData));
		world.add("sidings", buildSidings(railwayData));
		world.add("players", buildPlayers(level));

		final JsonObject depotToSidings = new JsonObject();
		depotIdToSidingIds.forEach((depotId, sidingIds) -> depotToSidings.add(String.valueOf(depotId), WebDashboardJson.ids(sidingIds)));
		world.add("depotIdToSidingIds", depotToSidings);

		final JsonObject counts = new JsonObject();
		counts.addProperty("stations", railwayData.stations.size());
		counts.addProperty("platforms", railwayData.platforms.size());
		counts.addProperty("routes", railwayData.routes.size());
		counts.addProperty("depots", railwayData.depots.size());
		counts.addProperty("sidings", railwayData.sidings.size());
		world.add("counts", counts);

		return world;
	}

	/**
	 * Applies the pruning {@code DataCache.sync()} performs anyway, explicitly, so this builder does not
	 * silently depend on a side effect of the sync call for its correctness.
	 * <p>
	 * Both prune operations remove references to objects that no longer exist: a stop pointing at a
	 * deleted platform, and a depot's route list pointing at a deleted route.
	 */
	private static void prune(RailwayData railwayData) {
		railwayData.routes.forEach(route -> route.platformIds.removeIf(routePlatform -> routePlatform == null || !railwayData.dataCache.platformIdMap.containsKey(routePlatform.platformId)));
		railwayData.depots.forEach(depot -> depot.routeIds.removeIf(routeId -> !railwayData.dataCache.routeIdMap.containsKey(routeId)));
	}

	// ---- players ----------------------------------------------------------

	/**
	 * Player positions, so the map can open centred the way the game's does.
	 * <p>
	 * <b>Only a position and a facing are published</b> - no inventory, no health, nothing that is not
	 * needed to draw a marker. Even so this is the one piece of genuinely personal data the API exposes:
	 * any signed-in account can see where everyone is. That was a deliberate decision, taken because a
	 * map that opens somewhere unrelated to the viewer is far less useful, and because the service is
	 * loopback-only by default. With {@code allowLanAccess} on, it is visible to the whole network.
	 * <p>
	 * Reads the level's player list, which is mutated as players change dimension, so the iteration is
	 * over a copied list. Callers are already on the game thread.
	 */
	private static JsonArray buildPlayers(ServerLevel level) {
		final JsonArray players = new JsonArray();
		final List<Player> online = new ArrayList<>(level.players());

		for (final Player player : online) {
			try {
				final JsonObject object = new JsonObject();
				object.addProperty("uuid", player.getUUID().toString());
				object.addProperty("name", player.getName().getString());
				// Rounded to two decimals rather than to whole blocks: the marker should sit where the player
				// actually is, and `RailwayData.round` is avoided here only because it returns a float, which
				// would quantise the coordinates for no reason.
				object.addProperty("x", round(player.getX(), 2));
				object.addProperty("y", round(player.getY(), 2));
				object.addProperty("z", round(player.getZ(), 2));
				// One decimal is enough for facing, which is not drawn yet but is cheap to publish and avoids
				// a second round-trip when a directional marker is added.
				object.addProperty("yaw", round(Utilities.getYaw(player), 1));
				players.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping a player entry: " + e);
			}
		}
		return players;
	}

	/** Rounds to a fixed number of decimals, so the payload does not carry meaningless float noise. */
	private static double round(double value, int decimals) {
		final double factor = Math.pow(10, decimals);
		return Math.round(value * factor) / factor;
	}

	// ---- stations ---------------------------------------------------------

	private static JsonArray buildStations(RailwayData railwayData, DataCache dataCache, Map<Long, Collection<Long>> stationIdToPlatformIds) {
		final JsonArray stations = new JsonArray();
		for (final Station station : railwayData.stations) {
			try {
				stations.add(buildStation(station, dataCache, stationIdToPlatformIds));
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping station " + station.id + ": " + e);
			}
		}
		return stations;
	}

	private static JsonObject buildStation(Station station, DataCache dataCache, Map<Long, Collection<Long>> stationIdToPlatformIds) {
		final JsonObject object = new JsonObject();
		WebDashboardJson.addId(object, "id", station.id);
		object.addProperty("name", station.name);
		object.addProperty("color", station.color);
		object.addProperty("zone", station.zone);

		final boolean hasArea = AreaBase.nonNullCorners(station);
		object.addProperty("hasArea", hasArea);
		// getCenter() returns null when no corners are set, and y is always 0 there, so only x and z are
		// reported. Null corners stay null rather than becoming 0 - 0 is a real coordinate.
		WebDashboardJson.addNullable(object, "corner1", hasArea ? WebDashboardJson.corner(station.corner1.getA(), station.corner1.getB()) : null);
		WebDashboardJson.addNullable(object, "corner2", hasArea ? WebDashboardJson.corner(station.corner2.getA(), station.corner2.getB()) : null);
		object.addProperty("centerX", hasArea ? (station.corner1.getA() + station.corner2.getA()) / 2 : null);
		object.addProperty("centerZ", hasArea ? (station.corner1.getB() + station.corner2.getB()) / 2 : null);

		final Collection<Long> platformIds = stationIdToPlatformIds.getOrDefault(station.id, Collections.emptyList());
		object.addProperty("platformCount", platformIds.size());
		object.add("platformIds", WebDashboardJson.ids(platformIds));

		final Set<Station> connectingStations = dataCache.stationIdToConnectingStations.get(station);
		final Set<String> connectingIds = new TreeSet<>();
		if (connectingStations != null) {
			connectingStations.forEach(connecting -> connectingIds.add(String.valueOf(connecting.id)));
		}
		object.add("connectingStationIds", WebDashboardJson.strings(connectingIds));

		final JsonObject exits = new JsonObject();
		station.exits.forEach((parent, destinations) -> exits.add(parent, WebDashboardJson.strings(destinations)));
		object.add("exits", exits);

		return object;
	}

	/**
	 * The station-to-platforms relationship the client builds lazily in
	 * {@code ClientCache.requestStationIdToPlatforms}. The server has no cache for it, so it is derived
	 * here with the same membership rule.
	 */
	private static Map<Long, Collection<Long>> getStationIdToPlatformIds(RailwayData railwayData) {
		final Map<Long, Collection<Long>> result = new HashMap<>();
		// Seeded for every station, drawn or not, so callers never need a null check for a known station.
		railwayData.stations.forEach(station -> result.put(station.id, new ArrayList<>()));

		for (final Platform platform : railwayData.platforms) {
			final BlockPos midPos = platform.getMidPos();
			for (final Station station : railwayData.stations) {
				// A station with no corners cannot contain anything, and inArea already rejects that case.
				if (station.inArea(midPos.getX(), midPos.getZ())) {
					result.get(station.id).add(platform.id);
					break;
				}
			}
		}
		return result;
	}

	// ---- platforms --------------------------------------------------------

	private static JsonArray buildPlatforms(RailwayData railwayData, DataCache dataCache) {
		final JsonArray platforms = new JsonArray();
		for (final Platform platform : railwayData.platforms) {
			try {
				platforms.add(buildPlatform(platform, dataCache));
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping platform " + platform.id + ": " + e);
			}
		}
		return platforms;
	}

	private static JsonObject buildPlatform(Platform platform, DataCache dataCache) {
		final JsonObject object = buildSavedRail(platform, dataCache);
		object.addProperty("stopWithoutOpeningDoors", platform.getStopWithoutOpeningDoors());
		object.addProperty("psdDisplayMode", platform.getPsdDisplayMode());
		return object;
	}

	/**
	 * The fields shared by platforms and sidings. Both extend {@code SavedRailBase}, and the getters for
	 * dwell and ADC time already clamp out-of-range values and force the cable-car values, so what is
	 * reported is what the game would actually use.
	 */
	private static JsonObject buildSavedRail(SavedRailBase savedRail, DataCache dataCache) {
		final JsonObject object = new JsonObject();
		WebDashboardJson.addId(object, "id", savedRail.id);
		object.addProperty("transportMode", savedRail.transportMode.toString());
		object.addProperty("name", savedRail.name);
		object.addProperty("color", savedRail.color);

		final Station station = dataCache.platformIdToStation.get(savedRail.id);
		final Depot depot = dataCache.sidingIdToDepot.get(savedRail.id);
		final Long ownerId = savedRail instanceof Platform ? station == null ? null : station.id : depot == null ? null : depot.id;
		final String ownerKey = savedRail instanceof Platform ? "stationId" : "depotId";
		if (ownerId == null) {
			WebDashboardJson.addNullable(object, ownerKey, null);
		} else {
			object.addProperty(ownerKey, String.valueOf(ownerId));
		}

		// Model stores half-seconds; the API speaks seconds so the front end never meets that unit.
		WebDashboardJson.addNumber(object, "dwellTimeSeconds", WebDashboardJson.seconds(savedRail.getDwellTime()));
		WebDashboardJson.addNumber(object, "adcTimeSeconds", WebDashboardJson.seconds(savedRail.getAdcTime()));

		final BlockPos midPos = savedRail.getMidPos();
		object.addProperty("midX", midPos.getX());
		object.addProperty("midY", midPos.getY());
		object.addProperty("midZ", midPos.getZ());
		// Uppercased deliberately. Direction.Axis#toString is lower case, but the API contract and every
		// other piece of axis notation in this project use upper case, and a front end comparing against
		// "X" would silently never match a lower-case value.
		object.addProperty("axis", savedRail.getAxis().toString().toUpperCase(Locale.ENGLISH));
		return object;
	}

	// ---- routes -----------------------------------------------------------

	private static JsonArray buildRoutes(RailwayData railwayData, DataCache dataCache) {
		final JsonArray routes = new JsonArray();
		for (final Route route : railwayData.routes) {
			try {
				routes.add(buildRoute(route, dataCache));
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping route " + route.id + ": " + e);
			}
		}
		return routes;
	}

	private static JsonObject buildRoute(Route route, DataCache dataCache) {
		final JsonObject object = new JsonObject();
		WebDashboardJson.addId(object, "id", route.id);
		object.addProperty("transportMode", route.transportMode.toString());
		object.addProperty("name", route.name);
		object.addProperty("color", route.color);
		object.addProperty("routeType", route.routeType.toString());
		object.addProperty("isLightRailRoute", route.isLightRailRoute);
		object.addProperty("lightRailRouteNumber", route.lightRailRouteNumber == null ? "" : route.lightRailRouteNumber);
		object.addProperty("isHidden", route.isHidden);
		object.addProperty("circularState", route.circularState.toString());
		object.addProperty("disableNextStationAnnouncements", route.disableNextStationAnnouncements);

		final Depot depot = dataCache.routeIdToOneDepot.get(route.id);
		if (depot == null) {
			WebDashboardJson.addNullable(object, "depotId", null);
		} else {
			object.addProperty("depotId", String.valueOf(depot.id));
		}

		final JsonArray platformIds = new JsonArray();
		double totalDwellSeconds = 0;
		for (final Route.RoutePlatform routePlatform : routePlatforms(route)) {
			// The cache stores the station for a platform, so the stop can name its station without a
			// second lookup through the platform map.
			final Station station = dataCache.platformIdToStation.get(routePlatform.platformId);
			final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);

			final JsonObject stop = new JsonObject();
			stop.addProperty("platformId", String.valueOf(routePlatform.platformId));
			if (station == null) {
				WebDashboardJson.addNullable(stop, "stationId", null);
			} else {
				stop.addProperty("stationId", String.valueOf(station.id));
			}
			stop.addProperty("customDestination", routePlatform.customDestination == null ? "" : routePlatform.customDestination);
			stop.addProperty("stopWithoutOpeningDoors", routePlatform.stopWithoutOpeningDoors);

			stop.addProperty("customDwellTime", routePlatform.customDwellTime);
			// The raw field is what an editor round-trips; the effective one is what a passenger
			// experiences. Both are reported because neither alone is enough: editing needs the stored
			// value, and any display of journey time needs the resolved one.
			WebDashboardJson.addNumber(stop, "dwellTimeSeconds", WebDashboardJson.seconds(routePlatform.dwellTime));
			final int effectiveDwellHalfSeconds = resolveEffectiveDwell(routePlatform, platform);
			WebDashboardJson.addNumber(stop, "effectiveDwellTimeSeconds", WebDashboardJson.seconds(effectiveDwellHalfSeconds));
			totalDwellSeconds += WebDashboardJson.seconds(effectiveDwellHalfSeconds);

			stop.addProperty("customAdcTime", routePlatform.customAdcTime);
			WebDashboardJson.addNumber(stop, "adcTimeSeconds", WebDashboardJson.seconds(routePlatform.adcTime));

			platformIds.add(stop);
		}
		object.add("platformIds", platformIds);
		// The sum of every stop's effective dwell, so it is directly comparable with what the game
		// simulates. Summing only the custom overrides would report zero for the common case where every
		// stop inherits its platform's dwell, which is worse than useless on a summary line.
		WebDashboardJson.addNumber(object, "totalDwellTimeSeconds", totalDwellSeconds);

		return object;
	}

	/**
	 * Defensive copy of a route's stop list.
	 * <p>
	 * {@code Route.platformIds} is a public mutable list, and the game's own dashboard swaps entries in
	 * place while the editor is open. Walking it directly here would be reading a list that another
	 * thread could be rearranging; this builder runs on the game thread so that cannot happen today, but
	 * the copy makes the assumption explicit and cheap.
	 */
	private static List<Route.RoutePlatform> routePlatforms(Route route) {
		return new ArrayList<>(route.platformIds);
	}

	/**
	 * Resolves the dwell a stop actually uses.
	 * <p>
	 * A custom dwell wins; otherwise the stop inherits the dwell of the platform it stops at, which is
	 * how the game's own path construction behaves. A stop whose platform has been deleted has nothing to
	 * inherit, so it falls back to the model default rather than to zero.
	 */
	private static int resolveEffectiveDwell(Route.RoutePlatform routePlatform, Platform platform) {
		if (routePlatform.customDwellTime) {
			return routePlatform.dwellTime;
		}
		return platform == null ? SavedRailBase.DEFAULT_DWELL_TIME : platform.getDwellTime();
	}

	// ---- depots -----------------------------------------------------------

	private static JsonArray buildDepots(RailwayData railwayData) {
		final JsonArray depots = new JsonArray();
		for (final Depot depot : railwayData.depots) {
			try {
				depots.add(buildDepot(depot));
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping depot " + depot.id + ": " + e);
			}
		}
		return depots;
	}

	private static JsonObject buildDepot(Depot depot) {
		final JsonObject object = new JsonObject();
		WebDashboardJson.addId(object, "id", depot.id);
		object.addProperty("transportMode", depot.transportMode.toString());
		object.addProperty("name", depot.name);
		object.addProperty("color", depot.color);

		final boolean hasArea = AreaBase.nonNullCorners(depot);
		object.addProperty("hasArea", hasArea);
		WebDashboardJson.addNullable(object, "corner1", hasArea ? WebDashboardJson.corner(depot.corner1.getA(), depot.corner1.getB()) : null);
		WebDashboardJson.addNullable(object, "corner2", hasArea ? WebDashboardJson.corner(depot.corner2.getA(), depot.corner2.getB()) : null);
		object.addProperty("centerX", hasArea ? (depot.corner1.getA() + depot.corner2.getA()) / 2 : null);
		object.addProperty("centerZ", hasArea ? (depot.corner1.getB() + depot.corner2.getB()) / 2 : null);

		object.add("routeIds", WebDashboardJson.ids(depot.routeIds));

		// Full 24 entries always, so the front end can index by hour without length checks.
		final JsonArray frequencies = new JsonArray();
		for (int hour = 0; hour < Depot.HOURS_IN_DAY; hour++) {
			frequencies.add(depot.getFrequency(hour));
		}
		object.add("frequencies", frequencies);

		object.addProperty("useRealTime", depot.useRealTime);

		final JsonArray departures = new JsonArray();
		// Model unit: milliseconds into the day, always a whole second. Sent as plain numbers, and the
		// front end formats them as clock times.
		depot.departures.forEach(departures::add);
		object.add("departures", departures);

		object.addProperty("repeatInfinitely", depot.repeatInfinitely);
		object.addProperty("cruisingAltitude", depot.cruisingAltitude);
		object.addProperty("lastDeployedMillis", depot.lastDeployedMillis);
		// Run times between consecutive stops, in seconds, keyed by whichever saved rail the train passes.
		// The inner key is usually a platform id, but not always: path generation treats a siding as a
		// pseudo-platform on the way out of the depot, so a siding id shows up here too. Callers must not
		// assume every key resolves against the platforms list.
		// Empty until a path has been generated successfully at least once.
		object.add("platformTimes", WebDashboardJson.nestedTimes(depot.platformTimes));
		// No sidingIds here on purpose. The depot-to-sidings relation is published once, in the world's
		// depotIdToSidingIds, and duplicating it per depot would mean two copies that could disagree and a
		// payload that grows with every siding. The front end indexes the map once.

		return object;
	}

	/**
	 * The depot-to-sidings relationship the client builds lazily in
	 * {@code ClientCache.requestDepotIdToSidings}. Same membership rule as the station mapping.
	 */
	private static Map<Long, Collection<Long>> getDepotIdToSidingIds(RailwayData railwayData) {
		final Map<Long, Collection<Long>> result = new HashMap<>();
		railwayData.depots.forEach(depot -> result.put(depot.id, new ArrayList<>()));

		for (final Siding siding : railwayData.sidings) {
			final BlockPos midPos = siding.getMidPos();
			for (final Depot depot : railwayData.depots) {
				if (siding.isTransportMode(depot.transportMode) && depot.inArea(midPos.getX(), midPos.getZ())) {
					result.get(depot.id).add(siding.id);
					break;
				}
			}
		}
		return result;
	}

	// ---- sidings ----------------------------------------------------------

	private static JsonArray buildSidings(RailwayData railwayData) {
		final JsonArray sidings = new JsonArray();
		for (final Siding siding : railwayData.sidings) {
			try {
				sidings.add(buildSiding(siding, railwayData.dataCache));
			} catch (Exception e) {
				// The id is logged but not the name: a broken name may be exactly what threw, and a
				// half-escaped string in the log is worse than no string at all.
				System.out.println("[MTR-WebDashboard] Skipping siding " + siding.id + ": " + e);
			}
		}
		return sidings;
	}

	private static JsonObject buildSiding(Siding siding, DataCache dataCache) {
		// depotId comes from the shared builder, which reads DataCache.sidingIdToDepot. That cache is
		// populated with the same membership rule this service uses for depotIdToSidingIds, so the two
		// agree by construction rather than by being computed twice and hoping they match.
		final JsonObject object = buildSavedRail(siding, dataCache);

		object.addProperty("stopWithoutOpeningDoors", siding.getStopWithoutOpeningDoors());
		object.addProperty("railLength", siding.railLength);
		object.addProperty("unlimitedTrains", siding.getUnlimitedTrains());
		object.addProperty("maxTrains", siding.getMaxTrains());
		object.addProperty("isManual", siding.getIsManual());
		object.addProperty("maxManualSpeed", siding.getMaxManualSpeed());
		object.addProperty("enablePredictiveBraking", siding.getEnablePredictiveBraking());
		// Model unit is m/tick². Left unconverted on purpose; limits.accelerationUnitConversion in /api/meta
		// is what a display multiplies by to get m/s².
		object.addProperty("accelerationConstant", siding.getAccelerationConstant());
		object.addProperty("trainId", siding.getTrainId());
		object.addProperty("baseTrainType", siding.getBaseTrainType());
		object.addProperty("trainCars", siding.getTrainCars());
		return object;
	}

	// ---- helpers ----------------------------------------------------------

	/**
	 * A hash of everything the page displays, used as {@code dataRevision}.
	 * <p>
	 * Deliberately derived from the content rather than from a timestamp. The obvious source would be
	 * {@code DataCache}'s own last-sync stamp, but that field is private to {@code mtr.data} - and going
	 * through it would have been the wrong signal anyway, because it advances on any refresh whether or
	 * not anything a visitor can see actually changed. Hashing the model gives the front end a value
	 * that is stable while nothing changes and differs the moment something does.
	 * <p>
	 * Order independent by construction: every contribution is XOR-ed into an accumulator and the
	 * per-collection counts are folded in separately. XOR-ing rather than adding means two objects whose
	 * hashes happen to be equal cancel out instead of doubling, which is what the count terms are there
	 * to notice.
	 * <p>
	 * Covers names, colours and the settings the editor exposes, but not positions or area corners: a
	 * selection being redrawn is not something the list needs to reload for, and the extra fields would
	 * roughly double the work for no benefit.
	 */
	private static int getContentFingerprint(RailwayData railwayData) {
		// Per collection rather than one accumulator, so a change in one cannot be masked by a change in
		// another. XOR-ing a per-collection hash with the same value twice would cancel, which is exactly
		// the trap this avoids.
		int stations = railwayData.stations.size();
		for (final Station station : railwayData.stations) {
			stations ^= hash(station.id) ^ hash(station.name) ^ station.color ^ hash(station.transportMode) ^ station.zone;
		}

		int platforms = railwayData.platforms.size();
		for (final Platform platform : railwayData.platforms) {
			platforms ^= hash(platform.id) ^ hash(platform.name) ^ platform.color ^ hash(platform.transportMode)
					^ platform.getDwellTime() ^ platform.getAdcTime()
					^ hash(platform.getStopWithoutOpeningDoors()) ^ platform.getPsdDisplayMode();
		}

		int routes = railwayData.routes.size();
		for (final Route route : railwayData.routes) {
			int routeHash = hash(route.id) ^ hash(route.name) ^ route.color ^ hash(route.transportMode)
					^ hash(route.routeType) ^ hash(route.circularState) ^ hash(route.lightRailRouteNumber)
					^ hash(route.isLightRailRoute) ^ hash(route.isHidden) ^ hash(route.disableNextStationAnnouncements)
					^ route.platformIds.size();
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				routeHash ^= hash(routePlatform.platformId) ^ hash(routePlatform.customDestination)
						^ hash(routePlatform.stopWithoutOpeningDoors) ^ hash(routePlatform.customDwellTime)
						^ routePlatform.dwellTime ^ hash(routePlatform.customAdcTime) ^ routePlatform.adcTime;
			}
			routes ^= routeHash;
		}

		int depots = railwayData.depots.size();
		for (final Depot depot : railwayData.depots) {
			int depotHash = hash(depot.id) ^ hash(depot.name) ^ depot.color ^ hash(depot.transportMode)
					^ hash(depot.useRealTime) ^ hash(depot.repeatInfinitely) ^ depot.cruisingAltitude
					^ hash(depot.lastDeployedMillis) ^ depot.routeIds.size() ^ depot.departures.size();
			for (final long routeId : depot.routeIds) {
				depotHash ^= hash(routeId);
			}
			for (int hour = 0; hour < Depot.HOURS_IN_DAY; hour++) {
				depotHash ^= depot.getFrequency(hour);
			}
			for (final int departure : depot.departures) {
				depotHash ^= departure;
			}
			depots ^= depotHash;
		}

		int sidings = railwayData.sidings.size();
		for (final Siding siding : railwayData.sidings) {
			sidings ^= hash(siding.id) ^ hash(siding.name) ^ siding.color ^ hash(siding.transportMode)
					^ siding.getDwellTime() ^ siding.getAdcTime() ^ hash(siding.getStopWithoutOpeningDoors())
					^ hash(siding.getUnlimitedTrains()) ^ siding.getMaxTrains() ^ hash(siding.getIsManual())
					^ siding.getMaxManualSpeed() ^ hash(siding.getEnablePredictiveBraking())
					^ hash(siding.getAccelerationConstant()) ^ hash(siding.getTrainId())
					^ hash(siding.getBaseTrainType()) ^ siding.getTrainCars() ^ hash(siding.railLength);
		}

		return (((stations * 31 + platforms) * 31 + routes) * 31 + depots) * 31 + sidings;
	}

	/**
	 * {@code Objects.hashCode} under a shorter name. Worth the indirection here because it is called
	 * with boxed primitives throughout the fingerprint above, and {@code Float}-valued fields have to
	 * go through a boxed form to hash sensibly anyway.
	 */
	private static int hash(Object value) {
		return Objects.hashCode(value);
	}

	/**
	 * One-time diagnostic for a fresh server: reports the row counts the web page will see, so an empty
	 * dashboard can be traced to the data rather than to the page. Logged once per server start because
	 * it would otherwise repeat on every request the page makes.
	 */
	private static void logFirstBuild(List<ServerLevel> levels) {
		if (firstBuildLogged) {
			return;
		}
		firstBuildLogged = true;

		final StringBuilder summary = new StringBuilder();
		levels.forEach(level -> {
			final RailwayData railwayData = RailwayData.getInstance(level);
			if (railwayData != null) {
				if (summary.length() > 0) {
					summary.append("; ");
				}
				summary.append(level.dimension().location()).append(": ")
						.append(railwayData.stations.size()).append(" stations, ")
						.append(railwayData.platforms.size()).append(" platforms, ")
						.append(railwayData.routes.size()).append(" routes, ")
						.append(railwayData.depots.size()).append(" depots, ")
						.append(railwayData.sidings.size()).append(" sidings");
			}
		});
		System.out.println("[MTR-WebDashboard] First /api/data request. " + (summary.length() == 0 ? "No world has railway data yet." : summary));
	}
}
