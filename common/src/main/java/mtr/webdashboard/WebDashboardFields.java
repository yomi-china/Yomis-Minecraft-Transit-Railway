package mtr.webdashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.data.AreaBase;
import mtr.data.NameColorDataBase;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.Station;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class WebDashboardFields {

	private WebDashboardFields() {
	}

	private static final List<String> NAME_COLOR_FIELDS = List.of("name", "color");
	private static final List<String> AREA_FIELDS = List.of("name", "color", "corners");
	private static final List<String> STATION_FIELDS = List.of("name", "color", "corners", "zone");

	static boolean has(JsonObject body, String key) {
		final JsonElement element = body.get(key);
		return element != null && !element.isJsonNull();
	}

	static String readString(JsonObject body, String key) throws FieldException {
		final JsonElement element = body.get(key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new FieldException(key, "expected a string");
		}
		final String value = element.getAsString();
		if (value.length() > 32767) {
			throw new FieldException(key, "longer than 32767 characters");
		}
		return value;
	}

	static int readInt(JsonObject body, String key) throws FieldException {
		final JsonElement element = body.get(key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new FieldException(key, "expected a number");
		}
		final double raw = element.getAsDouble();
		if (raw != Math.floor(raw) || Double.isNaN(raw) || Double.isInfinite(raw)) {
			throw new FieldException(key, "expected a whole number");
		}
		return (int) raw;
	}

	static NameColorDataBase resolve(String kind, RailwayData railwayData, long id) {
		switch (kind) {
			case "station":
				return railwayData.dataCache.stationIdMap.get(id);
			case "route":
				return railwayData.dataCache.routeIdMap.get(id);
			case "depot":
				return railwayData.dataCache.depotIdMap.get(id);
			default:
				return null;
		}
	}

	static List<String> acceptedFields(String kind) {
		switch (kind) {
			case "station":
				return STATION_FIELDS;
			case "depot":
				return AREA_FIELDS;
			default:
				return NAME_COLOR_FIELDS;
		}
	}

	static Set<String> knownKinds() {
		return Set.of("station", "route", "depot");
	}

	static final class Applied {

		final List<String> changed;
		final JsonObject effects;

		Applied(List<String> changed, JsonObject effects) {
			this.changed = changed;
			this.effects = effects;
		}
	}

	static Applied apply(String kind, RailwayData railwayData, NameColorDataBase object, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings) throws FieldException {
		final List<String> changed = new ArrayList<>();

		for (final String key : body.keySet()) {
			if (acceptedFields(kind).stream().noneMatch(key::equals)) {
				throw new FieldException(key, "not a field of " + kind + "; this build accepts " + String.join(", ", acceptedFields(kind)));
			}
		}

		final boolean setName = has(body, "name");
		final boolean setColor = has(body, "color");

		if (setName || setColor) {
			final String name = setName ? readString(body, "name") : object.name;
			int color = object.color;
			if (setColor) {
				final int raw = readInt(body, "color");
				if (raw < 0 || raw > 0xFFFFFF) {
					final int clamped = Math.max(0, Math.min(0xFFFFFF, raw));
					warnings.add("color was outside 0..16777215 and was clamped to " + clamped);
					color = clamped;
				} else {
					color = raw;
				}
			}
			object.name = name;
			object.color = color;
			object.setNameColor(sendPacket);
			if (setName) {
				changed.add("name");
			}
			if (setColor) {
				changed.add("color");
			}
		}

		if ("station".equals(kind) && has(body, "zone")) {
			final Station station = (Station) object;
			station.zone = readInt(body, "zone");
			station.setZone(sendPacket);
			changed.add("zone");
		}

		JsonObject effects = null;
		if (has(body, "corners")) {
			final JsonElement cornersElement = body.get("corners");
			if (!cornersElement.isJsonObject()) {
				throw new FieldException("corners", "expected an object with corner1 and corner2");
			}
			final JsonObject corners = cornersElement.getAsJsonObject();

			final AreaBase area = (AreaBase) object;
			final Tuple<Integer, Integer> corner1 = readCorner(corners, "corner1");
			final Tuple<Integer, Integer> corner2 = readCorner(corners, "corner2");

			final Set<Long> before = savedRailsInArea(railwayData, area, area.corner1, area.corner2);

			area.corner1 = corner1;
			area.corner2 = corner2;
			area.setCorners(sendPacket);
			changed.add("corners");
			effects = calculateAreaEffects(railwayData, kind, area, before);
		}

		return new Applied(changed, effects);
	}

	private static Tuple<Integer, Integer> readCorner(JsonObject body, String key) throws FieldException {
		final JsonElement element = body.get(key);
		if (element == null || !element.isJsonObject()) {
			throw new FieldException(key, "expected an object with x and z");
		}

		final JsonObject corner = element.getAsJsonObject();
		if (!corner.has("x") || !corner.has("z")) {
			throw new FieldException(key, "expected both x and z");
		}

		final int x = readInt(corner, "x");
		final int z = readInt(corner, "z");
		if (x == 0 && z == 0) {
			throw new FieldException(key, "a corner at 0, 0 cannot be stored");
		}
		return new Tuple<>(x, z);
	}

	private static Set<Long> savedRailsInArea(RailwayData railwayData, AreaBase area, Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2) {
		final Set<Long> ids = new LinkedHashSet<>();
		if (corner1 == null || corner2 == null) {
			return ids;
		}

		for (final Platform platform : railwayData.platforms) {
			if (area.isTransportMode(platform.transportMode) && inArea(corner1, corner2, platform.getMidPos().getX(), platform.getMidPos().getZ())) {
				ids.add(platform.id);
			}
		}
		for (final Siding siding : railwayData.sidings) {
			if (area.isTransportMode(siding.transportMode) && inArea(corner1, corner2, siding.getMidPos().getX(), siding.getMidPos().getZ())) {
				ids.add(siding.id);
			}
		}
		return ids;
	}

	private static boolean inArea(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int x, int z) {
		return RailwayData.isBetween(x, corner1.getA(), corner2.getA()) && RailwayData.isBetween(z, corner1.getB(), corner2.getB());
	}

	private static JsonObject calculateAreaEffects(RailwayData railwayData, String kind, AreaBase area, Set<Long> before) {
		final Set<Long> after = savedRailsInArea(railwayData, area, area.corner1, area.corner2);

		final Set<Long> added = new LinkedHashSet<>(after);
		added.removeAll(before);
		final Set<Long> removed = new LinkedHashSet<>(before);
		removed.removeAll(after);

		if (added.isEmpty() && removed.isEmpty()) {
			return null;
		}

		final boolean depot = "depot".equals(kind);
		final JsonObject effects = new JsonObject();
		final JsonArray savedRails = new JsonArray();
		removed.forEach(id -> savedRails.add(describeSavedRail(railwayData, depot, id, "removed")));
		added.forEach(id -> savedRails.add(describeSavedRail(railwayData, depot, id, "added")));
		effects.add("savedRails", savedRails);
		effects.addProperty("addedCount", added.size());
		effects.addProperty("removedCount", removed.size());

		int routeStopsRemoved = 0;
		if (!removed.isEmpty()) {
			for (final Route route : railwayData.routes) {
				for (final long platformId : removed) {
					if (route.containsPlatformId(platformId)) {
						routeStopsRemoved++;
						break;
					}
				}
			}
		}
		effects.addProperty("routeStopsRemoved", routeStopsRemoved);

		effects.addProperty("savedRailKind", depot ? "siding" : "platform");
		return effects;
	}

	private static JsonObject describeSavedRail(RailwayData railwayData, boolean depot, long id, String change) {
		final JsonObject json = new JsonObject();
		json.addProperty("id", String.valueOf(id));
		json.addProperty("kind", depot ? "siding" : "platform");

		String name = "";
		if (depot) {
			for (final Siding siding : railwayData.sidings) {
				if (siding.id == id) {
					name = siding.name;
					break;
				}
			}
		} else {
			for (final Platform platform : railwayData.platforms) {
				if (platform.id == id) {
					name = platform.name;
					break;
				}
			}
		}

		json.addProperty("name", name);
		json.addProperty("change", change);
		return json;
	}

	static final class FieldException extends Exception {

		private final String field;

		FieldException(String field, String reason) {
			super(reason);
			this.field = field;
		}

		String getField() {
			return field;
		}
	}
}
