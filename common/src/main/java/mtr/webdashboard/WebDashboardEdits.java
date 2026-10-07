package mtr.webdashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mtr.data.NameColorDataBase;
import mtr.data.RailwayData;
import mtr.data.Station;
import mtr.packet.DataUpdateBroadcast;
import mtr.webdashboard.WebDashboardFields.FieldException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Applies one patch, whichever kind of object it touches: permission, editor, validation, the model's own
 * setter, broadcast, cache refresh, audit record and response. Field-specific work is in
 * {@link WebDashboardFields}.
 *
 * Fields are written through the model's own setters rather than by replaying
 * {@code PacketTrainDataGuiServer.receiveUpdateOrDeleteC2S}, whose packet read order would have to be
 * hand-matched and whose mistakes would corrupt unrelated fields. The resulting bytes on the wire are
 * identical either way.
 *
 * An online editor is required because {@code RailwayDataLoggingModule.addEvent} takes a
 * {@code ServerPlayer} and an unattributed edit is not acceptable; that player's world is also the only
 * world a session cookie can name. An offline editor gets a specific error rather than a silent failure.
 */
public final class WebDashboardEdits {

	/** Not instantiable: a namespace for one entry point. */
	private WebDashboardEdits() {
	}

	/**
	 * A refusal, carrying the HTTP status and the error code the page reports.
	 *
	 * Public so the servlet package can catch it and read the three things needed for a response. The
	 * constructors stay package-private: the servlet may translate a refusal but never invent one.
	 */
	public static final class EditFailure extends Exception {

		private final int status;
		private final String error;
		private final String field;
		private final List<String> accepted;

		EditFailure(int status, String error, String message) {
			this(status, error, message, null, null);
		}

		EditFailure(int status, String error, String message, String field, List<String> accepted) {
			super(message);
			this.status = status;
			this.error = error;
			this.field = field;
			this.accepted = accepted;
		}

		/** @return the HTTP status to answer with. */
		public int getStatus() {
			return status;
		}

		/** @return the short machine-readable code, e.g. {@code web_editor_offline}. */
		public String getError() {
			return error;
		}

		/** @return this refusal as the error body the page parses. */
		public JsonObject toJson() {
			final JsonObject json = new JsonObject();
			json.addProperty("error", error);
			json.addProperty("message", getMessage());
			if (field != null) {
				json.addProperty("field", field);
			}
			if (accepted != null) {
				final JsonArray array = new JsonArray();
				accepted.forEach(array::add);
				json.add("acceptedFields", array);
			}
			return json;
		}
	}

	/**
	 * Applies a patch to one object.
	 * <p>
	 * <b>Must be called on the game thread.</b> It reads and mutates {@code RailwayData}, whose collections
	 * are plain maps mutated by the server thread, and it broadcasts to clients.
	 *
	 * @param kind     'station' | 'route' | 'depot'.
	 * @param packetId the packet clients expect for this kind of change.
	 * @param uuid     the editor's account id, from the session cookie.
	 * @param body     the request body: only the fields to change.
	 * @return the response body.
	 * @throws EditFailure for every refusal, so the caller translates one exception type.
	 */
	public static JsonObject applyPatch(String kind, ResourceLocation packetId, UUID uuid, long objectId, JsonObject body) throws EditFailure {
		if (!WebDashboardFields.knownKinds().contains(kind)) {
			throw new EditFailure(404, "unknown_resource", "No editable resource named '" + kind + "'");
		}
		if (body == null) {
			throw new EditFailure(400, "bad_request", "Expected a JSON object body");
		}
		if (body.size() == 0) {
			// size() == 0 rather than isEmpty(): JsonObject only gained isEmpty in Gson 2.10.1, and this build
			// resolves 2.10.
			throw new EditFailure(400, "bad_request", "No fields to change");
		}

		final MinecraftServer server = WebDashboardRuntime.getServer();
		if (server == null) {
			throw new EditFailure(503, "no_server", "No server is running");
		}

		final ServerPlayer editor = WebDashboardPermissions.getOnlinePlayer(uuid);
		if (editor == null) {
			throw new EditFailure(503, "web_editor_offline", "The account that signed in is no longer in the game, so this change cannot be recorded");
		}

		final Level world = editor.level();
		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData == null) {
			throw new EditFailure(503, "no_server", "This world has no railway data loaded");
		}

		final NameColorDataBase object = WebDashboardFields.resolve(kind, railwayData, objectId);
		if (object == null) {
			throw new EditFailure(404, "not_found", "No " + kind + " with that id exists in this world");
		}

