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
 * Access to the command is granted to operators and console at the root, and the subcommands then
 * check {@link WebDashboardPermissions}, so an ordinary player who was granted web access can pass
 * it on without being an operator. That mirrors how the dashboard itself works - web access is its
 * own grant, not a side effect of being opped.
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
	private static final String ARGUMENT_PLAYER = "player";

	private WebDashboardCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal(ROOT)
				.requires(WebDashboardCommands::mayAdministrate)
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
	 * The command-level gate.
	 * <p>
	 * Console passes: an operator at the server terminal must always be able to restore access, which
	 * is the documented recovery path for locking yourself out.
	 * <p>
	 * A player passes when they may edit the dashboard. This uses the same function as the HTTP layer,
	 * so "can edit on the web" and "can administer web access" cannot drift apart.
	 */
	private static boolean mayAdministrate(CommandSourceStack source) {
		final ServerPlayer player = source.getPlayer();
		return player == null || WebDashboardPermissions.canEdit(player);
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

		if (isReset) {
			if (!WebDashboardPermissions.reset(target.uuid)) {
				source.sendFailure(Text.translatable("gui.mtr.web_dashboard_command_no_entry", target.displayName));
				return 0;
			}

			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_reset", target.displayName), true);
			warnIfNobodyCanEdit(source);
			return 1;
		}

		WebDashboardPermissions.setPermission(target.uuid, granted, target.onlineName);
		source.sendSuccess(() -> Text.translatable(granted ? "gui.mtr.web_dashboard_command_granted" : "gui.mtr.web_dashboard_command_revoked", target.displayName), true);

		// Told explicitly, because the same command means something different for an offline player on a
		// server that later turns on authentication.
		if (!target.online) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_offline_note", target.displayName), false);
		}

		if (!granted) {
			// Sign them out now rather than letting their open tab keep working until its next request.
			final int endedSessions = WebDashboardTokenStore.revokePlayer(target.uuid);
			if (endedSessions > 0) {
				source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_sessions_ended", endedSessions), false);
			}
			warnIfNobodyCanEdit(source);
		} else if (target.online) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_tells_player"), false);
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
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_header"), false);
		// The state words are translated separately and appended, rather than passed into the sentence
		// as an argument: a translation key with no placeholder reads as dead code and some tooling
		// flags it. Vanilla composes its list output the same way.
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_service", port)
				.append(Text.translatable(running ? "gui.mtr.web_dashboard_state_listening" : "gui.mtr.web_dashboard_state_not_listening")), false);
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_bind", WebDashboardSettings.get().getBindHost())
				.append(Text.translatable(lanAccess ? "gui.mtr.web_dashboard_state_on" : "gui.mtr.web_dashboard_state_off")), false);
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_access", WebDashboardPermissions.describeTable(), WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_signins", WebDashboardTokenStore.getSessionCount(), WebDashboardTokenStore.getPendingLoginTokenCount()), false);
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_tokens", WebDashboardSettings.get().getLoginTokenTtlSeconds(), WebDashboardSettings.get().getSessionTtlHours()), false);
		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_target", WebDashboardServer.getUrl()), false);
		if (server != null) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_players", server.getPlayerList().getPlayerCount()), false);
		}

		if (lanAccess) {
			// Worth repeating here: this is the setting that puts sign-in tokens on the wire in clear text.
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_lan_warning"), false);
		}
		return 1;
	}

	// ---- list -------------------------------------------------------------

	private static int list(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();
		final List<PermissionEntryView> entries = WebDashboardSettings.get().listPermissionEntries();

		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_header", WebDashboardPermissions.describeTable()), false);

		if (entries.isEmpty()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_empty", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		} else {
			for (final PermissionEntryView entry : entries) {
				final boolean online = WebDashboardPermissions.getOnlinePlayer(entry.uuid) != null;
				source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_entry", entry.getDisplayName())
						.append(Text.translatable(entry.granted ? "gui.mtr.web_dashboard_state_granted" : "gui.mtr.web_dashboard_state_denied"))
						.append(Text.translatable(online ? "gui.mtr.web_dashboard_state_online" : "gui.mtr.web_dashboard_state_offline")), false);
			}
		}

		final List<String> usernames = WebDashboardTokenStore.getSessionUsernames();
		if (!usernames.isEmpty()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_sessions", String.join(", ", usernames)), false);
		}
		return 1;
	}

	// ---- helpers ----------------------------------------------------------

	/**
	 * Warns when the change leaves no explicit grant at all.
	 * <p>
	 * A warning rather than a refusal: players who qualify purely by op level are invisible to this
	 * count, so blocking would be both wrong and infuriating. The message says as much.
	 */
	private static void warnIfNobodyCanEdit(CommandSourceStack source) {
		if (!WebDashboardPermissions.hasAnyExplicitGrant()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_no_grants", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
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
