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

public final class WebDashboardDataService {

	public static final long REQUEST_TIMEOUT_MILLIS = 8000L;
	public static final float ACCELERATION_UNIT_CONVERSION = 20F * 20F;

	private WebDashboardDataService() {
	}

	public static double seconds(int halfSeconds) {
		return halfSeconds / 2D;
	}

	public static JsonArray ids(Collection<Long> values) {
		final JsonArray array = new JsonArray();
		values.forEach(value -> array.add(String.valueOf(value)));
		return array;
	}

	public static JsonArray strings(Collection<String> values) {
		final JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	public static JsonObject nestedTimes(Map<Long, Map<Long, Float>> values) {
		final JsonObject outer = new JsonObject();
		if (values == null) {
			return outer;
		}
		values.forEach((outerId, inner) -> {
			final JsonObject innerObject = new JsonObject();
			if (inner != null) {
				inner.forEach((innerId, secondsValue) -> innerObject.addProperty(String.valueOf(innerId), secondsValue));
			}
			outer.add(String.valueOf(outerId), innerObject);
		});
		return outer;
	}

	public static JsonObject corner(Integer x, Integer z) {
		if (x == null || z == null) {
			return null;
		}
		final JsonObject object = new JsonObject();
		object.addProperty("x", x);
		object.addProperty("z", z);
		return object;
	}

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
			final String nameKey = "gui.mtr.transport_mode_" + mode.toString().toLowerCase(Locale.ENGLISH);
			String modeName;
			try {
				modeName = Text.translatable(nameKey).getString();
				if (modeName == null || modeName.isEmpty() || nameKey.equals(modeName)) {
					modeName = mode.toString();
				}
			} catch (Exception e) {
				modeName = mode.toString();
			}
			modeObject.addProperty("name", modeName);
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
		limits.addProperty("accelerationUnitConversion", ACCELERATION_UNIT_CONVERSION);
		root.add("limits", limits);

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

	public static JsonObject buildData(MinecraftServer server) {
		final JsonArray worlds = new JsonArray();
		int fingerprint = 1;

		final List<ServerLevel> levels = new ArrayList<>();
		server.getAllLevels().forEach(levels::add);
		levels.sort(Comparator.comparing(level -> level.dimension().location().toString()));

		for (final ServerLevel level : levels) {
			try {
				final RailwayData railwayData = RailwayData.getInstance(level);
				if (railwayData == null) {
					continue;
				}
				final DataCache dataCache = railwayData.dataCache;
				dataCache.sync();

				railwayData.routes.forEach(route -> route.platformIds.removeIf(routePlatform -> routePlatform == null || !railwayData.dataCache.platformIdMap.containsKey(routePlatform.platformId)));
				railwayData.depots.forEach(depot -> depot.routeIds.removeIf(routeId -> !railwayData.dataCache.routeIdMap.containsKey(routeId)));

				fingerprint = 31 * fingerprint + getContentFingerprint(railwayData);

				final JsonObject world = new JsonObject();
				world.addProperty("dimension", level.dimension().location().toString());

				final Map<Long, Collection<Long>> stationIdToPlatformIds = getStationIdToPlatformIds(railwayData);
				final Map<Long, Collection<Long>> depotIdToSidingIds = getDepotIdToSidingIds(railwayData);

				world.add("stations", buildStations(railwayData, dataCache, stationIdToPlatformIds));
				world.add("platforms", buildPlatforms(railwayData, dataCache));
				world.add("routes", buildRoutes(railwayData, dataCache));
				world.add("depots", buildDepots(railwayData));
				world.add("sidings", buildSidings(railwayData));
				world.add("players", buildPlayers(level));

				final JsonObject depotToSidings = new JsonObject();
				depotIdToSidingIds.forEach((depotId, sidingIds) -> depotToSidings.add(String.valueOf(depotId), ids(sidingIds)));
				world.add("depotIdToSidingIds", depotToSidings);

				final JsonObject counts = new JsonObject();
				counts.addProperty("stations", railwayData.stations.size());
				counts.addProperty("platforms", railwayData.platforms.size());
				counts.addProperty("routes", railwayData.routes.size());
				counts.addProperty("depots", railwayData.depots.size());
				counts.addProperty("sidings", railwayData.sidings.size());
				world.add("counts", counts);

				worlds.add(world);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Could not serialise the railway data of " + level.dimension().location() + ": " + e);
				e.printStackTrace();
			}
		}

		final JsonObject root = new JsonObject();
		root.addProperty("version", Keys.MOD_VERSION);
		root.addProperty("serverTimeMillis", System.currentTimeMillis());
		root.addProperty("dataRevision", fingerprint);
		root.add("worlds", worlds);
		return root;
	}

	private static JsonArray buildStations(RailwayData railwayData, DataCache dataCache, Map<Long, Collection<Long>> stationIdToPlatformIds) {
		final JsonArray stations = new JsonArray();
		for (final Station station : railwayData.stations) {
			try {
				final JsonObject object = new JsonObject();
				object.addProperty("id", String.valueOf(station.id));
				object.addProperty("name", station.name);
				object.addProperty("color", station.color);
				object.addProperty("zone", station.zone);

				final boolean hasArea = AreaBase.nonNullCorners(station);
				object.addProperty("hasArea", hasArea);
				object.add("corner1", hasArea ? corner(station.corner1.getA(), station.corner1.getB()) : null);
				object.add("corner2", hasArea ? corner(station.corner2.getA(), station.corner2.getB()) : null);
				object.addProperty("centerX", hasArea ? (station.corner1.getA() + station.corner2.getA()) / 2 : null);
				object.addProperty("centerZ", hasArea ? (station.corner1.getB() + station.corner2.getB()) / 2 : null);

				final Collection<Long> platformIds = stationIdToPlatformIds.getOrDefault(station.id, Collections.emptyList());
				object.addProperty("platformCount", platformIds.size());
				object.add("platformIds", ids(platformIds));

				final Set<Station> connectingStations = dataCache.stationIdToConnectingStations.get(station);
				final Set<String> connectingIds = new TreeSet<>();
				if (connectingStations != null) {
					connectingStations.forEach(connecting -> connectingIds.add(String.valueOf(connecting.id)));
				}
				object.add("connectingStationIds", strings(connectingIds));

				final JsonObject exits = new JsonObject();
				station.exits.forEach((parent, destinations) -> exits.add(parent, strings(destinations)));
				object.add("exits", exits);

				stations.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping station " + station.id + ": " + e);
			}
		}
		return stations;
	}

	private static JsonArray buildPlatforms(RailwayData railwayData, DataCache dataCache) {
		final JsonArray platforms = new JsonArray();
		for (final Platform platform : railwayData.platforms) {
			try {
				final JsonObject object = buildSavedRail(platform, dataCache);
				object.addProperty("stopWithoutOpeningDoors", platform.getStopWithoutOpeningDoors());
				object.addProperty("psdDisplayMode", platform.getPsdDisplayMode());
				platforms.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping platform " + platform.id + ": " + e);
			}
		}
		return platforms;
	}

	private static JsonObject buildSavedRail(SavedRailBase savedRail, DataCache dataCache) {
		final JsonObject object = new JsonObject();
		object.addProperty("id", String.valueOf(savedRail.id));
		object.addProperty("transportMode", savedRail.transportMode.toString());
		object.addProperty("name", savedRail.name);
		object.addProperty("color", savedRail.color);

		final Station station = dataCache.platformIdToStation.get(savedRail.id);
		final Depot depot = dataCache.sidingIdToDepot.get(savedRail.id);
		final Long ownerId = savedRail instanceof Platform ? station == null ? null : station.id : depot == null ? null : depot.id;
		final String ownerKey = savedRail instanceof Platform ? "stationId" : "depotId";
		if (ownerId == null) {
			object.add(ownerKey, null);
		} else {
			object.addProperty(ownerKey, String.valueOf(ownerId));
		}

		object.addProperty("dwellTimeSeconds", seconds(savedRail.getDwellTime()));
		object.addProperty("adcTimeSeconds", seconds(savedRail.getAdcTime()));

		final BlockPos midPos = savedRail.getMidPos();
		object.addProperty("midX", midPos.getX());
		object.addProperty("midY", midPos.getY());
		object.addProperty("midZ", midPos.getZ());
		object.addProperty("axis", savedRail.getAxis().toString().toUpperCase(Locale.ENGLISH));
		return object;
	}

	private static JsonArray buildRoutes(RailwayData railwayData, DataCache dataCache) {
		final JsonArray routes = new JsonArray();
		for (final Route route : railwayData.routes) {
			try {
				final JsonObject object = new JsonObject();
				object.addProperty("id", String.valueOf(route.id));
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
					object.add("depotId", null);
				} else {
					object.addProperty("depotId", String.valueOf(depot.id));
				}

				final JsonArray platformIds = new JsonArray();
				double totalDwellSeconds = 0;
				for (final Route.RoutePlatform routePlatform : new ArrayList<>(route.platformIds)) {
					final Station station = dataCache.platformIdToStation.get(routePlatform.platformId);
					final Platform platform = dataCache.platformIdMap.get(routePlatform.platformId);

					final JsonObject stop = new JsonObject();
					stop.addProperty("platformId", String.valueOf(routePlatform.platformId));
					if (station == null) {
						stop.add("stationId", null);
					} else {
						stop.addProperty("stationId", String.valueOf(station.id));
					}
					stop.addProperty("customDestination", routePlatform.customDestination == null ? "" : routePlatform.customDestination);
					stop.addProperty("stopWithoutOpeningDoors", routePlatform.stopWithoutOpeningDoors);

					stop.addProperty("customDwellTime", routePlatform.customDwellTime);
					stop.addProperty("dwellTimeSeconds", seconds(routePlatform.dwellTime));
					final int effectiveDwellHalfSeconds = routePlatform.customDwellTime ? routePlatform.dwellTime
							: platform == null ? SavedRailBase.DEFAULT_DWELL_TIME : platform.getDwellTime();
					stop.addProperty("effectiveDwellTimeSeconds", seconds(effectiveDwellHalfSeconds));
					totalDwellSeconds += seconds(effectiveDwellHalfSeconds);

					stop.addProperty("customAdcTime", routePlatform.customAdcTime);
					stop.addProperty("adcTimeSeconds", seconds(routePlatform.adcTime));

					platformIds.add(stop);
				}
				object.add("platformIds", platformIds);
				object.addProperty("totalDwellTimeSeconds", totalDwellSeconds);

				routes.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping route " + route.id + ": " + e);
			}
		}
		return routes;
	}

