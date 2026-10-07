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

public final class WebDashboardCommands {

	private static final String ROOT = "mtr-webdashboard";

	private static final int REQUIRED_OP_LEVEL = 2;
	private static final String ARGUMENT_PLAYER = "player";

	private WebDashboardCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
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

	private static boolean checkGate(CommandSourceStack source) {
		final ServerPlayer player = source.getPlayer();
		final boolean allowed = player == null
				|| (source.getServer() != null && source.getServer().isSingleplayerOwner(player.getGameProfile()))
				|| source.hasPermission(REQUIRED_OP_LEVEL);
		if (allowed) {
			return true;
		}
		source.sendFailure(Text.translatable("gui.mtr.web_dashboard_command_not_permitted", REQUIRED_OP_LEVEL));
		return false;
	}

	private static int apply(CommandContext<CommandSourceStack> context, boolean granted, boolean isReset) {
		final CommandSourceStack source = context.getSource();
		final String name = StringArgumentType.getString(context, ARGUMENT_PLAYER);
		final Target target = resolveTarget(source.getServer(), name);
		if (!checkGate(source)) {
			return 0;
		}

		if (isReset) {
			if (!WebDashboardPermissions.reset(target.uuid())) {
				source.sendFailure(Text.translatable("gui.mtr.web_dashboard_command_no_entry", target.displayName()));
				return 0;
			}

			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_reset", target.displayName()), true);
			warnIfNobodyCanEdit(source);
			return 1;
		}

		WebDashboardPermissions.setPermission(target.uuid(), granted, target.onlineName());
		source.sendSuccess(() -> Text.translatable(granted ? "gui.mtr.web_dashboard_command_granted" : "gui.mtr.web_dashboard_command_revoked", target.displayName()), true);

		if (!target.online()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_offline_note", target.displayName()), false);
		}

		if (!granted) {
			final int endedSessions = WebDashboardTokenStore.revokePlayer(target.uuid());
			if (endedSessions > 0) {
				source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_sessions_ended", endedSessions), false);
			}
			warnIfNobodyCanEdit(source);
		} else if (target.online()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_tells_player"), false);
		}

		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();
		final MinecraftServer server = source.getServer();
		final boolean running = WebDashboardRuntime.isRunning();
		final int port = WebDashboardServer.getPort();
		final boolean lanAccess = WebDashboardSettings.get().isAllowLanAccess();

		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_header"), false);
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
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_status_lan_warning"), false);
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		final CommandSourceStack source = context.getSource();
		if (!checkGate(source)) {
			return 0;
		}
		final List<PermissionEntryView> entries = WebDashboardSettings.get().listPermissionEntries();

		source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_header", WebDashboardPermissions.describeTable()), false);

		if (entries.isEmpty()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_empty", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		} else {
			for (final PermissionEntryView entry : entries) {
				final String uuidText = entry.uuid() == null ? "" : entry.uuid().toString();
				final String shortUuid = uuidText.length() <= 8 ? uuidText : uuidText.substring(0, 8);
				final boolean online = WebDashboardPermissions.getOnlinePlayer(entry.uuid()) != null;
				source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_entry", entry.getDisplayName(), shortUuid)
						.append(Text.translatable(entry.granted() ? "gui.mtr.web_dashboard_state_granted" : "gui.mtr.web_dashboard_state_denied"))
						.append(Text.translatable(online ? "gui.mtr.web_dashboard_state_online" : "gui.mtr.web_dashboard_state_offline")), false);
			}
		}

		final List<String> usernames = WebDashboardTokenStore.getSessionUsernames();
		if (!usernames.isEmpty()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_list_sessions", String.join(", ", usernames)), false);
		}
		return 1;
	}

	private static void warnIfNobodyCanEdit(CommandSourceStack source) {
		if (!WebDashboardPermissions.hasAnyExplicitGrant()) {
			source.sendSuccess(() -> Text.translatable("gui.mtr.web_dashboard_command_no_grants", WebDashboardPermissions.REQUIRED_OP_LEVEL), false);
		}
	}

	private static Target resolveTarget(MinecraftServer server, String name) {
		final ServerPlayer online = server == null ? null : server.getPlayerList().getPlayerByName(name);
		if (online != null) {
			final String username = online.getName().getString();
			return new Target(online.getUUID(), username, true, username);
		}

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

	private record Target(UUID uuid, String displayName, boolean online, String onlineName) {
	}
}
