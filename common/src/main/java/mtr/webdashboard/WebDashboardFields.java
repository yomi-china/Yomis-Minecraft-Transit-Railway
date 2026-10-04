package mtr.webdashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mtr.data.NameColorDataBase;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
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

	/** The fields every {@link NameColorDataBase} accepts, which is every resource in this stage. */
	private static final List<String> NAME_COLOR_FIELDS = List.of("name", "color");

	/** Name, colour and zone: what a station accepts beyond the common pair. */
	private static final List<String> STATION_FIELDS = List.of("name", "color", "zone");

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
		return "station".equals(kind) ? STATION_FIELDS : NAME_COLOR_FIELDS;
	}

	// ---- applying -----------------------------------------------------------

	/**
	 * Writes the request's fields onto the object.
	 *
	 * @param sendPacket receives the packet the model builds; the caller broadcasts it.
	 * @param warnings   collects notes about values that had to be adjusted.
	 * @return the names of the fields written, in field-table order.
	 * @throws FieldException when a field is present but malformed, or is not one this build accepts.
	 */
	static List<String> apply(String kind, NameColorDataBase object, JsonObject body, Consumer<FriendlyByteBuf> sendPacket, List<String> warnings) throws FieldException {
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

		return changed;
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
