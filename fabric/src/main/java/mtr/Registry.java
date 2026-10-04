package mtr;

import mtr.fabric.RegistryImpl;
import mtr.mappings.NetworkUtilities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class Registry {

	public static boolean isFabric() {
		return RegistryImpl.isFabric();
	}

	public static Supplier<CreativeModeTab> getCreativeModeTab(ResourceLocation id, Supplier<ItemStack> supplier) {
		return RegistryImpl.getCreativeModeTab(id, supplier);
	}

	public static void registerCreativeModeTab(ResourceLocation resourceLocation, Item item) {
		RegistryImpl.registerCreativeModeTab(resourceLocation, item);
	}

	public static Packet<?> createAddEntityPacket(Entity entity) {
		return RegistryImpl.createAddEntityPacket(entity);
	}

	public static void registerNetworkReceiver(ResourceLocation resourceLocation, NetworkUtilities.PacketCallback packetCallback) {
		RegistryImpl.registerNetworkReceiver(resourceLocation, packetCallback);
	}

	public static void registerPlayerJoinEvent(Consumer<ServerPlayer> consumer) {
		RegistryImpl.registerPlayerJoinEvent(consumer);
	}

	public static void registerPlayerQuitEvent(Consumer<ServerPlayer> consumer) {
		RegistryImpl.registerPlayerQuitEvent(consumer);
	}

	public static void registerServerStartingEvent(Consumer<MinecraftServer> consumer) {
		RegistryImpl.registerServerStartingEvent(consumer);
	}

	public static void registerServerStoppingEvent(Consumer<MinecraftServer> consumer) {
		RegistryImpl.registerServerStoppingEvent(consumer);
	}

	public static void registerTickEvent(Consumer<MinecraftServer> consumer) {
		RegistryImpl.registerTickEvent(consumer);
	}

	public static void sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf packet) {
		RegistryImpl.sendToPlayer(player, id, packet);
	}

	public static void setInTeleportationState(Player player, boolean isRiding) {
		RegistryImpl.setInTeleportationState(player, isRiding);
	}
}
