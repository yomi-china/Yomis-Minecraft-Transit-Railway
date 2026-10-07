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

/**
 * Which fields each kind of object accepts over the write API. The only file to edit when adding an
 * editable field; parsing, permission checks, broadcasting and auditing are generic and live in
 * {@link WebDashboardEdits}.
 *
 * Two rules for every field:
 * <ol>
 *   <li>Write through the model's own setter, never by assigning a field directly. The setter builds the
 *       packet the in-game client expects, so a web edit is byte-for-byte identical to the same edit made in
 *       game, and the model's own clamping applies instead of being reimplemented here.</li>
 *   <li>Only touch fields that were actually sent. A PATCH that omits a field must leave it alone.</li>
 * </ol>
 *
 * Package-private: the servlet package calls {@link WebDashboardEdits} and has no business deciding what a
 * patch may contain.
 */
final class WebDashboardFields {

	private WebDashboardFields() {
	}

	/**
	 * The fields every {@link NameColorDataBase} accepts, which is every resource in this stage.
	 */
	private static final List<String> NAME_COLOR_FIELDS = List.of("name", "color");

	/**
	 * A station or depot, both of which have a selection.
	 *
	 * A selection is set as one unit, so the key is the whole rectangle rather than a corner.
	 *
	 * <b>A station's list must contain "corners" too.</b> An earlier version had the station list as
	 * name/colour/zone and a separate depot list with the selection, and the omission was invisible until a
	 * visitor drew a rectangle: the page sent `corners`, the server refused the whole request as an unknown
	 * field, and every attempt to save a selection failed. Nothing about the station's other fields was wrong,
	 * which is what made it look like a map problem rather than a field-table one.
	 */
	private static final List<String> AREA_FIELDS = List.of("name", "color", "corners");

	/** A station takes a zone on top of the shared fields. */
	private static final List<String> STATION_FIELDS = List.of("name", "color", "corners", "zone");

	// ---- field parsing ------------------------------------------------------

	/** @return whether the body carries the key. A JSON null counts as absent. */
	static boolean has(JsonObject body, String key) {
		final JsonElement element = body.get(key);
		return element != null && !element.isJsonNull();
	}

	/**
	 * @return the field's string value.
	 * @throws FieldException when the value is not a string, or exceeds PACKET_STRING_READ_LENGTH - a longer
	 *         name would be truncated by the packet writer, so it is refused rather than shortened.
	 */
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

	/**
	 * @return the field's integer value.
	 * @throws FieldException when the value is not a whole number.
	 */
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

	// ---- resolution ---------------------------------------------------------

	/** @return the object, or null when the kind is unknown or the id does not exist. */
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

	/** @return the field names the kind accepts, for the error message and the "nothing to do" check. */
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

	// ---- applying -----------------------------------------------------------

	/** The outcome of applying a patch: which fields were written, and what else it moved. */
	static final class Applied {

		final List<String> changed;
		final JsonObject effects;

		Applied(List<String> changed, JsonObject effects) {
			this.changed = changed;
			this.effects = effects;
		}
	}

	/**
	 * Writes the request's fields onto the object.
	 *
	 * @param railwayData the world's data, read for the saved rails a selection change would move.
	 * @param sendPacket  receives the packet the model builds; the caller broadcasts it.
	 * @param warnings    collects notes about values that had to be adjusted.
	 * @return which fields were written, plus the side effects of a selection change when there was one.
	 * @throws FieldException when a field is present but malformed, or is not one this build accepts.
	 */
	static Applied apply(String kind, RailwayData railwayData, NameColorDataBase object, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings) throws FieldException {
		final List<String> changed = new ArrayList<>();

		// Unknown fields are refused rather than ignored for now, so a client that spells "color" as "colour"
		// fails loudly instead of appearing to succeed. This should soften to ignoring once the field set
		// stops moving.
		for (final String key : body.keySet()) {
			if (acceptedFields(kind).stream().noneMatch(key::equals)) {
				throw new FieldException(key, "not a field of " + kind + "; this build accepts " + String.join(", ", acceptedFields(kind)));
			}
		}

		final boolean setName = has(body, "name");
		final boolean setColor = has(body, "color");

		if (setName || setColor) {
			final String name = setName ? readString(body, "name") : object.name;
			final int color = setColor ? readColor(body, warnings) : object.color;
			object.name = name;
			object.color = color;
			// One packet carries both, so they are applied together.
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
			final AreaBase area = (AreaBase) object;
			// Both corners are read before either is written, so a malformed second one cannot leave the
			// selection half-updated in memory while the request is reported as failed.
			final Tuple<Integer, Integer> corner1 = readCorner(body, "corner1");
			final Tuple<Integer, Integer> corner2 = readCorner(body, "corner2");

			// Captured before the change: moving a selection changes which saved rails belong to it, and that
			// is the consequence worth reporting. See calculateAreaEffects.
			final Set<Long> before = savedRailsInArea(railwayData, area, area.corner1, area.corner2);

			area.corner1 = corner1;
			area.corner2 = corner2;
			area.setCorners(sendPacket);
			changed.add("corners");
			effects = calculateAreaEffects(railwayData, kind, area, before);
		}

		return new Applied(changed, effects);
	}

