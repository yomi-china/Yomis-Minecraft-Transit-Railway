package mtr.webdashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.data.AreaBase;
import mtr.data.NameColorDataBase;
import mtr.data.Platform;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.SavedRailBase;
import mtr.data.Siding;
import mtr.data.Station;
import mtr.data.Train;
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
	private static final List<String> PLATFORM_FIELDS = List.of("name", "dwellTimeSeconds", "adcTimeSeconds", "psdDisplayMode");
	private static final List<String> SIDING_FIELDS = List.of("name", "dwellTimeSeconds", "unlimitedTrains", "maxTrains", "isManual",
			"maxManualSpeed", "enablePredictiveBraking", "enableDelayedAcceleration", "accelerationMetersPerSecondSquared",
			"decelerationMetersPerSecondSquared");

	/**
	 * The in-game saved rail editor only accepts a number this long, and a longer one cannot be read back
	 * there, so the web editor keeps the same limit rather than writing a value the game would not produce.
	 */
	private static final int MAX_SAVED_RAIL_NAME_LENGTH = 10;
	private static final int MAX_PSD_DISPLAY_MODE = 2;
	/** The game's max-vehicles field is three digits, and its value already means "stored value plus one". */
	private static final int MAX_TRAINS = 998;
	private static final int MAX_MANUAL_SPEED = RailType.DIAMOND.ordinal();
	/** Only guards the int conversion; the setter resets anything outside its own range anyway. */
	private static final double MAX_SECONDS_INPUT = 1_000_000;

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

	static double readDouble(JsonObject body, String key) throws FieldException {
		final JsonElement element = body.get(key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new FieldException(key, "expected a number");
		}
		final double value = element.getAsDouble();
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			throw new FieldException(key, "expected a finite number");
		}
		return value;
	}

	static boolean readBoolean(JsonObject body, String key) throws FieldException {
		final JsonElement element = body.get(key);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw new FieldException(key, "expected true or false");
		}
		return element.getAsBoolean();
	}

	static NameColorDataBase resolve(String kind, RailwayData railwayData, long id) {
		switch (kind) {
			case "station":
				return railwayData.dataCache.stationIdMap.get(id);
			case "route":
				return railwayData.dataCache.routeIdMap.get(id);
			case "depot":
				return railwayData.dataCache.depotIdMap.get(id);
			case "platform":
				return railwayData.dataCache.platformIdMap.get(id);
			case "siding":
				return railwayData.dataCache.sidingIdMap.get(id);
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
			case "platform":
				return PLATFORM_FIELDS;
			case "siding":
				return SIDING_FIELDS;
			default:
				return NAME_COLOR_FIELDS;
		}
	}

	static Set<String> knownKinds() {
		return Set.of("station", "route", "depot", "platform", "siding");
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
		for (final String key : body.keySet()) {
			if (acceptedFields(kind).stream().noneMatch(key::equals)) {
				throw new FieldException(key, "not a field of " + kind + "; this build accepts " + String.join(", ", acceptedFields(kind)));
			}
		}

		final List<String> changed = new ArrayList<>();

		if (object instanceof Platform platform) {
			applyPlatform(platform, body, sendPacket, warnings, changed);
			return new Applied(changed, null);
		}
		if (object instanceof Siding siding) {
			applySiding(siding, body, sendPacket, warnings, changed);
			return new Applied(changed, null);
		}

		applyNameColor(object, body, sendPacket, warnings, changed);

		if (object instanceof Station station && has(body, "zone")) {
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

	private static void applyNameColor(NameColorDataBase object, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings, List<String> changed) throws FieldException {
		final boolean setName = has(body, "name");
		final boolean setColor = has(body, "color");
		if (!setName && !setColor) {
			return;
		}

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

	private static void applyPlatform(Platform platform, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings, List<String> changed) throws FieldException {
		final boolean setName = has(body, "name");
		final boolean setDwell = has(body, "dwellTimeSeconds");

		if (setName) {
			platform.name = readSavedRailName(body, "name");
		}

		if (setName || setDwell) {
			final int dwellTime = setDwell ? readHalfSeconds(body, "dwellTimeSeconds") : platform.getDwellTime();
			platform.setDwellTime(dwellTime, sendPacket);
			if (setName) {
				changed.add("name");
			}
			if (setDwell) {
				changed.add("dwellTimeSeconds");
				warnAboutDwellTime(warnings, dwellTime);
			}
		}

		if (has(body, "adcTimeSeconds")) {
			final int adcTime = readHalfSeconds(body, "adcTimeSeconds");
			platform.setAdcTime(adcTime, sendPacket);
			changed.add("adcTimeSeconds");
			if (adcTime < 0 || adcTime > SavedRailBase.MAX_ADC_TIME) {
				warnings.add("wait-after-doors time was outside 0..600 seconds and the game reset it to 0 seconds");
			}
		}

		if (has(body, "psdDisplayMode")) {
			platform.setPsdDisplayMode(readPsdDisplayMode(body), sendPacket);
			changed.add("psdDisplayMode");
		}
	}

	private static void applySiding(Siding siding, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings, List<String> changed) throws FieldException {
		final boolean setName = has(body, "name");
		final boolean setDwell = has(body, "dwellTimeSeconds");
		final float oldAcceleration = siding.getAccelerationConstant();
		final float oldDeceleration = siding.getDecelerationConstant();
		final boolean oldManual = siding.getIsManual();
		final int oldMaxManualSpeed = siding.getMaxManualSpeed();
		final int oldDwellTime = siding.getDwellTime();

		if (setName) {
			siding.name = readSavedRailName(body, "name");
		}

		final boolean unlimitedTrains = has(body, "unlimitedTrains") ? readBoolean(body, "unlimitedTrains") : siding.getUnlimitedTrains();
		final int maxTrains = has(body, "maxTrains") ? readMaxTrains(body) : siding.getMaxTrains();
		final boolean manual = has(body, "isManual") ? readBoolean(body, "isManual") : oldManual;
		final int maxManualSpeed = has(body, "maxManualSpeed") ? readMaxManualSpeed(body) : oldMaxManualSpeed;
		final boolean predictiveBraking = has(body, "enablePredictiveBraking") ? readBoolean(body, "enablePredictiveBraking") : siding.getEnablePredictiveBraking();
		final boolean delayedAcceleration = has(body, "enableDelayedAcceleration") ? readBoolean(body, "enableDelayedAcceleration") : siding.getEnableDelayedAcceleration();
		final float acceleration = has(body, "accelerationMetersPerSecondSquared")
				? toMetersPerTickSquared(readDouble(body, "accelerationMetersPerSecondSquared"), "acceleration", warnings)
				: oldAcceleration;
		// A request without a deceleration is a page written before the split, or the same page changing only
		// one of the two fields. If the siding never had two different rates, that page expects the single
		// value to move both of them; if it already had two, its deceleration is the user's setting and is
		// left alone.
		final float deceleration;
		if (has(body, "decelerationMetersPerSecondSquared")) {
			deceleration = toMetersPerTickSquared(readDouble(body, "decelerationMetersPerSecondSquared"), "deceleration", warnings);
		} else if (has(body, "accelerationMetersPerSecondSquared") && oldDeceleration == oldAcceleration) {
			deceleration = acceleration;
		} else {
			deceleration = oldDeceleration;
		}
		final int dwellTime = setDwell ? readHalfSeconds(body, "dwellTimeSeconds") : oldDwellTime;

		siding.setEnablePredictiveBraking(predictiveBraking);
		siding.setEnableDelayedAcceleration(delayedAcceleration);

		// Matches what SidingScreen does on close: any change to the driving parameters has to discard the
		// trains already sitting on the siding, or they keep running with the old values forever.
		final boolean clearTrains = acceleration != oldAcceleration || deceleration != oldDeceleration || manual != oldManual
				|| maxManualSpeed != oldMaxManualSpeed || dwellTime != oldDwellTime;
		siding.setUnlimitedTrains(unlimitedTrains, maxTrains, manual, maxManualSpeed, predictiveBraking, acceleration, deceleration, dwellTime, clearTrains, sendPacket);

		if (setName) {
			changed.add("name");
		}
		if (setDwell) {
			changed.add("dwellTimeSeconds");
			warnAboutDwellTime(warnings, dwellTime);
		}
		if (has(body, "unlimitedTrains")) {
			changed.add("unlimitedTrains");
		}
		if (has(body, "maxTrains")) {
			changed.add("maxTrains");
		}
		if (has(body, "isManual")) {
			changed.add("isManual");
		}
		if (has(body, "maxManualSpeed")) {
			changed.add("maxManualSpeed");
		}
		if (has(body, "enablePredictiveBraking")) {
			changed.add("enablePredictiveBraking");
		}
		if (has(body, "enableDelayedAcceleration")) {
			changed.add("enableDelayedAcceleration");
		}
		if (has(body, "accelerationMetersPerSecondSquared")) {
			changed.add("accelerationMetersPerSecondSquared");
		}
		if (has(body, "decelerationMetersPerSecondSquared")) {
			changed.add("decelerationMetersPerSecondSquared");
		}
	}

	private static String readSavedRailName(JsonObject body, String key) throws FieldException {
		final String name = readString(body, key);
		if (name.length() > MAX_SAVED_RAIL_NAME_LENGTH) {
			throw new FieldException(key, "longer than " + MAX_SAVED_RAIL_NAME_LENGTH + " characters");
		}
		return name;
	}

	private static int readHalfSeconds(JsonObject body, String key) throws FieldException {
		final double seconds = readDouble(body, key);
		if (Math.abs(seconds) > MAX_SECONDS_INPUT) {
			throw new FieldException(key, "out of range");
		}
		return (int) Math.round(seconds * 2);
	}

	private static void warnAboutDwellTime(List<String> warnings, int halfSeconds) {
		if (halfSeconds <= 0 || halfSeconds > SavedRailBase.MAX_DWELL_TIME) {
			warnings.add("dwell time was outside 0.5..600 seconds and the game reset it to " + WebDashboardDataService.seconds(SavedRailBase.DEFAULT_DWELL_TIME) + " seconds");
		}
	}

	private static int readMaxTrains(JsonObject body) throws FieldException {
		final int maxTrains = readInt(body, "maxTrains");
		if (maxTrains < 0 || maxTrains > MAX_TRAINS) {
			throw new FieldException("maxTrains", "expected 0.." + MAX_TRAINS);
		}
		return maxTrains;
	}

	private static int readMaxManualSpeed(JsonObject body) throws FieldException {
		final int maxManualSpeed = readInt(body, "maxManualSpeed");
		if (maxManualSpeed < 0 || maxManualSpeed > MAX_MANUAL_SPEED) {
			throw new FieldException("maxManualSpeed", "expected 0.." + MAX_MANUAL_SPEED);
		}
		return maxManualSpeed;
	}

	private static int readPsdDisplayMode(JsonObject body) throws FieldException {
		final int mode = readInt(body, "psdDisplayMode");
		if (mode < 0 || mode > MAX_PSD_DISPLAY_MODE) {
			throw new FieldException("psdDisplayMode", "expected 0.." + MAX_PSD_DISPLAY_MODE);
		}
		return mode;
	}

	private static float toMetersPerTickSquared(double metersPerSecondSquared, String fieldName, List<String> warnings) {
		final double raw = metersPerSecondSquared / WebDashboardDataService.ACCELERATION_UNIT_CONVERSION;
		final double clamped = Math.max(Train.MIN_ACCELERATION, Math.min(Train.MAX_ACCELERATION, raw));
		final double factor = Math.pow(10, Train.ACCELERATION_DECIMAL_PLACES);
		final double rounded = Math.round(clamped * factor) / factor;
		if (clamped != raw) {
			warnings.add(fieldName + " was outside "
					+ Train.MIN_ACCELERATION * WebDashboardDataService.ACCELERATION_UNIT_CONVERSION + ".."
					+ Train.MAX_ACCELERATION * WebDashboardDataService.ACCELERATION_UNIT_CONVERSION
					+ " m/s² and was limited to " + rounded * WebDashboardDataService.ACCELERATION_UNIT_CONVERSION + " m/s²");
		}
		return (float) rounded;
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
