package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import mtr.MTRClient;
import mtr.client.Config;
import mtr.data.RailwayData;
import mtr.data.TrainClient;
import mtr.entity.EntitySeat;
import mtr.mappings.Utilities;
import mtr.mappings.UtilitiesClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public abstract class TrainRendererBase {

	protected static Camera camera;
	protected static Level world;
	protected static float lastFrameDuration;
	protected static PoseStack matrices;
	protected static MultiBufferSource vertexConsumers;

	protected static boolean isTranslucentBatch;

	private static Entity cameraEntity;
	private static boolean hasEntity;
	private static double entityX;
	private static double entityY;
	private static double entityZ;
	private static EntityRenderDispatcher entityRenderDispatcher;
	private static LocalPlayer player;
	private static Vec3 playerEyePosition;

	private static boolean flipActive;
	private static final float FLIP_THRESHOLD_UP = 100;
	private static final float FLIP_THRESHOLD_DOWN = 80;

	private static long frameId;
	private static boolean renderFrameActive;

	private static double cameraEntityX;
	private static double cameraEntityY;
	private static double cameraEntityZ;
	private static boolean hasCameraEntity;

	private static long carContextFrame = -1;
	private static double carContextX;
	private static double carContextY;
	private static double carContextZ;
	private static boolean carContextNoOffset;
	private static BlockPos carContextPosAverage;
	private static boolean carContextVisible;
	private static int carContextLight = -1;
	private static BlockPos carContextLightPos;

	public abstract TrainRendererBase createTrainInstance(TrainClient train);

	public abstract void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen);

	public abstract void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch);

	public abstract void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch);

	/**
	 * Override to opt out of the translucent render pass when the renderer can never produce translucent geometry.
	 */
	public boolean hasTranslucentParts() {
		return true;
	}

	/**
	 * Pure visibility query. This does not change when the render methods are called; it is only used as a hint by
	 * callers that need to know whether a car is likely to be visible this frame.
	 */
	public boolean isCarVisible(double x, double y, double z) {
		return TrainRenderCulling.isVisible(x, y, z, TrainRenderCulling.getDefaultRadius());
	}

	public static boolean isRenderFrameActive() {
		return renderFrameActive;
	}

	public static void renderRidingPlayer(Vec3 viewOffset, UUID playerId, Vec3 playerPositionOffset) {
		final BlockPos posAverage = applyAverageTransform(viewOffset, playerPositionOffset.x, playerPositionOffset.y, playerPositionOffset.z);
		if (posAverage == null) {
			return;
		}
		matrices.translate(0, RenderTrains.PLAYER_RENDER_OFFSET, 0);
		final Player renderPlayer = world.getPlayerByUUID(playerId);
		if (renderPlayer != null && (!playerId.equals(player.getUUID()) || camera.isDetached())) {
			entityRenderDispatcher.render(renderPlayer, playerPositionOffset.x, playerPositionOffset.y, playerPositionOffset.z, 0, 1, matrices, vertexConsumers, 0xF000F0);
		}
		matrices.popPose();
	}

	public static void setupStaticInfo(PoseStack matrices, MultiBufferSource vertexConsumers, EntitySeat entity, float tickDelta) {
		final Minecraft client = Minecraft.getInstance();
		camera = client.gameRenderer.getMainCamera();
		entityRenderDispatcher = client.getEntityRenderDispatcher();
		world = client.level;
		player = client.player;
		lastFrameDuration = MTRClient.getLastFrameDuration();
		TrainRendererBase.matrices = matrices;
		TrainRendererBase.vertexConsumers = vertexConsumers;
		cameraEntity = client.cameraEntity;
		hasEntity = entity != null;
		entityX = hasEntity ? Mth.lerp(tickDelta, entity.xOld, entity.getX()) : 0;
		entityY = hasEntity ? Mth.lerp(tickDelta, entity.yOld, entity.getY()) : 0;
		entityZ = hasEntity ? Mth.lerp(tickDelta, entity.zOld, entity.getZ()) : 0;
		playerEyePosition = player == null ? Vec3.ZERO : player.getEyePosition(client.getFrameTime());

		hasCameraEntity = cameraEntity != null;
		if (hasCameraEntity) {
			final Vec3 cameraEntityPos = cameraEntity.position();
			cameraEntityX = cameraEntityPos.x;
			cameraEntityY = cameraEntityPos.y;
			cameraEntityZ = cameraEntityPos.z;
		}

		frameId++;
		renderFrameActive = true;
		carContextFrame = -1;
		carContextLight = -1;
		carContextLightPos = null;
		TrainRenderCulling.prepare(camera);
	}

	public static void endRenderFrame() {
		renderFrameActive = false;
		carContextFrame = -1;
		carContextLight = -1;
		carContextLightPos = null;
		TrainRenderCulling.clear();
	}

	public static void setBatch(boolean isTranslucentBatch) {
		TrainRendererBase.isTranslucentBatch = isTranslucentBatch;
	}

	/**
	 * Pre-computes the (pure) visibility decision for the next render call. The pose transformations themselves are
	 * still executed by {@link #applyAverageTransform(Vec3, double, double, double)}; this only caches the block
	 * position / visibility / light lookup so that addon renderers calling {@code applyAverageTransform} for the same
	 * coordinates within the same frame do not repeat them.
	 */
	public static boolean beginCar(Vec3 viewOffset, double x, double y, double z, double radius, boolean forceVisible) {
		final boolean noOffset = viewOffset == null;
		final BlockPos posAverage = toBlockPos(x, y, z, noOffset);
		final boolean withinDistance = posAverage != null && !RenderTrains.shouldNotRender(posAverage, Config.getTrainRenderDistanceBlocks(), null);
		final boolean visible = withinDistance && (forceVisible || TrainRenderCulling.isVisible(
				x + (noOffset || !hasCameraEntity ? 0 : cameraEntityX),
				y + (noOffset || !hasCameraEntity ? 0 : cameraEntityY),
				z + (noOffset || !hasCameraEntity ? 0 : cameraEntityZ),
				radius
		));

		carContextFrame = frameId;
		carContextX = x;
		carContextY = y;
		carContextZ = z;
		carContextNoOffset = noOffset;
		carContextPosAverage = posAverage;
		carContextVisible = visible;
		carContextLight = -1;
		carContextLightPos = null;
		return visible;
	}

	/**
	 * Returns the light value for the given block position, reusing the value cached for the current car when possible.
	 */
	public static int getCarLight(BlockPos posAverage) {
		if (posAverage == null) {
			return 0;
		}
		if (carContextLightPos == posAverage && carContextLight >= 0) {
			return carContextLight;
		}
		if (world == null) {
			return 0;
		}
		final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));
		carContextLightPos = posAverage;
		carContextLight = light;
		return light;
	}

	public static BlockPos applyAverageTransform(Vec3 viewOffset, double x, double y, double z) {
		final boolean noOffset = viewOffset == null;
		final BlockPos posAverage;
		final boolean visible;

		if (carContextFrame == frameId && carContextNoOffset == noOffset && carContextX == x && carContextY == y && carContextZ == z) {
			posAverage = carContextPosAverage;
			visible = carContextVisible;
		} else {
			posAverage = toBlockPos(x, y, z, noOffset);
			visible = posAverage != null && !RenderTrains.shouldNotRender(posAverage, Config.getTrainRenderDistanceBlocks(), null);
		}

		if (!visible) {
			return null;
		}

		matrices.pushPose();
		if (viewOffset != null) {
			final double offsetX;
			final double offsetY;
			final double offsetZ;
			if (MTRClient.isVivecraft() && hasEntity) {
				offsetX = entityX;
				offsetY = entityY;
				offsetZ = entityZ;
			} else {
				final Vec3 cameraOffset = camera.isDetached() ? playerEyePosition : camera.getPosition();
				offsetX = cameraOffset.x;
				offsetY = cameraOffset.y;
				offsetZ = cameraOffset.z;
			}
			final float cameraYaw = camera.getYRot();
			final float yawDiff = Utilities.getYaw(player) - cameraYaw;
			final float absYawDiff = Math.abs(yawDiff);
			if (absYawDiff > FLIP_THRESHOLD_UP) {
				flipActive = true;
			} else if (absYawDiff < FLIP_THRESHOLD_DOWN) {
				flipActive = false;
			}
			matrices.translate(offsetX, offsetY, offsetZ);
			UtilitiesClient.rotateYDegrees(matrices, yawDiff + (flipActive ? 180 : 0));
			matrices.translate(-viewOffset.x, -viewOffset.y, -viewOffset.z);
		}

		return posAverage;
	}

	private static BlockPos toBlockPos(double x, double y, double z, boolean noOffset) {
		if (noOffset || !hasCameraEntity) {
			return RailwayData.newBlockPos(x, y, z);
		} else {
			return RailwayData.newBlockPos(x + cameraEntityX, y + cameraEntityY, z + cameraEntityZ);
		}
	}
}
