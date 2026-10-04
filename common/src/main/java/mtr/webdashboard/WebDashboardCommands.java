package mtr.webdashboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import mtr.mappings.Text;
import mtr.webdashboard.WebDashboardSettings.PermissionEntryView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The {@code /mtr-webdashboard} command tree: who may edit through the web dashboard.
 * <p>
 * The root gate is <b>op level</b>, not the dashboard's own permission, for a specific reason: the
 * command exists to change that permission, so requiring it would deadlock anyone who revoked their
 * own access. See {@link #mayAdministrate} for the full reasoning.
 * <p>
 * Separate from the in-game dashboards' permission model, which is a gamemode check in
 * {@code RailwayData} and is left untouched.
 * <p>
 * Every message is a translation key. Note that {@code CommandSourceStack} has exactly one
 * successful-feedback method - {@code sendSuccess(Supplier&lt;Component&gt;, boolean)} - and
 * {@link net.minecraft.network.chat.Component} does <em>not</em> implement {@code Supplier}. So even
 * a single-line message has to be wrapped in a lambda; passing a component directly does not
 * compile. Only {@code sendFailure(Component)} takes a component as-is.
 */
public final class WebDashboardCommands {

	private static final String ROOT = "mtr-webdashboard";

	/**
	 * The op level needed to run the permission commands. Matches the level vanilla uses for its own
	 * player-management commands ({@code /op} needs 3), so anyone who can already manage operators can
	 * manage dashboard access - and so the singleplayer host, who holds level 4, always can.
	 */
	private static final int REQUIRED_OP_LEVEL = 2;
	private static final String ARGUMENT_PLAYER = "player";

	private WebDashboardCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Note what is deliberately absent: `.requires(...)`.
		//
		// Brigadier evaluates `requires` while building the command tree and hides anything that fails,
		// so a requirement anywhere on this tree makes the command vanish from tab completion for
		// anyone who fails it - `/mtr-webdashboard` answers "unknown command" rather than "you may not
		// do that". That is exactly what turned a recoverable lockout in this project into an
		// unexplainable one. The gate is applied at execution instead, which keeps the command
		// discoverable and lets it say why it refused.
		dispatcher.register(literal(ROOT)
				.then(literal("grant")
						.then(argument(ARGUMENT_PLAYER, StringArgumentType.word())
								.suggests(WebDashboardCommands::suggestOnlinePlayers)
								.executes(context -> apply(context, true, false))
								.then(literal("on").executes(context -> apply(context, true, false)))
								.then(literal("off").executes(context -> apply(context, false, false)))))
				.then(literal("revoke")
						.then(argument(ARGUMENT_PLAYER, StringArgumentType.word())
								.suggests(WebDashboardCommands::suggestOnlinePlayers)
								.executes(context -> apply(context, false, false))))
				.then(literal("reset")
						.then(argument(ARGUMENT_PLAYER, StringArgumentType.word())
								.suggests(WebDashboardCommands::suggestOnlinePlayers)
								.executes(context -> apply(context, false, true))))
				.then(literal("status").executes(WebDashboardCommands::status))
				.then(literal("list").executes(WebDashboardCommands::list)));
	}

	/**
	 * @return true when the gate passed. When it did not, the refusal has already been sent, and the
	 *         caller must return without doing anything.
	 */
	private static boolean checkGate(CommandSourceStack source) {
		if (mayAdministrate(source)) {
			return true;
		}
		source.sendFailure(Text.translatable("gui.mtr.web_dashboard_command_not_permitted", REQUIRED_OP_LEVEL));
		return false;
	}

	/**
	 * The command-level gate: who may run the permission commands at all, and on whom.
	 * <p>
	 * Deliberately <b>not</b> the same test as "may edit the dashboard through the web". An earlier
	 * version used that, and it deadlocked in the most likely way possible: revoking your own access
	 * removed the permission that the command needs to restore it. Worse, Brigadier evaluates
	 * {@code requires} when building the command tree, so the command disappeared from tab completion
	 * entirely - it did not even report "you lack permission".
	 * <p>
	 * The rule is therefore "can this person already administer the server", never "can they edit the
	 * dashboard":
	 *
	 * <ul>
	 *     <li><b>Console</b> passes. A source with no player attached is the server terminal or the
	 *     singleplayer world's own source, and must always be able to restore access.</li>
	 *     <li><b>The singleplayer owner</b> passes by uuid. This one matters: with cheats turned off in a
	 *     singleplayer world, {@code CommandSourceStack.hasPermission} reports 0 for everyone -
	 *     including the host - so an op-level test would leave the host with no in-game way back. The
	 *     host owns the world; there is nothing to protect from them.</li>
	 *     <li><b>Anyone at op level 2</b> passes, matching the level vanilla's own player-management
	 *     commands use.</li>
	 * </ul>
	 *
	 * A player who was granted web access but is neither the host nor opped cannot hand it on. That is
	 * a deliberate trade, and it is the only thing keeping the recovery property above true. If
	 * delegated administration is wanted instead, this method is the one place to change.
	 */
	private static boolean mayAdministrate(CommandSourceStack source) {
		// No player means the console (or a command block): always allowed.
		final ServerPlayer player = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;
		if (player == null) {
			return true;
		}

		final MinecraftServer server = source.getServer();
		if (server != null && server.isSingleplayerOwner(player.getGameProfile())) {
			return true;
		}

		return source.hasPermission(REQUIRED_OP_LEVEL);
	}

	// ---- grant / revoke / reset -------------------------------------------

	/**
	 * Shared body of grant, revoke and reset.
	 *
	 * @param granted the value to record, ignored when {@code isReset}.
	 * @param isReset true to remove the explicit entry so the player follows the op-level rule again,
	 *                rather than recording an explicit denial.
	 */
	private static int apply(CommandContext<CommandSourceStack> context, boolean granted, boolean isReset) {
		final CommandSourceStack source = context.getSource();
		final String name = StringArgumentType.getString(context, ARGUMENT_PLAYER);
		final Target target = resolveTarget(source.getServer(), name);
		// The gate is here rather than in `requires`, so that an unauthorised caller gets a reason
		// instead of the command silently not existing. See register().
		if (!checkGate(source)) {
			return 0;
		}

		if (isReset) {
			if (!WebDashboardPermissions.reset(target.uuid)) {
				source.sendFailure(Text.translatable("gui.mtr.web_dashboard_command_no_entry", target.displayName));
				return 0;
			}

			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_command_reset", target.displayName), true);
			warnIfNobodyCanEdit(source);
			return 1;
		}

		WebDashboardPermissions.setPermission(target.uuid, granted, target.onlineName);
		source.sendSuccess(Text.translatable(granted ? "gui.mtr.web_dashboard_command_granted" : "gui.mtr.web_dashboard_command_revoked", target.displayName), true);

		// Told explicitly, because the same command means something different for an offline player on a
		// server that later turns on authentication.
		if (!target.online) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_command_offline_note", target.displayName), false);
		}

		if (!granted) {
			// Sign them out now rather than letting their open tab keep working until its next request.
			final int endedSessions = WebDashboardTokenStore.revokePlayer(target.uuid);
			if (endedSessions > 0) {
				source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_command_sessions_ended", endedSessions), false);
			}
			warnIfNobodyCanEdit(source);
		} else if (target.online) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_command_tells_player"), false);
		}

		return 1;
	}

	// ---- status -----------------------------------------------------------

	private static int status(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();
		final MinecraftServer server = source.getServer();
		final boolean running = WebDashboardRuntime.isRunning();
		final int port = WebDashboardServer.getPort();
		final boolean lanAccess = WebDashboardSettings.get().isAllowLanAccess();

		// status is readable by anyone, so it reports service facts only - no player names, no UUIDs.
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_header"), false);
		// The state words are translated separately and appended, rather than passed into the sentence
		// as an argument: a translation key with no placeholder reads as dead code and some tooling
		// flags it. Vanilla composes its list output the same way.
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_service", port)
				.append(Text.translatable(running ? "gui.mtr.web_dashboard_state_listening" : "gui.mtr.web_dashboard_state_not_listening")), false);
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_bind", WebDashboardSettings.get().getBindHost())
				.append(Text.translatable(lanAccess ? "gui.mtr.web_dashboard_state_on" : "gui.mtr.web_dashboard_state_off")), false);
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_access", WebDashboardPermissions.describeTable(), WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_signins", WebDashboardTokenStore.getSessionCount(), WebDashboardTokenStore.getPendingLoginTokenCount()), false);
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_tokens", WebDashboardSettings.get().getLoginTokenTtlSeconds(), WebDashboardSettings.get().getSessionTtlHours()), false);
		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_target", WebDashboardServer.getUrl()), false);
		if (server != null) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_players", server.getPlayerList().getPlayerCount()), false);
		}

		if (lanAccess) {
			// Worth repeating here: this is the setting that puts sign-in tokens on the wire in clear text.
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_status_lan_warning"), false);
		}
		return 1;
	}

	// ---- list -------------------------------------------------------------

	private static int list(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();
		if (!checkGate(source)) {
			return 0;
		}
		final List<PermissionEntryView> entries = WebDashboardSettings.get().listPermissionEntries();

		source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_list_header", WebDashboardPermissions.describeTable()), false);

		if (entries.isEmpty()) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_list_empty", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		} else {
			for (final PermissionEntryView entry : entries) {
				final boolean online = WebDashboardPermissions.getOnlinePlayer(entry.uuid) != null;
				// The uuid is shown alongside the name because one name can map to more than one uuid:
				// a Mojang account and the offline uuid derived from the same name are different keys, and
				// an entry written for one does nothing for the other. Without this, a list can contain two
				// lines that look identical and behave completely differently - which is exactly how a
				// lockout was misdiagnosed once already.
				source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_list_entry", entry.getDisplayName(), shortUuid(entry.uuid))
						.append(Text.translatable(entry.granted ? "gui.mtr.web_dashboard_state_granted" : "gui.mtr.web_dashboard_state_denied"))
						.append(Text.translatable(online ? "gui.mtr.web_dashboard_state_online" : "gui.mtr.web_dashboard_state_offline")), false);
			}
		}

		final List<String> usernames = WebDashboardTokenStore.getSessionUsernames();
		if (!usernames.isEmpty()) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_list_sessions", String.join(", ", usernames)), false);
		}
		return 1;
	}

	// ---- helpers ----------------------------------------------------------

	/**
	 * @return the first eight characters of a uuid, which is plenty to tell two entries apart on screen
	 *         while staying short enough to sit in a command line.
	 */
	private static String shortUuid(UUID uuid) {
		final String text = uuid == null ? "" : uuid.toString();
		return text.length() <= 8 ? text : text.substring(0, 8);
	}

	/**
	 * Warns when the change leaves no explicit grant at all.
	 * <p>
	 * A warning rather than a refusal: players who qualify purely by op level are invisible to this
	 * count, so blocking would be both wrong and infuriating. The message says as much.
	 */
	private static void warnIfNobodyCanEdit(CommandSourceStack source) {
		if (!WebDashboardPermissions.hasAnyExplicitGrant()) {
			source.sendSuccess(Text.translatable("gui.mtr.web_dashboard_command_no_grants", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		}
	}

	/**
	 * Turns the typed name into a UUID.
	 * <p>
	 * Online players resolve directly. Anyone else is treated as offline and given the vanilla offline
	 * UUID, so the console can grant access to someone who has not connected yet.
	 */
	private static Target resolveTarget(MinecraftServer server, String name) {
		final ServerPlayer online = server == null ? null : server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			final String username = online.getName().getString();
			return new Target(online.getUUID(), username, true, username);
		}

		// The exact derivation vanilla uses for offline players: name-based, so it matches whatever UUID
		// the server hands out when that name connects in offline mode.
		final UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		return new Target(offlineUuid, name, false, name);
	}

	private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		final MinecraftServer server = context.getSource().getServer();
		if (server != null) {
			for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
				builder.suggest(player.getName().getString());
			}
		}
		return builder.buildFuture();
	}

	/** Resolved command target, online or offline. */
	private static final class Target {

		private final UUID uuid;
		private final String displayName;
		private final boolean online;
		/** The player's real name when online, otherwise the typed name. */
		private final String onlineName;

		private Target(UUID uuid, String displayName, boolean online, String onlineName) {
			this.uuid = uuid;
			this.displayName = displayName;
			this.online = online;
			this.onlineName = onlineName;
		}
	}
}
