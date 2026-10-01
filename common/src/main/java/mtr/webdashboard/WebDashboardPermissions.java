package mtr.webdashboard;

import mtr.webdashboard.WebDashboardSettings.PermissionEntryView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The single source of truth for "may this player edit through the web dashboard".
 * <p>
 * Two layers, in this order:
 * <ol>
 *     <li>An explicit entry in the config's {@code permissions} table wins outright, whether it says
 *         {@code true} or {@code false}. This is what makes revoking access from an operator
 *         possible at all - deleting an entry would only fall back to the op-level rule below.</li>
 *     <li>With no entry, the player's current op level decides. Evaluated on every check rather than
 *         cached at sign-in, so {@code /op} and {@code /deop} take effect on the next request with
 *         no re-login.</li>
 * </ol>
 * An explicit {@code false} therefore means "denied even if opped", and {@link #reset} is how an
 * operator removes that denial.
 * <p>
 * Deliberately independent of {@code RailwayData.hasPermission}, which is a gamemode check
 * (creative or survival) used by the in-game dashboards. Web access is a separate grant and must not
 * change in-game behaviour, or be changed by it.
 */
public final class WebDashboardPermissions {

	/** Vanilla's top level: what {@code /op} grants and what a level-4 command requires. */
	public static final int REQUIRED_OP_LEVEL = 4;

	/**
	 * How to reach the running server, needed because the op-level layer can only be evaluated for a
	 * player who is online.
	 * <p>
	 * Web request threads hold only a UUID from a cookie, so without this they could not consult op
	 * level at all. Set from the server-starting hook; null before the server exists and after it
	 * stops, in which case op-based access is denied and only explicit grants still work.
	 */
	private static volatile Supplier<MinecraftServer> serverSupplier = () -> null;

	private WebDashboardPermissions() {
	}

	public static void setServerSupplier(Supplier<MinecraftServer> supplier) {
		serverSupplier = supplier == null ? () -> null : supplier;
	}

	/**
	 * @return the online player for this UUID, or null when they are offline or no server is running.
	 */
	public static ServerPlayer getOnlinePlayer(UUID uuid) {
		final MinecraftServer server = serverSupplier.get();
		return server == null || uuid == null ? null : server.getPlayerList().getPlayer(uuid);
	}

	/**
	 * @return true when this player may edit through the web dashboard.
	 */
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

	/**
	 * The UUID-only form, used by the HTTP layer where no {@link ServerPlayer} is in hand.
	 * <p>
	 * An explicit entry is honoured whether or not the player is online, so a grant does not go
	 * dormant while its owner is disconnected. The op-level fallback needs the player to be online,
	 * which is the same situation as vanilla: op level is a property of the connected session.
	 *
	 * @return true when this player may edit through the web dashboard.
	 */
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

	/**
	 * @return the explicit entry, or null when this player is governed by the op-level rule. Used by
	 *         command output to explain <em>why</em> someone has or lacks access.
	 */
	public static Boolean getExplicitPermission(UUID uuid) {
		return WebDashboardSettings.get().getPermissionEntry(uuid);
	}

	public static boolean hasExplicitPermission(UUID uuid) {
		return WebDashboardSettings.get().hasPermissionEntry(uuid);
	}

	/**
	 * Records an explicit grant or denial and writes the config.
	 *
	 * @param name the player's name, recorded so operators can read the file and command output
	 *             without a UUID lookup. May be empty for a player who has never been seen.
	 * @return true when the file was written.
	 */
	public static boolean setPermission(UUID uuid, boolean granted, String name) {
		final WebDashboardSettings settings = WebDashboardSettings.get();
		settings.setPermissionEntry(uuid, granted, name);
		log("Set " + describe(uuid, name) + " to " + (granted ? "ON" : "OFF"));
		return settings.save();
	}

	/**
	 * Removes an explicit entry so the player falls back to the op-level rule. Distinct from
	 * {@link #setPermission} with false, which denies even an operator.
	 *
	 * @return true when an entry existed and the config was written.
	 */
	public static boolean reset(UUID uuid) {
		final WebDashboardSettings settings = WebDashboardSettings.get();
		if (!settings.removePermissionEntry(uuid)) {
			return false;
		}
		log("Removed the explicit entry for " + uuid + ", it now follows the op-level rule");
		settings.save();
		return true;
	}

	/**
	 * Refreshes the recorded name when a player joins, so {@code /mtr-webdashboard list} shows
	 * something readable for someone who was granted access before ever connecting.
	 * <p>
	 * Only writes when the name actually changed, to avoid a file write on every join.
	 */
	public static void refreshName(ServerPlayer player) {
		if (player == null) {
			return;
		}
		final WebDashboardSettings settings = WebDashboardSettings.get();
		if (settings.updatePermissionName(player.getUUID(), player.getName().getString())) {
			settings.save();
		}
	}

	/**
	 * @return true when at least one explicit entry grants access. A player who qualifies purely by
	 *         op level is not counted, because their access cannot be listed from this side - the
	 *         command output says so explicitly to avoid a misleading "nobody" answer.
	 */
	public static boolean hasAnyExplicitGrant() {
		for (final PermissionEntryView entry : WebDashboardSettings.get().listPermissionEntries()) {
			if (entry.granted) {
				return true;
			}
		}
		return false;
	}

	public static int getExplicitGrantCount() {
		int count = 0;
		for (final PermissionEntryView entry : WebDashboardSettings.get().listPermissionEntries()) {
			if (entry.granted) {
				count++;
			}
		}
		return count;
	}

	/**
	 * @return a one-line summary of the permission table for {@code status}, e.g.
	 *         {@code "3 entries (2 on, 1 off)"}.
	 */
	public static String describeTable() {
		final List<PermissionEntryView> entries = WebDashboardSettings.get().listPermissionEntries();
		int granted = 0;
		for (final PermissionEntryView entry : entries) {
			if (entry.granted) {
				granted++;
			}
		}
		return entries.size() + " entries (" + granted + " on, " + (entries.size() - granted) + " off)";
	}

	/**
	 * @return the UUIDs holding an explicit grant, for revoking their live sessions.
	 */
	public static List<UUID> getGrantedUuids() {
		final List<UUID> uuids = new ArrayList<>();
		for (final PermissionEntryView entry : WebDashboardSettings.get().listPermissionEntries()) {
			if (entry.granted) {
				uuids.add(entry.uuid);
			}
		}
		return uuids;
	}

	private static String describe(UUID uuid, String name) {
		return name == null || name.isEmpty() ? uuid.toString() : name + " (" + uuid + ")";
	}

	private static void log(String message) {
		System.out.println("[MTR-WebDashboard] " + message);
	}
}
