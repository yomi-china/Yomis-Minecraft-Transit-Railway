package mtr.webdashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mtr.data.IGui;

import java.util.Collection;
import java.util.Map;

/**
 * Small helpers for building the read-only API's JSON.
 * <p>
 * Stage 3.1 hand-builds the JSON rather than annotating the model classes. That is deliberate: the
 * model is persisted to disk as MessagePack and sent over the network, so adding serialisation
 * annotations to it would couple the wire format of the API to the save format of the world. A
 * separate builder can also pick units (see {@link #seconds}) and omit fields the web page has no
 * business seeing.
 */
public final class WebDashboardJson {

	/**
	 * Conversion from the model's dwell/ADC unit to seconds.
	 * <p>
	 * The model stores half-seconds: the in-game screens compute
	 * {@code (int) ((seconds + minutes * 60) * 2)}. Presenting seconds over the API keeps that quirk
	 * out of the front end; the {@code Seconds} suffix on the field names says which unit is in play.
	 */
	private static final double HALF_SECONDS_PER_SECOND = 2D;

	/** Model acceleration is m/tick², and {@code m/s² = value * 400}. Kept as-is on purpose. */
	public static final float ACCELERATION_UNIT_CONVERSION = 20F * 20F;

	private WebDashboardJson() {
	}

	// ---- primitives -------------------------------------------------------

	/**
	 * Adds a long as a JSON <em>string</em>.
	 * <p>
	 * Railway object ids are random longs, routinely far outside the ±2^53 that an IEEE-754 double -
	 * and therefore {@code JSON.parse} in a browser - can represent exactly. Emitting them as numbers
	 * would silently corrupt some ids, producing objects whose id matches nothing on the next write.
	 * Strings keep them opaque and exact, and the front end treats them as map keys anyway.
	 */
	public static void addId(JsonObject object, String key, long id) {
		object.addProperty(key, String.valueOf(id));
	}

	/**
	 * Adds a long as a JSON number. For values that are genuinely small and numeric, such as a
	 * departure offset in milliseconds.
	 */
	public static void addNumber(JsonObject object, String key, Number value) {
		object.add(key, new JsonPrimitive(value));
	}

	/**
	 * @return half-seconds converted to seconds, e.g. 21 half-seconds to 10.5.
	 */
	public static double seconds(int halfSeconds) {
		return halfSeconds / HALF_SECONDS_PER_SECOND;
	}

	/**
	 * A string array from a collection, preserving iteration order.
	 */
	public static JsonArray strings(Collection<String> values) {
		final JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	/**
	 * A string array of ids.
	 */
	public static JsonArray ids(Collection<Long> values) {
		final JsonArray array = new JsonArray();
		values.forEach(value -> array.add(String.valueOf(value)));
		return array;
	}

	/**
	 * A nested object of {@code id -> id -> number}, as used by {@code Depot.platformTimes}.
	 * Returns an empty object rather than null when the source is empty, so the front end never has
	 * to null-check a field that is normally present.
	 * <p>
	 * The keys are <b>saved rail</b> ids, not platform ids. Path generation treats the siding a train
	 * starts from as a pseudo-stop, so a siding id appears here alongside the platform ids. See the
	 * per-depot warning in {@link WebDashboardDataService}; callers must not assume every key resolves
	 * against the platforms list.
	 */
	public static JsonObject nestedTimes(Map<Long, Map<Long, Float>> values) {
		final JsonObject outer = new JsonObject();
		if (values == null) {
			return outer;
		}
		values.forEach((outerId, inner) -> {
			final JsonObject innerObject = new JsonObject();
			if (inner != null) {
				inner.forEach((innerId, seconds) -> innerObject.addProperty(String.valueOf(innerId), seconds));
			}
			outer.add(String.valueOf(outerId), innerObject);
		});
		return outer;
	}

	/**
	 * World coordinates, or null when the area was never drawn.
	 * <p>
	 * The null matters: {@code AreaBase} uses null corners for "not drawn yet", and 0 is a legitimate
	 * coordinate, so substituting a default would turn "no selection" into "a selection at the origin".
	 */
	public static JsonObject corner(Integer x, Integer z) {
		if (x == null || z == null) {
			return null;
		}
		final JsonObject object = new JsonObject();
		object.addProperty("x", x);
		object.addProperty("z", z);
		return object;
	}

	/**
	 * Like {@link #corner} but without the null case, for positions that always exist.
	 */
	public static JsonObject position(int x, int y, int z) {
		final JsonObject object = new JsonObject();
		object.addProperty("x", x);
		object.addProperty("y", y);
		object.addProperty("z", z);
		return object;
	}

	/**
	 * Adds a possibly-null JSON element.
	 * <p>
	 * Always emits the key, with a JSON literal null when the value is absent, rather than omitting it.
	 * The front end can then tell "this station has no selection drawn" apart from "this build of the
	 * server does not report selections", and it never has to distinguish a missing key from a null one.
	 * <p>
	 * Typed as {@link JsonElement} rather than {@link JsonObject} so an id, a coordinate pair or a
	 * number can all be passed through the same null-tolerant path.
	 */
	public static void addNullable(JsonObject object, String key, JsonElement value) {
		object.add(key, value);
	}

	/**
	 * @return the untitled fallback the game itself uses, so the web page and the in-game list agree
	 *         on what an empty name looks like.
	 */
	public static String untitled() {
		return IGui.textOrUntitled("");
	}
}