	/**
	 * Reads one corner of a selection.
	 *
	 * <b>A corner at (0, 0) is refused.</b> {@code AreaBase.setCorners} treats that value as "no selection set"
	 * and nulls the corner out, so accepting one would mean answering "saved" to a change that silently
	 * cleared the selection instead. The web page prevents the case while the selection is being dragged, so
	 * this is the backstop for a hand-written request rather than a path a visitor can reach.
	 *
	 * @return the corner.
	 * @throws FieldException when either coordinate is missing, not a whole number, or zero on both axes.
	 */
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

	/**
	 * The saved rails a selection contains, matched the way {@code DataCache} matches them.
	 *
	 * The transport-mode test matters and is easy to leave out: a station answers true only for trains, so a
	 * boat platform inside a station's rectangle is not a member of it. Reusing the cache's own rule here is
	 * what keeps the report consistent with the membership the server actually applies.
	 *
	 * @return the ids, or an empty set when a corner is absent.
	 */
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

	/** Containment, with both ends included, matching {@code AreaBase.inArea}. */
	private static boolean inArea(Tuple<Integer, Integer> corner1, Tuple<Integer, Integer> corner2, int x, int z) {
		return RailwayData.isBetween(x, corner1.getA(), corner2.getA()) && RailwayData.isBetween(z, corner1.getB(), corner2.getB());
	}

	/**
	 * Reports what moving a selection moved with it.
	 *
	 * Worth the work because the consequence is invisible otherwise: a saved rail's area is worked out from
	 * whether its middle falls inside the rectangle, so shrinking a station quietly orphans platforms - and
	 * {@code DataCache.sync} then drops those platforms from every route that called at them. A visitor who
	 * only sees "saved" has no way to know they just cut a stop out of a line.
	 *
	 * Both states are worked out from the corners rather than read back from the cache, because the cache has
	 * already been resynced by the time this runs and no longer holds the previous membership. A null corner
	 * means the selection did not exist, and an empty set is the right answer for it.
	 *
	 * @param before the ids contained before the change.
	 * @return the report, or null when nothing moved.
	 */
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
		// Removals first: they are the ones with a consequence the visitor did not ask for.
		removed.forEach(id -> savedRails.add(describeSavedRail(railwayData, depot, id, "removed")));
		added.forEach(id -> savedRails.add(describeSavedRail(railwayData, depot, id, "added")));
		effects.add("savedRails", savedRails);
		effects.addProperty("addedCount", added.size());
		effects.addProperty("removedCount", removed.size());

		// Counted over the routes of the whole world, not just those serving this area: a platform can be
		// called at by any route, and all of them lose the stop.
		effects.addProperty("routeStopsRemoved", countRoutesLosingStops(railwayData, removed));

		// Named so the page does not have to infer which collection to look the ids up in.
		effects.addProperty("savedRailKind", depot ? "siding" : "platform");
		return effects;
	}

	/**
	 * One entry of the saved-rails list: enough to identify it, and no more.
	 *
	 * The page already holds the whole world, so the name is a convenience rather than a necessity - it is
	 * sent because the alternative is the page repeating this lookup, and because the ids a visitor sees in
	 * the report should be readable.
	 */
	private static JsonObject describeSavedRail(RailwayData railwayData, boolean depot, long id, String change) {
		final JsonObject json = new JsonObject();
		WebDashboardJson.addId(json, "id", id);
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

	/**
	 * How many routes lose at least one stop because of the platforms that left the area.
	 *
	 * Counted per route rather than per call: a route that called at the same platform twice would otherwise
	 * report two stops, and the number is meant to answer "how many lines did I just change".
	 */
	private static int countRoutesLosingStops(RailwayData railwayData, Set<Long> removedPlatformIds) {
		if (removedPlatformIds.isEmpty()) {
			return 0;
		}

		int routes = 0;
		for (final Route route : railwayData.routes) {
			for (final long platformId : removedPlatformIds) {
				// The model's own test, so the count cannot disagree with what the cache is about to prune.
				if (route.containsPlatformId(platformId)) {
					routes++;
					break;
				}
			}
		}
		return routes;
	}

	/**
	 * @return the colour, clamped to 24 bits and reported in {@code warnings} when it had to be. Clamped
	 *         rather than refused, matching how the game behaves when a colour is picked.
	 */
	private static int readColor(JsonObject body, List<String> warnings) throws FieldException {
		final int raw = readInt(body, "color");
		if (raw < 0 || raw > 0xFFFFFF) {
			final int clamped = Math.max(0, Math.min(0xFFFFFF, raw));
			warnings.add("color was outside 0..16777215 and was clamped to " + clamped);
			return clamped;
		}
		return raw;
	}

	/** @return the object kinds the write API knows about. */
	static Set<String> knownKinds() {
		return Set.of("station", "route", "depot");
	}

	/** A field was present but unusable. Carries the field name so the response can point at it. */
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