	private static JsonArray buildDepots(RailwayData railwayData) {
		final JsonArray depots = new JsonArray();
		for (final Depot depot : railwayData.depots) {
			try {
				final JsonObject object = new JsonObject();
				object.addProperty("id", String.valueOf(depot.id));
				object.addProperty("transportMode", depot.transportMode.toString());
				object.addProperty("name", depot.name);
				object.addProperty("color", depot.color);

				final boolean hasArea = AreaBase.nonNullCorners(depot);
				object.addProperty("hasArea", hasArea);
				object.add("corner1", hasArea ? corner(depot.corner1.getA(), depot.corner1.getB()) : null);
				object.add("corner2", hasArea ? corner(depot.corner2.getA(), depot.corner2.getB()) : null);
				object.addProperty("centerX", hasArea ? (depot.corner1.getA() + depot.corner2.getA()) / 2 : null);
				object.addProperty("centerZ", hasArea ? (depot.corner1.getB() + depot.corner2.getB()) / 2 : null);

				object.add("routeIds", ids(depot.routeIds));

				final JsonArray frequencies = new JsonArray();
				for (int hour = 0; hour < Depot.HOURS_IN_DAY; hour++) {
					frequencies.add(depot.getFrequency(hour));
				}
				object.add("frequencies", frequencies);

				object.addProperty("useRealTime", depot.useRealTime);

				final JsonArray departures = new JsonArray();
				depot.departures.forEach(departures::add);
				object.add("departures", departures);

				object.addProperty("repeatInfinitely", depot.repeatInfinitely);
				object.addProperty("cruisingAltitude", depot.cruisingAltitude);
				object.addProperty("lastDeployedMillis", depot.lastDeployedMillis);
				object.add("platformTimes", nestedTimes(depot.platformTimes));

				depots.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping depot " + depot.id + ": " + e);
			}
		}
		return depots;
	}

