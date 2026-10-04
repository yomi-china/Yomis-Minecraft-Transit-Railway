package mtr.data;

import mtr.Items;
import mtr.Keys;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPlatform;
import mtr.MtrDebug;
import mtr.mappings.RegistryUtilities;
import mtr.packet.IPacket;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.*;
import java.util.*;

public abstract class Train extends NameColorDataBase implements IPacket {

	protected float speed;
	protected double railProgress;
	protected boolean doorTarget;
	protected float doorValue;
	protected float elapsedDwellTicks;
	protected int nextStoppingIndex;
	protected int nextPlatformIndex;
	protected boolean reversed;
	protected boolean isOnRoute = false;
	protected boolean isCurrentlyManual;
	protected int manualNotch;
	protected boolean useLegacyManualNotch = true;

	public final long sidingId;
	public final String trainId;
	public final String baseTrainType;
	public final TransportMode transportMode;
	public final int spacing;
	public final int width;
	public final int trainCars;
	public final float accelerationConstant;
	public final boolean isManualAllowed;
	public boolean enablePredictiveBraking;
	public final int maxManualSpeed;
	public final int manualToAutomaticTime;
	public final List<PathData> path;

	protected final List<Double> distances;
	protected final int repeatIndex1;
	protected final int repeatIndex2;
	protected final Set<UUID> ridingEntities = new HashSet<>();
	protected final SimpleContainer inventory;

	private final float railLength;

	public static final float ACCELERATION_DEFAULT = 0.01F; // m/tick^2
	public static final float MAX_ACCELERATION = 0.05F; // m/tick^2
	public static final float MIN_ACCELERATION = 0.0001F; // m/tick^2
	public static final int ACCELERATION_DECIMAL_PLACES = 5;
	public static final int DOOR_MOVE_TIME = 64;
	protected static final int MAX_CHECK_DISTANCE = 32;
	protected static final int DOOR_DELAY = 20;

	public static final int P5 = 5;
	public static final int P4 = 4;
	public static final int P3 = 3;
	public static final int P2 = 2;
	public static final int P1 = 1;
	public static final int N = 0;
	public static final int B1 = -1;
	public static final int B2 = -2;
	public static final int B3 = -3;
	public static final int B4 = -4;
	public static final int B5 = -5;
	public static final int B6 = -6;
	public static final int B7 = -7;
	public static final int EB = -8;
	public static final int MAX_POWER_NOTCH = P5;
	public static final int MAX_BRAKE_NOTCH = B7;

	public static float getManualNotchAccelerationMultiplier(int notch) {
		switch (notch) {
			case EB: return -5.0f;
			case B7: return -1.5f;
			case B6: return -1.25f;
			case B5: return -1.0f;
			case B4: return -0.7f;
			case B3: return -0.5f;
			case B2: return -0.2f;
			case B1: return -0.1f;
			case N:  return 0.0f;
			case P1: return 0.2f;
			case P2: return 0.4f;
			case P3: return 0.6f;
			case P4: return 0.8f;
			case P5: return 1.0f;
			default: return notch / 2.0f;
		}
	}

	@Deprecated
	public static final int OLD_MAX_POWER = 2;
	@Deprecated
	public static final int OLD_MAX_BRAKE = -2;
	@Deprecated
	public static final int OLD_EMERGENCY_BRAKE = -3;

	private static final String KEY_SPEED = "speed";
	private static final String KEY_RAIL_PROGRESS = "rail_progress";
	private static final String KEY_ELAPSED_DWELL_TICKS = "stop_counter";
	private static final String KEY_NEXT_STOPPING_INDEX = "next_stopping_index";
	private static final String KEY_NEXT_PLATFORM_INDEX = "next_platform_index";
	private static final String KEY_REVERSED = "reversed";
	private static final String KEY_IS_CURRENTLY_MANUAL = "is_currently_manual";
	private static final String KEY_IS_ON_ROUTE = "is_on_route";
	private static final String KEY_TRAIN_TYPE = "train_type";
	private static final String KEY_TRAIN_CUSTOM_ID = "train_custom_id";
	private static final String KEY_RIDING_ENTITIES = "riding_entities";
	private static final String KEY_CARGO = "cargo";

	public Train(long id, long sidingId, float railLength, String trainId, String baseTrainType, int trainCars, List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2, float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime) {
		super(id);
		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.trainId = trainId;
		// TODO temporary code for backwards compatibility
		baseTrainType = baseTrainType.startsWith("base_") ? baseTrainType.replace("base_", "train_") : baseTrainType;
		// TODO temporary code end
		this.baseTrainType = baseTrainType;
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		this.trainCars = trainCars;
		enablePredictiveBraking = false;
		this.isManualAllowed = isManualAllowed;
		isCurrentlyManual = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		final float tempAccelerationConstant = RailwayData.round(accelerationConstant, ACCELERATION_DECIMAL_PLACES);
		this.accelerationConstant = tempAccelerationConstant < MIN_ACCELERATION ? ACCELERATION_DEFAULT : tempAccelerationConstant;
		inventory = new SimpleContainer(trainCars);
	}

