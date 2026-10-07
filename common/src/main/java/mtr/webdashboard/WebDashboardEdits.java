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

public final class WebDashboardEdits {

	private WebDashboardEdits() {
	}

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

		public int getStatus() {
			return status;
		}

		public String getError() {
			return error;
		}

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

	public static JsonObject applyPatch(String kind, ResourceLocation packetId, UUID uuid, long objectId, JsonObject body) throws EditFailure {
		if (!WebDashboardFields.knownKinds().contains(kind)) {
			throw new EditFailure(404, "unknown_resource", "No editable resource named '" + kind + "'");
		}
		if (body == null) {
			throw new EditFailure(400, "bad_request", "Expected a JSON object body");
		}
		if (body.size() == 0) {
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

		final List<String> before = describe(object);

		final List<String> warnings = new ArrayList<>();
		final WebDashboardFields.Applied applied;
		try {
			applied = WebDashboardFields.apply(kind, railwayData, object, body, packet -> DataUpdateBroadcast.broadcastDataUpdate(world, packetId, packet, null), warnings);
		} catch (FieldException e) {
			throw new EditFailure(400, "invalid_field", e.getMessage(), e.getField(), WebDashboardFields.acceptedFields(kind));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Failed to apply a " + kind + " edit to " + objectId + ": " + e);
			throw new EditFailure(500, "edit_failed", "The server could not apply that change");
		}

		if (applied.changed.isEmpty()) {
			throw new EditFailure(400, "bad_request", "None of the accepted fields were present: " + String.join(", ", WebDashboardFields.acceptedFields(kind)));
		}

		try {
			railwayData.railwayDataLoggingModule.addEvent(editor, object.getClass(), object.id, object.name, before, describe(object));
		} catch (Exception e) {
			System.out.println("[MTR-WebDashboard] Could not write an audit record for a " + kind + " edit: " + e);
			warnings.add("the change was applied but could not be written to the audit log");
		}

		final JsonObject json = new JsonObject();
		final JsonArray changedArray = new JsonArray();
		applied.changed.forEach(changedArray::add);
		json.add("changed", changedArray);
		final JsonArray warningArray = new JsonArray();
		warnings.forEach(warningArray::add);
		json.add("warnings", warningArray);

		final JsonObject objectJson = new JsonObject();
		objectJson.addProperty("id", String.valueOf(object.id));
		objectJson.addProperty("name", object.name);
		objectJson.addProperty("color", object.color);
		objectJson.addProperty("transportMode", object.transportMode.toString());
		if (object instanceof Station station) {
			objectJson.addProperty("zone", station.zone);
			final boolean hasArea = station.corner1 != null && station.corner2 != null;
			objectJson.addProperty("hasArea", hasArea);
			objectJson.add("corner1", hasArea ? WebDashboardDataService.corner(station.corner1.getA(), station.corner1.getB()) : null);
			objectJson.add("corner2", hasArea ? WebDashboardDataService.corner(station.corner2.getA(), station.corner2.getB()) : null);
		}
		json.add("object", objectJson);

		if (applied.effects != null) {
			json.add("effects", applied.effects);
		}
		return json;
	}

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
