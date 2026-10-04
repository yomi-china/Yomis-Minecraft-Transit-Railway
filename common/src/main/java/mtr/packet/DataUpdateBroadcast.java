package mtr.packet;

import mtr.Registry;
import mtr.data.RailwayData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * The downstream effects of a railway data change, from either the in-game packet path or the web
 * dashboard: notify other players, refresh the caches, refresh the external map plugins.
 *
 * Shared so both paths produce identical effects. Two copies would drift, and the symptom would be a change
 * that looks applied to its author and is invisible to everyone else.
 */
public final class DataUpdateBroadcast {

	private DataUpdateBroadcast() {
	}

	/**
	 * Announces a change to everyone in the level and refreshes the derived caches.
	 * <p>
	 * Must be called on the game thread: it mutates the caches and walks the level's player list.
	 *
	 * @param payload    the packet body, already written. It is sent verbatim, so its format must match what
	 *                   the in-game client's receiver reads - which holds when the caller built it through
	 *                   the model's own setter.
	 * @param exceptUuid a player to skip, or null to send to everyone. The in-game path skips the editor,
	 *                   whose client already applied the change; the web path has no such client.
	 */
	public static void broadcastDataUpdate(Level world, ResourceLocation packetId, FriendlyByteBuf payload, UUID exceptUuid) {
		if (world == null || packetId == null || payload == null) {
			return;
		}

		for (final Player worldPlayer : world.players()) {
			if (exceptUuid != null && worldPlayer.getUUID().equals(exceptUuid)) {
				continue;
			}
			Registry.sendToPlayer((ServerPlayer) worldPlayer, packetId, payload);
		}

		final RailwayData railwayData = RailwayData.getInstance(world);
		if (railwayData != null) {
			railwayData.dataCache.sync();
			updateExternalMaps(world, railwayData, packetId);
		}
	}

	/**
	 * Refreshes the Dynmap, BlueMap and squaremap layers.
	 * <p>
	 * Each call is guarded by {@code NoClassDefFoundError} as well as {@code Exception}: the plugins are
	 * optional compile-time dependencies, so on most installs their classes are absent at runtime.
	 */
	public static void updateExternalMaps(Level world, RailwayData railwayData, ResourceLocation packetId) {
		if (world == null || railwayData == null || !affectsMapPlugins(packetId)) {
			return;
		}

		try {
			UpdateDynmap.updateDynmap(world, railwayData);
		} catch (NoClassDefFoundError | Exception ignored) {
		}
		try {
			UpdateBlueMap.updateBlueMap(world, railwayData);
		} catch (NoClassDefFoundError | Exception ignored) {
		}
		try {
			UpdateSquaremap.updateSquaremap(world, railwayData);
		} catch (NoClassDefFoundError | Exception ignored) {
		}
	}

	/** @return whether the external map plugins draw this kind of object, so only those changes pay for it. */
	public static boolean affectsMapPlugins(ResourceLocation packetId) {
		return packetId != null && (packetId.equals(IPacket.PACKET_UPDATE_STATION) || packetId.equals(IPacket.PACKET_DELETE_STATION)
				|| packetId.equals(IPacket.PACKET_UPDATE_DEPOT) || packetId.equals(IPacket.PACKET_DELETE_DEPOT));
	}
}
