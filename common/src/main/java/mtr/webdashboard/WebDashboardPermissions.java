package mtr.webdashboard;

import mtr.webdashboard.WebDashboardSettings.PermissionEntryView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WebDashboardPermissions {

	public static final int REQUIRED_OP_LEVEL = 4;

	private WebDashboardPermissions() {
	}

	public static ServerPlayer getOnlinePlayer(UUID uuid) {
		final MinecraftServer server = WebDashboardRuntime.getServer();
		return server == null || uuid == null ? null : server.getPlayerList().getPlayer(uuid);
	}

	public static boolean canEdit(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		final Boolean explicit = WebDashboardSettings.get().getPermissionEntry(player.getUUID());
		if (explicit != null) {
			return explicit;
		}
		return player.hasPermissions(REQUIRED_OP_LEVEL);
	}

	public static boolean canEdit(UUID uuid) {
		if (uuid == null) {
			return false;
		}
		final Boolean explicit = WebDashboardSettings.get().getPermissionEntry(uuid);
		if (explicit != null) {
			return explicit;
		}
		final ServerPlayer player = getOnlinePlayer(uuid);
		return player != null && player.hasPermissions(REQUIRED_OP_LEVEL);
	}

	public static boolean setPermission(UUID uuid, boolean granted, String name) {
		final WebDashboardSettings settings = WebDashboardSettings.get();
		settings.setPermissionEntry(uuid, granted, name);
		log("Set " + (name == null || name.isEmpty() ? uuid.toString() : name + " (" + uuid + ")") + " to " + (granted ? "ON" : "OFF"));
		return settings.save();
	}

	public static boolean reset(UUID uuid) {
		final WebDashboardSettings settings = WebDashboardSettings.get();
		if (!settings.removePermissionEntry(uuid)) {
			return false;
		}
		log("Removed the explicit entry for " + uuid + ", it now follows the op-level rule");
		settings.save();
		return true;
	}

	public static void refreshName(ServerPlayer player) {
		if (player == null) {
			return;
		}
		final WebDashboardSettings settings = WebDashboardSettings.get();
		if (!settings.hasPermissionEntry(player.getUUID())) {
			return;
		}
		if (settings.updatePermissionName(player.getUUID(), player.getName().getString())) {
			settings.save();
		}
	}

	public static boolean hasAnyExplicitGrant() {
		for (final PermissionEntryView entry : WebDashboardSettings.get().listPermissionEntries()) {
			if (entry.granted()) {
				return true;
			}
		}
		return false;
	}

	public static String describeTable() {
		final List<PermissionEntryView> entries = WebDashboardSettings.get().listPermissionEntries();
		int granted = 0;
		for (final PermissionEntryView entry : entries) {
			if (entry.granted()) {
				granted++;
			}
		}
		return entries.size() + " entries (" + granted + " on, " + (entries.size() - granted) + " off)";
	}

	public static List<UUID> getGrantedUuids() {
		final List<UUID> uuids = new ArrayList<>();
		for (final PermissionEntryView entry : WebDashboardSettings.get().listPermissionEntries()) {
			if (entry.granted()) {
				uuids.add(entry.uuid());
			}
		}
		return uuids;
	}

	private static void log(String message) {
		System.out.println("[MTR-WebDashboard] " + message);
	}
}