	private static JsonArray buildSidings(RailwayData railwayData) {
		final JsonArray sidings = new JsonArray();
		for (final Siding siding : railwayData.sidings) {
			try {
				final JsonObject object = buildSavedRail(siding, railwayData.dataCache);
				object.addProperty("stopWithoutOpeningDoors", siding.getStopWithoutOpeningDoors());
				object.addProperty("railLength", siding.railLength);
				object.addProperty("unlimitedTrains", siding.getUnlimitedTrains());
				object.addProperty("maxTrains", siding.getMaxTrains());
				object.addProperty("isManual", siding.getIsManual());
				object.addProperty("maxManualSpeed", siding.getMaxManualSpeed());
				object.addProperty("enablePredictiveBraking", siding.getEnablePredictiveBraking());
				object.addProperty("accelerationConstant", siding.getAccelerationConstant());
				object.addProperty("trainId", siding.getTrainId());
				object.addProperty("baseTrainType", siding.getBaseTrainType());
				object.addProperty("trainCars", siding.getTrainCars());
				sidings.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping siding " + siding.id + ": " + e);
			}
		}
		return sidings;
	}

	private static JsonArray buildPlayers(ServerLevel level) {
		final JsonArray players = new JsonArray();
		final List<Player> online = new ArrayList<>(level.players());

		for (final Player player : online) {
			try {
				final JsonObject object = new JsonObject();
				object.addProperty("uuid", player.getUUID().toString());
				object.addProperty("name", player.getName().getString());
				object.addProperty("x", round(player.getX(), 2));
				object.addProperty("y", round(player.getY(), 2));
				object.addProperty("z", round(player.getZ(), 2));
				object.addProperty("yaw", round(player.getYRot(), 1));
				players.add(object);
			} catch (Exception e) {
				System.out.println("[MTR-WebDashboard] Skipping a player entry: " + e);
			}
		}
		return players;
	}