	public Train(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map
	) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);

		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		this.accelerationConstant = accelerationConstant;
		enablePredictiveBraking = false;
		this.isManualAllowed = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;

		speed = messagePackHelper.getFloat(KEY_SPEED);
		railProgress = messagePackHelper.getDouble(KEY_RAIL_PROGRESS);
		elapsedDwellTicks = messagePackHelper.getFloat(KEY_ELAPSED_DWELL_TICKS);
		nextStoppingIndex = messagePackHelper.getInt(KEY_NEXT_STOPPING_INDEX);
		nextPlatformIndex = messagePackHelper.getInt(KEY_NEXT_PLATFORM_INDEX);
		reversed = messagePackHelper.getBoolean(KEY_REVERSED);

		final String tempTrainId = messagePackHelper.getString(KEY_TRAIN_CUSTOM_ID).toLowerCase(Locale.ENGLISH);
		// TODO temporary code for backwards compatibility
		String tempBaseTrainType = messagePackHelper.getString(KEY_TRAIN_TYPE).toLowerCase(Locale.ENGLISH);
		baseTrainType = tempBaseTrainType.startsWith("base_") ? tempBaseTrainType.replace("base_", "train_") : tempBaseTrainType;
		// TODO temporary code end
		trainId = tempTrainId.isEmpty() ? baseTrainType : tempTrainId;
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isCurrentlyManual = messagePackHelper.getBoolean(KEY_IS_CURRENTLY_MANUAL);

		isOnRoute = messagePackHelper.getBoolean(KEY_IS_ON_ROUTE);
		messagePackHelper.iterateArrayValue(KEY_RIDING_ENTITIES, value -> ridingEntities.add(UUID.fromString(value.asStringValue().asString())));

		SimpleContainer inventory1 = new SimpleContainer(trainCars);
		if (map.containsKey(KEY_CARGO) && !map.get(KEY_CARGO).isNilValue()) {
			final byte[] rawNbt = map.get(KEY_CARGO).asBinaryValue().asByteArray();
			final ByteArrayInputStream inputStream = new ByteArrayInputStream(rawNbt);
			try {
				final CompoundTag compoundTag = NbtIo.read(new DataInputStream(inputStream));
				final NonNullList<ItemStack> stacks = NonNullList.withSize(trainCars, ItemStack.EMPTY);
				ContainerHelper.loadAllItems(compoundTag.getCompound(KEY_CARGO), stacks);
				inventory1 = new SimpleContainer(stacks.toArray(new ItemStack[0]));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		inventory = inventory1;
	}

	@Deprecated
	public Train(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag
	) {
		super(compoundTag);

		this.sidingId = sidingId;
		this.railLength = RailwayData.round(railLength, 3);
		this.path = path;
		this.distances = distances;
		this.repeatIndex1 = repeatIndex1;
		this.repeatIndex2 = repeatIndex2;
		this.accelerationConstant = accelerationConstant;
		enablePredictiveBraking = false;
		this.isManualAllowed = isManualAllowed;
		this.maxManualSpeed = maxManualSpeed;
		this.manualToAutomaticTime = manualToAutomaticTime;

		speed = compoundTag.getFloat(KEY_SPEED);
		railProgress = compoundTag.getDouble(KEY_RAIL_PROGRESS);
		elapsedDwellTicks = compoundTag.getFloat(KEY_ELAPSED_DWELL_TICKS);
		nextStoppingIndex = compoundTag.getInt(KEY_NEXT_STOPPING_INDEX);
		nextPlatformIndex = compoundTag.getInt(KEY_NEXT_PLATFORM_INDEX);
		reversed = compoundTag.getBoolean(KEY_REVERSED);

		trainId = compoundTag.getString(KEY_TRAIN_CUSTOM_ID);
		baseTrainType = compoundTag.getString(KEY_TRAIN_TYPE);
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isCurrentlyManual = compoundTag.getBoolean(KEY_IS_CURRENTLY_MANUAL);

		isOnRoute = compoundTag.getBoolean(KEY_IS_ON_ROUTE);
		final CompoundTag tagRidingEntities = compoundTag.getCompound(KEY_RIDING_ENTITIES);
		tagRidingEntities.getAllKeys().forEach(key -> ridingEntities.add(tagRidingEntities.getUUID(key)));

		final NonNullList<ItemStack> stacks = NonNullList.withSize(trainCars, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(compoundTag.getCompound(KEY_CARGO), stacks);
		inventory = new SimpleContainer(stacks.toArray(new ItemStack[0]));
	}

	public Train(FriendlyByteBuf packet) {
		super(packet);

		path = new ArrayList<>();
		distances = new ArrayList<>();
		final int pathSize = packet.readInt();
		for (int i = 0; i < pathSize; i++) {
			path.add(new PathData(packet));
			distances.add(packet.readDouble());
		}
		repeatIndex1 = packet.readInt();
		repeatIndex2 = packet.readInt();

		sidingId = packet.readLong();
		railLength = RailwayData.round(packet.readFloat(), 3);
		speed = packet.readFloat();
		final float tempAccelerationConstant = RailwayData.round(packet.readFloat(), ACCELERATION_DECIMAL_PLACES);
		accelerationConstant = tempAccelerationConstant < MIN_ACCELERATION ? ACCELERATION_DEFAULT : tempAccelerationConstant;
		railProgress = packet.readDouble();
		elapsedDwellTicks = packet.readFloat();
		nextStoppingIndex = packet.readInt();
		nextPlatformIndex = packet.readInt();
		reversed = packet.readBoolean();
		trainId = packet.readUtf(PACKET_STRING_READ_LENGTH);
		baseTrainType = packet.readUtf(PACKET_STRING_READ_LENGTH);
		transportMode = TrainType.getTransportMode(baseTrainType);
		spacing = TrainType.getSpacing(baseTrainType);
		width = TrainType.getWidth(baseTrainType);
		trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / spacing));
		isManualAllowed = packet.readBoolean();
		enablePredictiveBraking = packet.readBoolean();
		isCurrentlyManual = packet.readBoolean();
		maxManualSpeed = packet.readInt();
		manualToAutomaticTime = packet.readInt();
		isOnRoute = packet.readBoolean();
		manualNotch = packet.readInt();
		useLegacyManualNotch = packet.readBoolean();
		doorTarget = packet.readBoolean();
		isManualBrakingToReversal = packet.readBoolean();
		reversalTargetIndex = packet.readInt();

		final int ridingEntitiesCount = packet.readInt();
		for (int i = 0; i < ridingEntitiesCount; i++) {
			ridingEntities.add(packet.readUUID());
		}

		inventory = null;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_SPEED).packFloat(speed);
		messagePacker.packString(KEY_RAIL_PROGRESS).packDouble(railProgress);
		messagePacker.packString(KEY_ELAPSED_DWELL_TICKS).packFloat(elapsedDwellTicks);
		messagePacker.packString(KEY_NEXT_STOPPING_INDEX).packLong(nextStoppingIndex);
		messagePacker.packString(KEY_NEXT_PLATFORM_INDEX).packLong(nextPlatformIndex);
		messagePacker.packString(KEY_REVERSED).packBoolean(reversed);
		messagePacker.packString(KEY_TRAIN_CUSTOM_ID).packString(trainId);
		messagePacker.packString(KEY_TRAIN_TYPE).packString(baseTrainType);
		messagePacker.packString(KEY_IS_CURRENTLY_MANUAL).packBoolean(isCurrentlyManual);
		messagePacker.packString(KEY_IS_ON_ROUTE).packBoolean(isOnRoute);

		messagePacker.packString(KEY_RIDING_ENTITIES).packArrayHeader(ridingEntities.size());
		for (final UUID uuid : ridingEntities) {
			messagePacker.packString(uuid.toString());
		}

		messagePacker.packString(KEY_CARGO);
		if (inventory != null) {
			final NonNullList<ItemStack> stacks = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
			int totalCount = 0;
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				stacks.set(i, inventory.getItem(i));
				totalCount += inventory.getItem(i).getCount();
			}
			if (totalCount > 0) {
				CompoundTag tag = ContainerHelper.saveAllItems(new CompoundTag(), stacks);
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				NbtIo.write(tag, new DataOutputStream(outputStream));
				messagePacker.packBinaryHeader(outputStream.size());
				messagePacker.writePayload(outputStream.toByteArray());
			} else {
				messagePacker.packNil();
			}
		} else {
			messagePacker.packNil();
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 12;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);

		final int pathSize = Math.min(path.size(), distances.size());
		packet.writeInt(pathSize);
		for (int i = 0; i < pathSize; i++) {
			path.get(i).writePacket(packet);
			packet.writeDouble(distances.get(i));
		}
		packet.writeInt(repeatIndex1);
		packet.writeInt(repeatIndex2);

		packet.writeLong(sidingId);
		packet.writeFloat(railLength);
		packet.writeFloat(speed);
		packet.writeFloat(accelerationConstant);
		packet.writeDouble(railProgress);
		packet.writeFloat(elapsedDwellTicks);
		packet.writeInt(nextStoppingIndex);
		packet.writeInt(nextPlatformIndex);
		packet.writeBoolean(reversed);
		packet.writeUtf(trainId);
		packet.writeUtf(baseTrainType);
		packet.writeBoolean(isManualAllowed);
		packet.writeBoolean(enablePredictiveBraking);
		packet.writeBoolean(isCurrentlyManual);
		packet.writeInt(maxManualSpeed);
		packet.writeInt(manualToAutomaticTime);
		packet.writeBoolean(isOnRoute);
		packet.writeInt(manualNotch);
		packet.writeBoolean(useLegacyManualNotch);
		packet.writeBoolean(doorTarget);
		packet.writeBoolean(isManualBrakingToReversal);
		packet.writeInt(reversalTargetIndex);
		packet.writeInt(ridingEntities.size());
		ridingEntities.forEach(packet::writeUUID);
	}

	@Override
	protected final boolean hasTransportMode() {
		return false;
	}

	public final boolean getIsOnRoute() {
		return isOnRoute;
	}

	public final double getRailProgress() {
		return railProgress;
	}

	public final boolean closeToDepot(int trainDistance) {
		return !isOnRoute || railProgress < trainDistance + railLength;
	}

	public final boolean isCurrentlyManual() {
		return isCurrentlyManual;
	}

	public boolean changeManualSpeed(boolean isAccelerate) {
		useLegacyManualNotch = true;
		if (doorValue == 0 && isAccelerate && manualNotch >= -2 && manualNotch < 2) {
			manualNotch++;
			return true;
		} else if (!isAccelerate && manualNotch > -2) {
			manualNotch--;
			return true;
		} else {
			return false;
		}
	}

	public boolean changeManualSpeedNew(boolean isAccelerate) {
		useLegacyManualNotch = false;
		if (manualNotch <= EB) {
			return false;
		}
		if (doorValue == 0 && isAccelerate && manualNotch >= B7 && manualNotch < P5) {
			manualNotch++;
			return true;
		} else if (!isAccelerate && manualNotch > B7) {
			manualNotch--;
			return true;
		} else if (!isAccelerate && manualNotch == B7) {
			manualNotch = EB;
			return true;
		} else {
			return false;
		}
	}

	public int getManualNotchPercentageForDisplay() {
		return Math.round(Math.abs(getManualNotchAccelerationMultiplier(manualNotch)) * 100);
	}

	public boolean isInEmergencyBrake() {
		return manualNotch <= EB;
	}

	@Deprecated
	public static boolean isEmergencyBrakeLegacy(int manualNotch) {
		return manualNotch <= OLD_EMERGENCY_BRAKE;
	}

	@Deprecated
	public static boolean isManualNotchInLegacyRange(int manualNotch) {
		return manualNotch >= OLD_MAX_BRAKE && manualNotch <= OLD_MAX_POWER;
	}

	public boolean toggleDoors() {
		if (speed == 0) {
			doorTarget = !doorTarget;
			manualNotch = -2;
			return true;
		} else {
			doorTarget = false;
			return false;
		}
	}

	public final int getIndex(int car, int trainSpacing, boolean roundDown) {
		return getIndex(getRailProgress(car, trainSpacing), roundDown);
	}

	public final int getIndex(double tempRailProgress, boolean roundDown) {
		for (int i = 0; i < path.size(); i++) {
			final double tempDistance = distances.get(i);
			if (tempRailProgress < tempDistance || roundDown && tempRailProgress == tempDistance) {
				return i;
			}
		}
		return path.size() - 1;
	}

	public final float getRailSpeed(int railIndex) {
		final RailType thisRail = path.get(railIndex).rail.railType;
		final float railSpeed;
		if (thisRail.canAccelerate) {
			railSpeed = thisRail.maxBlocksPerTick;
		} else {
			final RailType lastRail = railIndex > 0 ? path.get(railIndex - 1).rail.railType : thisRail;
			railSpeed = Math.max(lastRail.canAccelerate ? lastRail.maxBlocksPerTick : RailType.getDefaultMaxBlocksPerTick(transportMode), speed);
		}
		return railSpeed;
	}

	public final boolean isPlayerRiding(Player player) {
		return ridingEntities.contains(player.getUUID());
	}

	public final float getSpeed() {
		return speed;
	}

	public final float getDoorValue() {
		return doorValue;
	}

	public final float getElapsedDwellTicks() {
		return elapsedDwellTicks;
	}

	public final boolean isReversed() {
		return reversed;
	}

	public final boolean isOnRoute() {
		return isOnRoute;
	}

	public int getTotalDwellTicks() {
		return path.get(nextStoppingIndex).dwellTime * 10;
	}

	protected int getAdcTimeTicks() {
		if (nextStoppingIndex < path.size()) {
			return path.get(nextStoppingIndex).adcTime * 10;
		}
		return 0;
	}

	protected final void simulateTrain(Level world, float ticksElapsed, Depot depot) {
		if (world == null) {
			return;
		}

		try {
			if (nextStoppingIndex >= path.size()) {
				return;
			}

			final boolean tempDoorOpen;
			final float tempDoorValue;
			final int totalDwellTicks = getTotalDwellTicks();
			final int adcTimeTicks = isCurrentlyManual ? 0 : getAdcTimeTicks();

			if (!isOnRoute) {
				isManualBrakingToReversal = false;
				reversalTargetIndex = -1;
				railProgress = (railLength + trainCars * spacing) / 2;
				reversed = false;
				tempDoorOpen = false;
				tempDoorValue = 0;
				speed = 0;
				nextStoppingIndex = 0;
				if (!world.isClientSide()) {
					if (!isCurrentlyManual && canDeploy(depot) || isCurrentlyManual && manualNotch > 0) {
						startUp(world, trainCars, spacing, isOppositeRail());
					}
				}
			} else {
				final float newAcceleration = accelerationConstant * ticksElapsed;

				if (railProgress >= distances.get(distances.size() - 1) - (railLength - trainCars * spacing) / 2) {
					isOnRoute = false;
					debugStopReport(world, "gui.mtr.debug_end_of_route", "");
					manualNotch = -2;
					ridingEntities.clear();
					tempDoorOpen = false;
					tempDoorValue = 0;
				} else {
					if (speed <= 0) {
						speed = 0;
						if (isCurrentlyManual && manualNotch <= EB) {
							manualNotch = B2;
						}

						if (isManualBrakingToReversal) {
							isManualBrakingToReversal = false;
							reversalTargetIndex = -1;
						}

						final boolean isOppositeRail = isOppositeRail();
						final boolean railBlocked = isRailBlocked(getIndex(0, spacing, true) + (isOppositeRail ? 2 : 1));

						if (totalDwellTicks == 0) {
							tempDoorOpen = false;
						} else {
							if (elapsedDwellTicks == 0 && isRepeat() && getIndex(railProgress, false) >= repeatIndex2 && distances.size() > repeatIndex1) {
								if (path.get(repeatIndex2).isOppositeRail(path.get(repeatIndex1))) {
									railProgress = distances.get(repeatIndex1 - 1) + trainCars * spacing;
									reversed = !reversed;
								} else {
									railProgress = distances.get(repeatIndex1);
								}
							}

							if (elapsedDwellTicks < totalDwellTicks - DOOR_MOVE_TIME - DOOR_DELAY - ticksElapsed || !railBlocked) {
								elapsedDwellTicks += ticksElapsed;
							}

							tempDoorOpen = openDoors();
						}

						if (!world.isClientSide() && (isCurrentlyManual || elapsedDwellTicks >= totalDwellTicks + adcTimeTicks) && !railBlocked && (!isCurrentlyManual || manualNotch > 0)) {
							startUp(world, trainCars, spacing, isOppositeRail);
						}
					}
					else {
						if (!world.isClientSide()) {
							final int checkIndex = getIndex(0, spacing, true) + 1;
							if (isRailBlocked(checkIndex)) {
								nextStoppingIndex = checkIndex - 1;
								debugStopReport(world, "gui.mtr.debug_rail_blocked", "checkIdx=" + checkIndex);
							} else if (!isCurrentlyManual && nextPlatformIndex > 0 && nextPlatformIndex < path.size()) {
								nextStoppingIndex = nextPlatformIndex;
							}
						}
						int forcedStopIndex = -1;
						if (isCurrentlyManual) {
							for (int i = getIndex(0, spacing, false); i < path.size(); i++) {
								if (i == path.size() - 1 || (i < path.size() - 1 && path.get(i).isOppositeRail(path.get(i + 1)))) {
									forcedStopIndex = i;
									break;
								}
							}
						}

						boolean isReversalPoint = false;
						if (nextStoppingIndex < path.size() - 1) {
							isReversalPoint = path.get(nextStoppingIndex).isOppositeRail(path.get(nextStoppingIndex + 1));
						} else {
							isReversalPoint = true;
						}

						final double effectiveStopDistance = (forcedStopIndex >= 0)
								? distances.get(forcedStopIndex) - railProgress
								: distances.get(nextStoppingIndex) - railProgress;

						final boolean forceBrake = forcedStopIndex >= 0 && effectiveStopDistance < 0.5 * speed * speed / accelerationConstant;

						if (!transportMode.continuousMovement && (effectiveStopDistance < 0.5 * speed * speed / accelerationConstant || isManualBrakingToReversal || forceBrake)) {
							if (!isCurrentlyManual || forcedStopIndex >= 0 || isReversalPoint || isManualBrakingToReversal) {
								if (!wasDecelerating && !isManualBrakingToReversal) {
									wasDecelerating = true;
									debugStopReport(world, "gui.mtr.debug_decel_start", "dist=" + String.format("%.1f", effectiveStopDistance));
								}
								speed = effectiveStopDistance <= 0 ? Train.ACCELERATION_DEFAULT
										: (float) Math.max(speed - (0.5 * speed * speed / effectiveStopDistance) * ticksElapsed, Train.ACCELERATION_DEFAULT);
								manualNotch = EB;

								if (isCurrentlyManual && isReversalPoint && !isManualBrakingToReversal) {
									isManualBrakingToReversal = true;
									reversalTargetIndex = nextStoppingIndex;
								} else if (forcedStopIndex >= 0 && forcedStopIndex != nextStoppingIndex) {
									nextStoppingIndex = forcedStopIndex;
									if (!isManualBrakingToReversal) {
										isManualBrakingToReversal = true;
										reversalTargetIndex = forcedStopIndex;
									}
								}
							} else {
								if (isManualBrakingToReversal) {
									isManualBrakingToReversal = false;
									reversalTargetIndex = -1;
								}
								wasDecelerating = false;
								if (manualNotch >= EB) {
									final RailType railType = convertMaxManualSpeed(maxManualSpeed);
									speed = Mth.clamp(speed + (useLegacyManualNotch ? manualNotch / 2.0f : getManualNotchAccelerationMultiplier(manualNotch)) * newAcceleration,
											0, railType == null ? RailType.IRON.maxBlocksPerTick : railType.maxBlocksPerTick);
								}
							}
						} else {
							if (isManualBrakingToReversal) {
								isManualBrakingToReversal = false;
								reversalTargetIndex = -1;
							}
							wasDecelerating = false;
							if (isCurrentlyManual) {
								if (manualNotch >= EB && !isManualBrakingToReversal) {
									final RailType railType = convertMaxManualSpeed(maxManualSpeed);
									speed = Mth.clamp(speed + (useLegacyManualNotch ? manualNotch / 2.0f : getManualNotchAccelerationMultiplier(manualNotch)) * newAcceleration,
											0, railType == null ? RailType.IRON.maxBlocksPerTick : railType.maxBlocksPerTick);
								}
							} else {
								final float railSpeed;
								if (enablePredictiveBraking && !transportMode.continuousMovement) {
									railSpeed = getPredictiveBrakingSpeed();
								} else {
									railSpeed = getRailSpeed(getIndex(0, spacing, false));
								}
								if (speed < railSpeed) {
									speed = Math.min(speed + newAcceleration, railSpeed);
									manualNotch = P5;
								} else if (speed > railSpeed) {
									speed = Math.max(speed - newAcceleration, railSpeed);
									manualNotch = B7;
								} else {
									manualNotch = N;
								}
							}
						}

						tempDoorOpen = transportMode.continuousMovement && openDoors();
					}

					railProgress += speed * ticksElapsed;

					if (!transportMode.continuousMovement && railProgress > distances.get(nextStoppingIndex)) {
						boolean isReversalPointNow = false;
						if (nextStoppingIndex < path.size() - 1) {
							isReversalPointNow = path.get(nextStoppingIndex).isOppositeRail(path.get(nextStoppingIndex + 1));
						} else {
							isReversalPointNow = true;
						}

						if (isManualBrakingToReversal) {
							if (reversalTargetIndex >= 0 && reversalTargetIndex < path.size()) {
								railProgress = distances.get(reversalTargetIndex);
							} else {
								railProgress = distances.get(nextStoppingIndex);
							}
							speed = 0;
							manualNotch = -2;
							debugStopReport(world, "gui.mtr.debug_reached_stop", "manual_reversal");
							isManualBrakingToReversal = false;
							reversalTargetIndex = -1;
						} else if (isReversalPointNow || nextStoppingIndex >= path.size() - 1) {
							railProgress = distances.get(nextStoppingIndex);
							speed = 0;
							manualNotch = -2;
							debugStopReport(world, "gui.mtr.debug_reached_stop", "forced_reversal_or_end");
						} else if (!isCurrentlyManual) {
							railProgress = distances.get(nextStoppingIndex);
							speed = 0;
							manualNotch = -2;
							debugStopReport(world, "gui.mtr.debug_reached_stop", "dwell=" + (totalDwellTicks / 10) + "s");
						} else {
							int newIndex = -1;
							for (int i = nextStoppingIndex + 1; i < path.size(); i++) {
								if (distances.get(i) > railProgress && (path.get(i).dwellTime > 0 || i == path.size() - 1
										|| (i < path.size() - 1 && path.get(i).isOppositeRail(path.get(i + 1))))) {
									newIndex = i;
									if (i < path.size() - 1 && path.get(i).isOppositeRail(path.get(i + 1))) {
										break;
									}
									if (i == path.size() - 1) {
										break;
									}
									break;
								}
							}
							if (newIndex >= 0) {
								nextStoppingIndex = newIndex;
								elapsedDwellTicks = 0;
								doorTarget = false;
							} else {
								nextStoppingIndex = path.size() - 1;
								railProgress = distances.get(nextStoppingIndex);
								speed = 0;
								manualNotch = -2;
							}
						}
					}

					tempDoorValue = Mth.clamp(doorValue + ticksElapsed * (doorTarget ? 1 : -1) / DOOR_MOVE_TIME, 0, 1);
				}
			}

			doorTarget = tempDoorOpen;
			doorValue = tempDoorValue;
			if (doorTarget || doorValue != 0) {
				manualNotch = -2;
			}

			if (!path.isEmpty()) {
				final Vec3[] positions = new Vec3[trainCars + 1];
				for (int i = 0; i <= trainCars; i++) {
					positions[i] = getRoutePosition(reversed ? trainCars - i : i, spacing);
				}

				if (handlePositions(world, positions, ticksElapsed)) {
					final double[] prevX = {0};
					final double[] prevY = {0};
					final double[] prevZ = {0};
					final float[] prevYaw = {0};
					final float[] prevPitch = {0};

					for (int i = 0; i < trainCars; i++) {
						final int ridingCar = i;
						calculateCar(world, positions, i, totalDwellTicks, (x, y, z, yaw, pitch, realSpacing, doorLeftOpen, doorRightOpen) -> {
							simulateCar(
									world, ridingCar, ticksElapsed,
									x, y, z,
									yaw, pitch,
									prevX[0], prevY[0], prevZ[0],
									prevYaw[0], prevPitch[0],
									doorLeftOpen, doorRightOpen, realSpacing
							);
							prevX[0] = x;
							prevY[0] = y;
							prevZ[0] = z;
							prevYaw[0] = yaw;
							prevPitch[0] = pitch;
						});
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private int lastReportedStopIndex = -1;
	private String lastReportedStopReason = "";
	private boolean wasDecelerating;
	protected boolean isManualBrakingToReversal = false;
	protected int reversalTargetIndex = -1;

	private void debugStopReport(Level world, String reasonKey, String details) {
		final String dedupKey = reasonKey + "@" + nextStoppingIndex;
		if (!dedupKey.equals(lastReportedStopReason)) {
			lastReportedStopReason = dedupKey;
			final int headIndex = getIndex(0, spacing, false);
			final float brakingDist = 0.5F * speed * speed / accelerationConstant;
			final String body = String.format(" §fstopIdx=%d §7(head=%d rp=%.1f spd=%.3f brkDist=%.1f platIdx=%d man=%b)",
					nextStoppingIndex, headIndex, railProgress, speed, brakingDist, nextPlatformIndex, isCurrentlyManual);
			final String railInfo = nextStoppingIndex < path.size() && nextStoppingIndex >= 0 ? " §7rail=" + path.get(nextStoppingIndex).rail.railType : "";
			MtrDebug.debugMessage(world, ridingEntities,
					mtr.mappings.Text.literal("§e[")
							.append(mtr.mappings.Text.translatable(reasonKey).withStyle(ChatFormatting.YELLOW))
							.append(mtr.mappings.Text.literal("]"))
							.append(mtr.mappings.Text.literal(body + railInfo + " §7" + details))
			);
		}
	}

	protected final void calculateCar(Level world, Vec3[] positions, int index, int dwellTicks, CalculateCarCallback calculateCarCallback) {
		final Vec3 pos1 = positions[index];
		final Vec3 pos2 = positions[index + 1];

		if (pos1 != null && pos2 != null) {
			final double x = getAverage(pos1.x, pos2.x);
			final double y = getAverage(pos1.y, pos2.y) + 1;
			final double z = getAverage(pos1.z, pos2.z);

			final double realSpacing = pos2.distanceTo(pos1);
			final float yaw = (float) Mth.atan2(pos2.x - pos1.x, pos2.z - pos1.z);
			final float pitch = realSpacing == 0 ? 0 : (float) asin((pos2.y - pos1.y) / realSpacing);
			final boolean doorLeftOpen = scanDoors(world, x, y, z, (float) Math.PI + yaw, pitch, realSpacing / 2, dwellTicks) && doorValue > 0;
			final boolean doorRightOpen = scanDoors(world, x, y, z, yaw, pitch, realSpacing / 2, dwellTicks) && doorValue > 0;

			calculateCarCallback.calculateCarCallback(x, y, z, yaw, pitch, realSpacing, doorLeftOpen, doorRightOpen);
		}
	}

	protected void startUp(Level world, int trainCars, int trainSpacing, boolean isOppositeRail) {
		doorTarget = false;
		doorValue = 0;
		nextPlatformIndex = nextStoppingIndex;
	}

	protected boolean openDoors() {
		return doorTarget;
	}

	protected float getModelZOffset() {
		return 0;
	}

	protected boolean isRepeat() {
		return repeatIndex1 > 0 && repeatIndex2 > 0;
	}

	protected abstract void simulateCar(
			Level world, int ridingCar, float ticksElapsed,
			double carX, double carY, double carZ, float carYaw, float carPitch,
			double prevCarX, double prevCarY, double prevCarZ, float prevCarYaw, float prevCarPitch,
			boolean doorLeftOpen, boolean doorRightOpen, double realSpacing
	);

	protected abstract boolean handlePositions(Level world, Vec3[] positions, float ticksElapsed);

	protected abstract boolean canDeploy(Depot depot);

	protected abstract boolean isRailBlocked(int checkIndex);

	protected abstract boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ);

	protected abstract boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks);

	protected abstract double asin(double value);

	private boolean isOppositeRail() {
		return path.size() > nextStoppingIndex + 1 && railProgress == distances.get(nextStoppingIndex) && path.get(nextStoppingIndex).isOppositeRail(path.get(nextStoppingIndex + 1));
	}

	private float getPredictiveBrakingSpeed() {
		final int currentIndex = getIndex(0, spacing, false);
		final float currentRailSpeed = getRailSpeed(currentIndex);

		// Distance from train head to end of current path segment
		double cumulativeDistance = distances.get(currentIndex) - railProgress;

		float effectiveSpeed = currentRailSpeed;
		// Stable scan range based on current track's speed limit, not current speed
		final double maxLookahead = 0.5 * currentRailSpeed * currentRailSpeed / accelerationConstant + 10;

		for (int i = currentIndex + 1; i < path.size(); i++) {
			final PathData pd = path.get(i);
			final RailType rt = pd.rail.railType;

			if (rt.canAccelerate) {
				final float segSpeed = rt.maxBlocksPerTick;
				if (segSpeed < speed) {
					// Exact braking distance: decelerate from current speed to target segment speed
					final double requiredBrakingDist = 0.5 * (speed * speed - segSpeed * segSpeed) / accelerationConstant;
					if (cumulativeDistance <= requiredBrakingDist) {
						effectiveSpeed = Math.min(effectiveSpeed, segSpeed);
					}
				}
			}

			cumulativeDistance += pd.rail.getLength();

			if (cumulativeDistance > maxLookahead) {
				break;
			}
		}

		return effectiveSpeed;
	}

	private double getRailProgress(int car, int trainSpacing) {
		return railProgress - car * trainSpacing;
	}

	private Vec3 getRoutePosition(int car, int trainSpacing) {
		final double tempRailProgress = Math.max(getRailProgress(car, trainSpacing) - getModelZOffset(), 0);
		final int index = getIndex(tempRailProgress, false);
		return path.get(index).rail.getPosition(tempRailProgress - (index == 0 ? 0 : distances.get(index - 1))).add(0, transportMode.railOffset, 0);
	}

	private boolean scanDoors(Level world, double trainX, double trainY, double trainZ, float checkYaw, float pitch, double halfSpacing, int dwellTicks) {
		if (skipScanBlocks(world, trainX, trainY, trainZ)) {
			return false;
		}

		boolean hasPlatform = false;
		final Vec3 offsetVec = new Vec3(1, 0, 0).yRot(checkYaw).xRot(pitch);
		final Vec3 traverseVec = new Vec3(0, 0, 1).yRot(checkYaw).xRot(pitch);

		for (int checkX = 1; checkX <= 3; checkX++) {
			for (int checkY = -2; checkY <= 3; checkY++) {
				for (double checkZ = -halfSpacing; checkZ <= halfSpacing; checkZ++) {
					final BlockPos checkPos = RailwayData.newBlockPos(trainX + offsetVec.x * checkX + traverseVec.x * checkZ, trainY + checkY, trainZ + offsetVec.z * checkX + traverseVec.z * checkZ);
					final Block block = world.getBlockState(checkPos).getBlock();

					if (block instanceof BlockPlatform || block instanceof BlockPSDAPGBase) {
						if (openDoors(world, block, checkPos, dwellTicks)) {
							return true;
						}
						hasPlatform = true;
					}
				}
			}
		}

		return hasPlatform;
	}

	public static boolean isHoldingKey(Player player) {
		return player != null && !Keys.LIFTS_ONLY && player.isHolding(Items.DRIVER_KEY.get());
	}

	@Nullable
	private static Item onboardToolItem;
	private static boolean onboardToolChecked;

	public static boolean isHoldingOnboardTool(Player player) {
		if (player == null) {
			return false;
		}
		if (onboardToolItem == null && !onboardToolChecked) {
			onboardToolChecked = true;
			final ResourceLocation id = new ResourceLocation("mtryum", "onboard_tool");
			if (RegistryUtilities.registryGetItem().containsKey(id)) {
				onboardToolItem = RegistryUtilities.registryGetItem().get(id);
			}
		}
		return onboardToolItem != null && player.isHolding(onboardToolItem);
	}

	public static double getAverage(double a, double b) {
		return (a + b) / 2;
	}

	public static RailType convertMaxManualSpeed(int maxManualSpeed) {
		if (maxManualSpeed >= 0 && maxManualSpeed <= RailType.DIAMOND.ordinal()) {
			return RailType.values()[maxManualSpeed];
		} else {
			return null;
		}
	}

	protected boolean isStoppingAtPlatform() {
		return nextStoppingIndex < path.size() && path.get(nextStoppingIndex).rail.railType == RailType.PLATFORM;
	}

	@FunctionalInterface
	protected interface CalculateCarCallback {
		void calculateCarCallback(double x, double y, double z, float yaw, float pitch, double realSpacing, boolean doorLeftOpen, boolean doorRightOpen);
	}
}
