package mtr;

import mtr.fabric.RegistryClientImpl;
import mtr.mappings.BlockEntityMapper;
import mtr.mappings.BlockEntityRendererMapper;
import mtr.mappings.EntityRendererMapper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Consumer;
import java.util.function.Function;

public class RegistryClient {

	public static void registerBlockRenderType(RenderType type, Block block) {
		RegistryClientImpl.registerBlockRenderType(type, block);
	}

	public static void registerItemModelPredicate(String id, Item item, String tag) {
		RegistryClientImpl.registerItemModelPredicate(id, item, tag);
	}

	public static <T extends BlockEntityMapper> void registerTileEntityRenderer(BlockEntityType<T> type, Function<BlockEntityRenderDispatcher, BlockEntityRendererMapper<T>> function) {
		RegistryClientImpl.registerTileEntityRenderer(type, function);
	}

	public static <T extends Entity> void registerEntityRenderer(EntityType<T> type, Function<Object, EntityRendererMapper<T>> function) {
		RegistryClientImpl.registerEntityRenderer(type, function);
	}

	public static void registerKeyBinding(KeyMapping keyMapping) {
		RegistryClientImpl.registerKeyBinding(keyMapping);
	}

	public static void registerBlockColors(Block block) {
		RegistryClientImpl.registerBlockColors(block);
	}

	public static void registerNetworkReceiver(ResourceLocation resourceLocation, Consumer<FriendlyByteBuf> consumer) {
		RegistryClientImpl.registerNetworkReceiver(resourceLocation, consumer);
	}

	public static void registerPlayerJoinEvent(Consumer<LocalPlayer> consumer) {
		RegistryClientImpl.registerPlayerJoinEvent(consumer);
	}

	public static void sendToServer(ResourceLocation id, FriendlyByteBuf packet) {
		RegistryClientImpl.sendToServer(id, packet);
	}
}