		// Captured before the change, for the audit trail.
		final List<String> before = describe(object);

		final List<String> warnings = new ArrayList<>();
		final WebDashboardFields.Applied applied;
		try {
			applied = WebDashboardFields.apply(kind, railwayData, object, body, packet -> DataUpdateBroadcast.broadcastDataUpdate(world, packetId, packet, null), warnings);
		} catch (FieldException e) {
			throw new EditFailure(400, "invalid_field", e.getMessage(), e.getField(), WebDashboardFields.acceptedFields(kind));
		} catch (Exception e) {
			// A setter threw, which the model's setters are not expected to do.
			System.out.println("[MTR-WebDashboard] Failed to apply a " + kind + " edit to " + objectId + ": " + e);
			throw new EditFailure(500, "edit_failed", "The server could not apply that change");
		}

		if (applied.changed.isEmpty()) {
			throw new EditFailure(400, "bad_request", "None of the accepted fields were present: " + String.join(", ", WebDashboardFields.acceptedFields(kind)));
		}

		// After the broadcast, so a throw during logging cannot leave that half-done.
		try {
			railwayData.railwayDataLoggingModule.addEvent(editor, object.getClass(), object.id, object.name, before, describe(object));
		} catch (Exception e) {
			// Non-fatal: the edit succeeded and has been broadcast, so failing the request now would tell the
			// page the change did not happen when it did.
			System.out.println("[MTR-WebDashboard] Could not write an audit record for a " + kind + " edit: " + e);
			warnings.add("the change was applied but could not be written to the audit log");
		}

		return buildResponse(kind, object, applied, warnings);
	}

	/**
	 * Builds the success body, including the edited object so the page can update its row without refetching
	 * the whole world.
	 *
	 * No {@code dataRevision}: that is a fingerprint of every world's data, computed by {@code /api/data}
	 * while it builds its payload. Producing it here would mean re-hashing the whole model on every rename,
	 * or inventing a second differently-scoped revision for the page to confuse with the first.
	 *
	 * {@code effects} is present only when the edit moved something the visitor did not name - a selection
	 * change that orphaned saved rails. It is omitted rather than sent empty, so the page can treat its
	 * presence as the signal that there is something to say.
	 */
	private static JsonObject buildResponse(String kind, NameColorDataBase object, WebDashboardFields.Applied applied, List<String> warnings) {
		final JsonObject json = new JsonObject();

		final JsonArray changedArray = new JsonArray();
		applied.changed.forEach(changedArray::add);
		json.add("changed", changedArray);

		final JsonArray warningArray = new JsonArray();
		warnings.forEach(warningArray::add);
		json.add("warnings", warningArray);

		// Re-serialised from the live object, so the page sees the server's post-clamp values.
		json.add("object", describeJson(kind, object));

		if (applied.effects != null) {
			json.add("effects", applied.effects);
		}
		return json;
	}

	/**
	 * The object as JSON. Only the fields this stage can change, beyond the identity: building the full
	 * per-kind serialisation here would duplicate {@link WebDashboardDataService}.
	 */
	private static JsonObject describeJson(String kind, NameColorDataBase object) {
		final JsonObject json = new JsonObject();
		WebDashboardJson.addId(json, "id", object.id);
		json.addProperty("name", object.name);
		json.addProperty("color", object.color);
		json.addProperty("transportMode", object.transportMode.toString());
		if (object instanceof Station station) {
			json.addProperty("zone", station.zone);
			final boolean hasArea = station.corner1 != null && station.corner2 != null;
			json.addProperty("hasArea", hasArea);
			// net.minecraft.util.Tuple uses getA()/getB(), not Guava's getFirst()/getSecond().
			WebDashboardJson.addNullable(json, "corner1", hasArea ? WebDashboardJson.corner(station.corner1.getA(), station.corner1.getB()) : null);
			WebDashboardJson.addNullable(json, "corner2", hasArea ? WebDashboardJson.corner(station.corner2.getA(), station.corner2.getB()) : null);
		}
		return json;
	}

	/** A flat description of the object, in the shape the audit log stores. */
	private static List<String> describe(NameColorDataBase object) {
		final List<String> lines = new ArrayList<>();
		lines.add("id: " + object.id);
		lines.add("name: " + object.name);
		lines.add("color: " + object.color);
		if (object instanceof Station station) {
			lines.add("zone: " + station.zone);
		}
		return lines;
	}
}
