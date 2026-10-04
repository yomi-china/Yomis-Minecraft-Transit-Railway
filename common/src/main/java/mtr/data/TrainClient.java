package mtr.data;

import mtr.MTRClient;
import mtr.client.ClientData;
import mtr.client.Config;
import mtr.client.ScrollingText;
import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;
import mtr.render.RenderDrivingOverlay;
import mtr.render.TrainRendererBase;
import mtr.sound.TrainSoundBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TrainClient extends Train implements IGui {

	public boolean isRemoved = false;
	private boolean justMounted;
	private float oldSpeed;
	private double oldRailProgress;
	private float oldDoorValue;
	private boolean doorOpening;
	private boolean isSitting;
	private boolean previousShifting;

	private int currentStationIndex;
	private Route thisRoute;
	private Route nextRoute;
	private Station thisStation;
	private Station nextStation;
	private Station lastStation;

	private SpeedCallback speedCallback;
	private AnnouncementCallback announcementCallback;
	private AnnouncementCallback lightRailAnnouncementCallback;
	private Depot depot;
	private List<Long> routeIds = new ArrayList<>();

	public final TrainRendererBase trainRenderer;
	public final TrainSoundBase trainSound;
	public final VehicleRidingClient vehicleRidingClient = new VehicleRidingClient(ridingEntities, PACKET_UPDATE_TRAIN_PASSENGER_POSITION);
	public final List<ScrollingText> scrollingTexts = new ArrayList<>();

	private final Set<Runnable> trainTranslucentRenders = new HashSet<>();

	private int scoringTargetStopIndex = -1;
	private double scoringPeakSpeed;
	private int scoringPeakSpeedTick;
	private boolean scoringStopped;
	private int scoringStopTick;
	private int scoringBrakeNotchMin;

	private static final float CONNECTION_HEIGHT = 2.25F;
	private static final float CONNECTION_Z_OFFSET = 0.5F;
	private static final float CONNECTION_X_OFFSET = 0.25F;

	public TrainClient(FriendlyByteBuf packet) {
		super(packet);
		final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
		trainRenderer = trainProperties.renderer.createTrainInstance(this);
		trainSound = trainProperties.sound.createTrainInstance(this);
	}

	@Override
	protected void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	) {
		final LocalPlayer clientPlayer = Minecraft.getInstance().player;
		if (clientPlayer == null) {
			return;
		}

		final BlockPos soundPos = RailwayData.newBlockPos(carX, carY, carZ);
		trainSound.playAllCars(world, soundPos, ridingCar);
		if (doorLeftOpen || doorRightOpen) {
			trainSound.playAllCarsDoorOpening(world, soundPos, ridingCar);
		}

		final Vec3 offset = vehicleRidingClient.renderPlayerAndGetOffset();
		final double newX = carX - offset.x;
		final double newY = carY - offset.y;
		final double newZ = carZ - offset.z;

		doorOpening = doorValue > oldDoorValue;
		trainRenderer.renderCar(ridingCar, newX, newY, newZ, carYaw, carPitch, doorLeftOpen, doorRightOpen);
		trainTranslucentRenders.add(() -> trainRenderer.renderCar(ridingCar, newX, newY, newZ, carYaw, carPitch, doorLeftOpen, doorRightOpen));

		if (ridingCar > 0) {
			final double newPrevCarX = prevCarX - offset.x;
			final double newPrevCarY = prevCarY - offset.y;
			final double newPrevCarZ = prevCarZ - offset.z;

			final Vec3 prevPos0 = new Vec3(0, 0, spacing / 2D - 1).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
			final Vec3 thisPos0 = new Vec3(0, 0, -(spacing / 2D - 1)).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
			final Vec3 connectPos = prevPos0.add(thisPos0).scale(0.5);
			final float connectYaw = (float) Mth.atan2(thisPos0.x - prevPos0.x, thisPos0.z - prevPos0.z);
			final double connectRealSpacing = thisPos0.distanceTo(prevPos0);
			final float connectPitch = (float) asin((thisPos0.y - prevPos0.y) / connectRealSpacing);

			for (int i = 0; i < 2; i++) {
				final double xStart = width / 2D + (i == 0 ? -1 : 0.5) * CONNECTION_X_OFFSET;
				final double zStart = spacing / 2D - (i == 0 ? 1 : 2) * CONNECTION_Z_OFFSET;

				final Vec3 prevPos1 = new Vec3(xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos2 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos3 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);
				final Vec3 prevPos4 = new Vec3(-xStart, SMALL_OFFSET, zStart).xRot(prevCarPitch).yRot(prevCarYaw).add(newPrevCarX, newPrevCarY, newPrevCarZ);

				final Vec3 thisPos1 = new Vec3(-xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos2 = new Vec3(-xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos3 = new Vec3(xStart, CONNECTION_HEIGHT + SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);
				final Vec3 thisPos4 = new Vec3(xStart, SMALL_OFFSET, -zStart).xRot(carPitch).yRot(carYaw).add(newX, newY, newZ);

				if (i == 0) {
					trainRenderer.renderConnection(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				} else {
					trainRenderer.renderBarrier(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, connectPos.x, connectPos.y, connectPos.z, connectYaw, connectPitch);
				}
			}
		}
	}

	@Override
	protected boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed) {
		final Minecraft client = Minecraft.getInstance();
		final LocalPlayer clientPlayer = client.player;
		if (clientPlayer == null) {
			return false;
		}

		vehicleRidingClient.begin();

		if (ticksElapsed > 0) {
			if (isPlayerRiding(clientPlayer)) {
				final int headIndex = getIndex(0, spacing, false);
				final int stopIndex = path.get(headIndex).stopIndex - 1;

				if (speedCallback != null) {
					speedCallback.speedCallback(speed * 20, stopIndex, routeIds);
				}

				if (announcementCallback != null) {
					final double targetProgress = distances.get(getPreviousStoppingIndex(headIndex)) + (trainCars + 1) * spacing;
					if (oldRailProgress < targetProgress && railProgress >= targetProgress) {
						announcementCallback.announcementCallback(stopIndex, routeIds);
					}
				}

				if (lightRailAnnouncementCallback != null && (justOpening() || justMounted)) {
					lightRailAnnouncementCallback.announcementCallback(stopIndex, routeIds);
				}
			}

			final TrainProperties trainProperties = TrainClientRegistry.getTrainProperties(trainId);
			vehicleRidingClient.movePlayer(uuid -> {
				final CalculateCarCallback calculateCarCallback = (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> vehicleRidingClient.setOffsets(uuid, x, y, z, yaw, pitch, transportMode.maxLength == 1 ? spacing : realSpacingRender, width, doorLeftOpenRender, doorRightOpenRender, transportMode.hasPitchAscending, transportMode.hasPitchDescending, trainProperties.riderOffset, trainProperties.riderOffsetDismounting, speed > 0, doorValue == 0, () -> {
					final boolean isShifting = clientPlayer.isShiftKeyDown();
					if (Config.shiftToToggleSitting() && !MTRClient.isVivecraft()) {
						if (isShifting && !previousShifting) {
							isSitting = !isSitting;
						}
						clientPlayer.setPose(isSitting && !client.gameRenderer.getMainCamera().isDetached() ? Pose.CROUCHING : Pose.STANDING);
					}
					previousShifting = isShifting;
				});

				final int currentRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
				calculateCar(world, positions, currentRidingCar, 0, (x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender) -> {
					vehicleRidingClient.moveSelf(id, uuid, realSpacingRender, width, yaw, currentRidingCar, trainCars, doorLeftOpenRender, doorRightOpenRender, !trainProperties.hasGangwayConnection, ticksElapsed);

					final int newRidingCar = Mth.clamp((int) Math.floor(vehicleRidingClient.getPercentageZ(uuid)), 0, positions.length - 2);
					if (currentRidingCar == newRidingCar) {
						calculateCarCallback.calculateCarCallback(x, y, z, yaw, pitch, realSpacingRender, doorLeftOpenRender, doorRightOpenRender);
					} else {
						calculateCar(world, positions, newRidingCar, 0, calculateCarCallback);
					}
				});
			});
		}

		vehicleRidingClient.end();
		justMounted = false;

		final Entity camera = client.cameraEntity;
		final Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
		Vec3 nearestPoint = positions[0];
		double nearestDistance = Double.POSITIVE_INFINITY;
		int nearestCar = 0;
		for (int i = 0; i < trainCars; i++) {
			Vec3 v = positions[i + 1].subtract(positions[i]);
			Vec3 w = cameraPos.subtract(positions[i]);

			double c1 = w.dot(v);
			if ( c1 <= 0 ) {
				final double checkDistance = positions[i].distanceToSqr(cameraPos);
				if (checkDistance < nearestDistance) {
					nearestCar = i;
					nearestDistance = checkDistance;
					nearestPoint = positions[i];
				}
				continue;
			}

			double c2 = v.dot(v);
			if ( c2 <= c1 ) {
				final double checkDistance = positions[i + 1].distanceToSqr(cameraPos);
				if (checkDistance < nearestDistance) {
					nearestCar = i;
					nearestDistance = checkDistance;
					nearestPoint = positions[i + 1];
				}
				continue;
			}

			double b = c1 / c2;
			Vec3 Pb = positions[i].add(v.scale(b));
			final double checkDistance = Pb.distanceToSqr(cameraPos);
			if (checkDistance < nearestDistance) {
				nearestCar = i;
				nearestDistance = checkDistance;
				nearestPoint = Pb;
			}
		}
		final BlockPos soundPos = RailwayData.newBlockPos(nearestPoint.x, nearestPoint.y, nearestPoint.z);
		trainSound.playNearestCar(world, soundPos, nearestCar);

		return true;
	}

	@Override
	protected boolean canDeploy(Depot depot) {
		return false;
	}

	@Override
	protected boolean isRailBlocked(int checkIndex) {
		return false;
	}

	@Override
	protected boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ) {
		return false;
	}

	@Override
	protected boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks) {
		return true;
	}

	@Override
	protected float getModelZOffset() {
		return baseTrainType.startsWith("london_underground_199")
				|| trainId.startsWith("london_underground_199")
				|| baseTrainType.equals("mpl_85")
				|| trainId.equals("mpl_85")
				|| baseTrainType.equals("br_423")
				|| trainId.equals("br_423") ?
				reversed ? -0.5F : 0.5F : 0; // TODO integrate this into TrainClientRegistry
	}

	@Override
	protected double asin(double value) {
		return Math.asin(value);
	}

	public void simulateTrain(Level world, float ticksElapsed, SpeedCallback speedCallback, AnnouncementCallback announcementCallback, AnnouncementCallback lightRailAnnouncementCallback) {
		trainTranslucentRenders.clear();
		this.speedCallback = speedCallback;
		this.announcementCallback = announcementCallback;
		this.lightRailAnnouncementCallback = lightRailAnnouncementCallback;
		oldSpeed = speed;
		oldRailProgress = railProgress;
		oldDoorValue = doorValue;

		if (ticksElapsed != 0) {
			final int stopIndex = path.get(getIndex(0, spacing, false)).stopIndex - 1;
			if (!RailwayData.useRoutesAndStationsFromIndex(stopIndex, routeIds, ClientData.DATA_CACHE, (currentStationIndex, thisRoute1, nextRoute1, thisStation1, nextStation1, lastStation1) -> {
				this.currentStationIndex = currentStationIndex;
				thisRoute = thisRoute1;
				nextRoute = nextRoute1;
				thisStation = thisStation1;
				nextStation = nextStation1;
				lastStation = lastStation1;
			})) {
				currentStationIndex = 0;
				thisRoute = null;
				nextRoute = null;
				thisStation = null;
				nextStation = null;
				lastStation = null;
			}
		}

		simulateTrain(world, ticksElapsed, null);

		if (depot == null || routeIds.isEmpty()) {
			final Siding siding = ClientData.DATA_CACHE.sidingIdMap.get(sidingId);
			depot = siding == null ? null : ClientData.DATA_CACHE.sidingIdToDepot.get(siding.id);
			routeIds = depot == null ? new ArrayList<>() : depot.routeIds;
			if (depot != null) {
				depot.lastDeployedMillis = System.currentTimeMillis();
			}
		}

		final LocalPlayer player = Minecraft.getInstance().player;
		if (isManualAllowed && Train.isHoldingKey(player) && isPlayerRiding(player)) {
			RenderDrivingOverlay.setData(manualNotch, this);

			if (Config.enableStoppingScoring() && isOnRoute && nextStoppingIndex < path.size()) {
				final int target = path.get(nextStoppingIndex).dwellTime > 0 ? nextStoppingIndex : -1;
				if (target != scoringTargetStopIndex) {
					scoringTargetStopIndex = target;
					scoringPeakSpeed = 0;
					scoringStopped = false;
					scoringBrakeNotchMin = 0;
				}

				if (scoringTargetStopIndex >= 0) {
					if (speed > scoringPeakSpeed) {
						scoringPeakSpeed = speed;
						scoringPeakSpeedTick = player.tickCount;
					}
					if (manualNotch < scoringBrakeNotchMin) {
						scoringBrakeNotchMin = manualNotch;
					}
					if (!scoringStopped && oldSpeed > 0 && speed <= 0) {
						final double dist = getDistanceToNextStop();
						if (dist != -1 && Math.abs(dist) < 10) {
							scoringStopped = true;
							scoringStopTick = player.tickCount;
						}
					}
					if (scoringStopped && oldDoorValue == 0 && doorValue > 0) {
						sendStoppingScore(getDistanceToNextStop());
						scoringStopped = false;
						scoringPeakSpeed = 0;
					}
					if (scoringStopped && speed > 0.5) {
						scoringStopped = false;
						scoringPeakSpeed = 0;
					}
				}
			}
		}
	}

	public void renderTranslucent() {
		trainTranslucentRenders.forEach(Runnable::run);
		trainTranslucentRenders.clear();
	}

	public Vec3 getViewOffset() {
		return vehicleRidingClient.getViewOffset();
	}

	public int getCurrentStationIndex() {
		return currentStationIndex;
	}

	public Route getThisRoute() {
		return thisRoute;
	}

	public Route getNextRoute() {
		return nextRoute;
	}

	public Station getThisStation() {
		return thisStation;
	}

	public Station getNextStation() {
		return nextStation;
	}

	public Station getLastStation() {
		return lastStation;
	}

	public int getManualNotch() {
		return manualNotch;
	}

	public int getMaxManualSpeedKmh() {
		RailType railType = Train.convertMaxManualSpeed(maxManualSpeed);
		return railType != null ? (int) (railType.maxBlocksPerTick * 20 * 3.6) : 300;
	}

	public double getDistanceToNextStop() {
		if (!isOnRoute || distances.isEmpty() || path.isEmpty()) {
			return -1;
		}
		for (int i = distances.size() - 1; i >= 0; i--) {
			if (distances.get(i) <= railProgress && path.get(i).rail.railType == RailType.PLATFORM) {
				final double overshoot = railProgress - distances.get(i);
				if (overshoot <= 5.0) {
					return -overshoot;
				}
				break;
			}
		}
		for (int i = 0; i < distances.size(); i++) {
			if (distances.get(i) > railProgress && path.get(i).rail.railType == RailType.PLATFORM) {
				return distances.get(i) - railProgress;
			}
		}
		return -1;
	}

	public void startRidingClient(UUID uuid, float percentageX, float percentageZ) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getUUID().equals(uuid)) {
			justMounted = true;
			isSitting = false;
		}
		vehicleRidingClient.startRiding(uuid, percentageX, percentageZ);
	}

	public void updateRiderPercentages(UUID uuid, float percentageX, float percentageZ) {
		vehicleRidingClient.updateRiderPercentages(uuid, percentageX, percentageZ);
	}

	public void copyFromTrain(Train train) {
		path.clear();
		distances.clear();
		ridingEntities.clear();

		path.addAll(train.path);
		distances.addAll(train.distances);
		ridingEntities.addAll(train.ridingEntities);

		speed = train.speed;
		railProgress = train.railProgress;
		doorTarget = train.doorTarget;
		elapsedDwellTicks = train.elapsedDwellTicks;
		nextStoppingIndex = train.nextStoppingIndex;
		nextPlatformIndex = train.nextPlatformIndex;
		reversed = train.reversed;
		isOnRoute = train.isOnRoute;
		isCurrentlyManual = train.isCurrentlyManual;
		manualNotch = train.manualNotch;
		useLegacyManualNotch = train.useLegacyManualNotch;
		isManualBrakingToReversal = train.isManualBrakingToReversal;
		reversalTargetIndex = train.reversalTargetIndex;
	}

	public final float speedChange() {
		return speed - oldSpeed;
	}

	public boolean justOpening() {
		return oldDoorValue == 0 && doorValue > 0;
	}

	public boolean justClosing(float doorCloseTime) {
		return oldDoorValue >= doorCloseTime && doorValue < doorCloseTime;
	}

	public final boolean isDoorOpening() {
		return doorOpening;
	}

	public final List<Long> getRouteIds() {
		return routeIds;
	}

	private void sendStoppingScore(double deviation) {
		final int actualTicks = scoringStopTick - scoringPeakSpeedTick;
		final float actualSeconds = actualTicks / 20.0f;
		final float refTicks = (float) scoringPeakSpeed / Math.max(accelerationConstant, 0.0001f);
		final float refSeconds = refTicks / 20.0f;

		final double absDev = Math.abs(deviation);
		final double distanceScore = Math.max(0, 60.0 * Math.exp(-absDev / 2.0));
		final double timeScore = actualTicks <= refTicks * 1.5 ? 40.0 : Math.max(0, 40.0 * (refTicks * 1.5 / actualTicks));
		int brakePenalty = 0;
		if (scoringBrakeNotchMin <= Train.EB) {
			brakePenalty = 15;
		} else if (scoringBrakeNotchMin <= Train.B7) {
			brakePenalty = 7;
		} else if (scoringBrakeNotchMin <= Train.B6) {
			brakePenalty = 3;
		}
		final double totalScore = Math.max(0, distanceScore + timeScore - brakePenalty);

		final String scoreColor = totalScore >= 90 ? "§a" : totalScore >= 70 ? "§e" : "§c";
		final String devColor = absDev <= 0.5 ? "§a" : absDev <= 2.0 ? "§e" : "§c";
		final String timeColor = actualSeconds <= refSeconds * 1.5 ? "§a" : actualSeconds <= refSeconds * 2.5 ? "§e" : "§c";

		final Component message = mtr.mappings.Text.translatable("gui.mtr.stopping_score",
				mtr.mappings.Text.literal(scoreColor + fmt(totalScore, 1) + "§f/100"),
				mtr.mappings.Text.literal(devColor + fmt(absDev, 1) + "m§7(" + fmt(distanceScore, 1) + "/60)"),
				mtr.mappings.Text.literal(timeColor + fmt(actualSeconds, 2) + "s§7(" + fmt(timeScore, 1) + "/40)"),
				brakePenalty > 0 ? mtr.mappings.Text.literal(" §c-" + brakePenalty + " B" + (-scoringBrakeNotchMin)) : mtr.mappings.Text.literal("")
		);

		final Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			for (final UUID uuid : ridingEntities) {
				final net.minecraft.world.entity.player.Player passenger = client.level.getPlayerByUUID(uuid);
				if (passenger != null) {
					passenger.displayClientMessage(message, false);
				}
			}
		}
	}

	private static String fmt(double value, int decimals) {
		return String.format(java.util.Locale.US, "%." + decimals + "f", value);
	}

	private int getPreviousStoppingIndex(int headIndex) {
		for (int i = headIndex; i >= 0; i--) {
			if (path.get(i).dwellTime > 0 && path.get(i).rail.railType == RailType.PLATFORM) {
				return i;
			}
		}
		return 0;
	}

	@FunctionalInterface
	public interface SpeedCallback {
		void speedCallback(float speed, int stopIndex, List<Long> routeIds);
	}

	@FunctionalInterface
	public interface AnnouncementCallback {
		void announcementCallback(int stopIndex, List<Long> routeIds);
	}
}
