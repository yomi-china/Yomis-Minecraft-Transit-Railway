package mtr.packet;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.client.AnteRailCompat;
import mtr.data.Rail;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 轨道数据包（服务端发送与处理，不含任何客户端类，可安全在专用服务器加载）。
 * 客户端把轨道附加数据（键值对）发给服务器，服务端写入正反向两条轨道的 railData 字段
 * （随 MTR 存档持久化），ANTE 加载时镜像到其 customConfigs（供 ANTE JS 读取），
 * 然后经 MTR 的 PACKET_CREATE_RAIL 广播回所有客户端。
 */
public class PacketUpdateRailData {

	/** 服务端发送：通知客户端打开轨道数据编辑器屏幕（屏幕打开逻辑在客户端专用类 RailDataEditorClient，服务端不加载）。 */
	public static void openRailDataEditorS2C(ServerPlayer player, BlockPos pos) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeBlockPos(pos);
		Registry.sendToPlayer(player, IPacket.PACKET_OPEN_RAIL_DATA_EDITOR, packet);
	}

	/** 服务端接收：校验权限、写入轨道数据、镜像到 ANTE、广播、标记存档保存。 */
	public static void receiveUpdateC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
		if (RailwayData.hasNoPermission(player)) {
			return;
		}
		final ResourceLocation dimensionId = packet.readResourceLocation();
		final BlockPos posStart = packet.readBlockPos();
		final BlockPos posEnd = packet.readBlockPos();
		final Map<String, String> railData = readStringMap(packet);
		server.execute(() -> {
			final ServerLevel level = getLevel(server, dimensionId);
			if (level == null) {
				return;
			}
			final RailwayData railwayData = RailwayData.getInstance(level);
			if (railwayData == null) {
				return;
			}
			final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRailsMap();
			final Map<BlockPos, Rail> railsFromStart = rails.get(posStart);
			final Map<BlockPos, Rail> railsFromEnd = rails.get(posEnd);
			if (railsFromStart == null || railsFromEnd == null) {
				return;
			}
			final Rail railForward = railsFromStart.get(posEnd);
			final Rail railBackward = railsFromEnd.get(posStart);
			if (railForward == null || railBackward == null) {
				return;
			}
			railForward.setRailData(railData);
			railBackward.setRailData(railData);
			AnteRailCompat.setRailCustomConfigsMirror(railForward, railData);
			AnteRailCompat.setRailCustomConfigsMirror(railBackward, railData);
			PacketTrainDataGuiServer.createRailS2C(level, railForward.transportMode, posStart, posEnd, railForward, railBackward, 0);
			railwayData.setDirty();
		});
	}

	private static Map<String, String> readStringMap(FriendlyByteBuf packet) {
		final Map<String, String> map = new HashMap<>();
		final int size = packet.readInt();
		for (int i = 0; i < size; i++) {
			map.put(packet.readUtf(), packet.readUtf());
		}
		return map;
	}

	private static ServerLevel getLevel(MinecraftServer server, ResourceLocation dimensionId) {
		for (final ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().equals(dimensionId)) {
				return level;
			}
		}
		return null;
	}
}