	private static double round(double value, int decimals) {
		final double factor = Math.pow(10, decimals);
		return Math.round(value * factor) / factor;
	}

	private static Map<Long, Collection<Long>> getStationIdToPlatformIds(RailwayData railwayData) {
		final Map<Long, Collection<Long>> result = new HashMap<>();
		railwayData.stations.forEach(station -> result.put(station.id, new ArrayList<>()));

		for (final Platform platform : railwayData.platforms) {
			final BlockPos midPos = platform.getMidPos();
			for (final Station station : railwayData.stations) {
				if (station.inArea(midPos.getX(), midPos.getZ())) {
					result.get(station.id).add(platform.id);
					break;
				}
			}
		}
		return result;
	}

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

	private static int getContentFingerprint(RailwayData railwayData) {
		int stations = railwayData.stations.size();
		for (final Station station : railwayData.stations) {
			stations ^= Objects.hashCode(station.id) ^ Objects.hashCode(station.name) ^ station.color ^ Objects.hashCode(station.transportMode) ^ station.zone;
		}

		int platforms = railwayData.platforms.size();
		for (final Platform platform : railwayData.platforms) {
			platforms ^= Objects.hashCode(platform.id) ^ Objects.hashCode(platform.name) ^ platform.color ^ Objects.hashCode(platform.transportMode)
					^ platform.getDwellTime() ^ platform.getAdcTime()
					^ Objects.hashCode(platform.getStopWithoutOpeningDoors()) ^ platform.getPsdDisplayMode();
		}

		int routes = railwayData.routes.size();
		for (final Route route : railwayData.routes) {
			int routeHash = Objects.hashCode(route.id) ^ Objects.hashCode(route.name) ^ route.color ^ Objects.hashCode(route.transportMode)
					^ Objects.hashCode(route.routeType) ^ Objects.hashCode(route.circularState) ^ Objects.hashCode(route.lightRailRouteNumber)
					^ Objects.hashCode(route.isLightRailRoute) ^ Objects.hashCode(route.isHidden) ^ Objects.hashCode(route.disableNextStationAnnouncements)
					^ route.platformIds.size();
			for (final Route.RoutePlatform routePlatform : route.platformIds) {
				routeHash ^= Objects.hashCode(routePlatform.platformId) ^ Objects.hashCode(routePlatform.customDestination)
						^ Objects.hashCode(routePlatform.stopWithoutOpeningDoors) ^ Objects.hashCode(routePlatform.customDwellTime)
						^ routePlatform.dwellTime ^ Objects.hashCode(routePlatform.customAdcTime) ^ routePlatform.adcTime;
			}
			routes ^= routeHash;
		}

		int depots = railwayData.depots.size();
		for (final Depot depot : railwayData.depots) {
			int depotHash = Objects.hashCode(depot.id) ^ Objects.hashCode(depot.name) ^ depot.color ^ Objects.hashCode(depot.transportMode)
					^ Objects.hashCode(depot.useRealTime) ^ Objects.hashCode(depot.repeatInfinitely) ^ depot.cruisingAltitude
					^ Objects.hashCode(depot.lastDeployedMillis) ^ depot.routeIds.size() ^ depot.departures.size();
			for (final long routeId : depot.routeIds) {
				depotHash ^= Objects.hashCode(routeId);
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
			sidings ^= Objects.hashCode(siding.id) ^ Objects.hashCode(siding.name) ^ siding.color ^ Objects.hashCode(siding.transportMode)
					^ siding.getDwellTime() ^ siding.getAdcTime() ^ Objects.hashCode(siding.getStopWithoutOpeningDoors())
					^ Objects.hashCode(siding.getUnlimitedTrains()) ^ siding.getMaxTrains() ^ Objects.hashCode(siding.getIsManual())
					^ siding.getMaxManualSpeed() ^ Objects.hashCode(siding.getEnablePredictiveBraking())
					^ Objects.hashCode(siding.getAccelerationConstant()) ^ Objects.hashCode(siding.getTrainId())
					^ Objects.hashCode(siding.getBaseTrainType()) ^ siding.getTrainCars() ^ Objects.hashCode(siding.railLength);
		}

		return (((stations * 31 + platforms) * 31 + routes) * 31 + depots) * 31 + sidings;
	}
}
