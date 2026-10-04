package mtr;

import mtr.mappings.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MtrDebug {

	private static final Set<UUID> debugPlayerUUIDs = new HashSet<>();

	public static void enableDebug(UUID playerUUID) {
		debugPlayerUUIDs.add(playerUUID);
	}

	public static void disableDebug(UUID playerUUID) {
		debugPlayerUUIDs.remove(playerUUID);
	}

	public static boolean isDebugEnabled(UUID playerUUID) {
		return debugPlayerUUIDs.contains(playerUUID);
	}

	public static void debugMessage(Level world, Set<UUID> ridingEntities, String message) {
		debugMessage(world, ridingEntities, Text.literal(message));
	}

	public static void debugMessage(Level world, Set<UUID> ridingEntities, Component message) {
		if (world.isClientSide() || ridingEntities == null) {
			return;
		}
		for (final UUID uuid : ridingEntities) {
			if (debugPlayerUUIDs.contains(uuid)) {
				final Player player = world.getPlayerByUUID(uuid);
				if (player instanceof ServerPlayer) {
					player.displayClientMessage(message, false);
				}
			}
		}
	}
}
