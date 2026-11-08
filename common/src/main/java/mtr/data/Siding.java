package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.IPacket;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class Siding extends SavedRailBase implements IPacket, IReducedSaveData {

	private Level world;
	private Depot depot;
	private String trainId;
	private String baseTrainType;
	private int trainCars;
	private boolean unlimitedTrains;
	private int maxTrains;
	private boolean isManual;
	private int maxManualSpeed;
	private boolean enablePredictiveBraking;
	private boolean enableDelayedAcceleration;
	private int repeatIndex1;
	private int repeatIndex2;
	private float accelerationConstant;
	/**
	 * The braking rate for trains leaving this siding, kept apart from {@link #accelerationConstant}.
	 * <p>
	 * It is never allowed to fall out of the acceleration range, and whenever a save or a packet written
	 * before the two rates were separated is read, it is set to {@link #accelerationConstant}. That keeps
	 * every old world, and every addon that only speaks the old one-value API, running exactly as before.
	 */
	private float decelerationConstant;
	/**
	 * Set when something the pre-simulated arrival times depend on changed, so the next server tick rebuilds
	 * them once. A flag rather than an immediate rebuild because a single edit touches several of those
	 * settings, and because the client has no use for the rebuild at all.
	 */
	private boolean timeSegmentsDirty;

	public final float railLength;
	private final List<PathData> path = new ArrayList<>();
	private final List<Double> distances = new ArrayList<>();
	private final List<TimeSegment> timeSegments = new ArrayList<>();
	private final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	private final Set<TrainServer> trains = new HashSet<>();

	private static final String KEY_RAIL_LENGTH = "rail_length";
	private static final String KEY_BASE_TRAIN_TYPE = "train_type";
	private static final String KEY_TRAIN_ID = "train_custom_id";
	private static final String KEY_UNLIMITED_TRAINS = "unlimited_trains";
	private static final String KEY_MAX_TRAINS = "max_trains";
	private static final String KEY_IS_MANUAL = "is_manual";
	private static final String KEY_MAX_MANUAL_SPEED = "max_manual_speed";
	private static final String KEY_PATH = "path";
	private static final String KEY_REPEAT_INDEX_1 = "repeat_index_1";
	private static final String KEY_REPEAT_INDEX_2 = "repeat_index_2";
	private static final String KEY_TRAINS = "trains";
	private static final String KEY_ACCELERATION_CONSTANT = "acceleration_constant";
	private static final String KEY_DECELERATION_CONSTANT = "deceleration_constant";
	private static final String KEY_ENABLE_PREDICTIVE_BRAKING = "enable_predictive_braking";
	private static final String KEY_ENABLE_DELAYED_ACCELERATION = "enable_delayed_acceleration";
	private static final String KEY_ADC_TIME = "adc_time";

	public Siding(long id, TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(id, transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		unlimitedTrains = transportMode.continuousMovement;
		enablePredictiveBraking = false;
		enableDelayedAcceleration = false;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
		decelerationConstant = accelerationConstant;
	}

	public Siding(TransportMode transportMode, BlockPos pos1, BlockPos pos2, float railLength) {
		super(transportMode, pos1, pos2);
		this.railLength = RailwayData.round(railLength, 3);
		setTrainDetails();
		unlimitedTrains = transportMode.continuousMovement;
		enablePredictiveBraking = false;
		enableDelayedAcceleration = false;
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
		decelerationConstant = accelerationConstant;
	}

	public Siding(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		railLength = RailwayData.round(messagePackHelper.getFloat(KEY_RAIL_LENGTH), 3);
		setTrainDetails(messagePackHelper.getString(KEY_TRAIN_ID), messagePackHelper.getString(KEY_BASE_TRAIN_TYPE), false);
		unlimitedTrains = transportMode.continuousMovement || messagePackHelper.getBoolean(KEY_UNLIMITED_TRAINS);
		maxTrains = messagePackHelper.getInt(KEY_MAX_TRAINS);
		isManual = messagePackHelper.getBoolean(KEY_IS_MANUAL);
		maxManualSpeed = messagePackHelper.getInt(KEY_MAX_MANUAL_SPEED);
		enablePredictiveBraking = messagePackHelper.getBoolean(KEY_ENABLE_PREDICTIVE_BRAKING);
		enableDelayedAcceleration = messagePackHelper.getBoolean(KEY_ENABLE_DELAYED_ACCELERATION);
		repeatIndex1 = messagePackHelper.getInt(KEY_REPEAT_INDEX_1);
		repeatIndex2 = messagePackHelper.getInt(KEY_REPEAT_INDEX_2);
		final float tempAccelerationConstant = RailwayData.round(messagePackHelper.getFloat(KEY_ACCELERATION_CONSTANT, Train.ACCELERATION_DEFAULT), Train.ACCELERATION_DECIMAL_PLACES);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant < Train.MIN_ACCELERATION ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;
		// Worlds saved before the two rates were separated have no deceleration key at all. Reading the
		// acceleration value as its default turns every one of them into "same rate both ways", which is
		// exactly what they were running before this feature existed.
		final float tempDecelerationConstant = RailwayData.round(messagePackHelper.getFloat(KEY_DECELERATION_CONSTANT, accelerationConstant), Train.ACCELERATION_DECIMAL_PLACES);
		decelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempDecelerationConstant < Train.MIN_ACCELERATION ? accelerationConstant : tempDecelerationConstant;

		messagePackHelper.iterateArrayValue(KEY_PATH, pathSection -> path.add(new PathData(RailwayData.castMessagePackValueToSKMap(pathSection))));

		generateTimeSegments(path, timeSegments, platformTimes);

		messagePackHelper.iterateArrayValue(KEY_TRAINS, value -> trains.add(new TrainServer(id, railLength, timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, decelerationConstant, isManual, maxManualSpeed, dwellTime, RailwayData.castMessagePackValueToSKMap(value))));
		for (final TrainServer train : trains) {
			train.enablePredictiveBraking = enablePredictiveBraking;
			train.enableDelayedAcceleration = enableDelayedAcceleration;
		}
		generateDistances();
	}

	@Deprecated
	public Siding(CompoundTag compoundTag) {
		super(compoundTag);

		railLength = RailwayData.round(compoundTag.getFloat(KEY_RAIL_LENGTH), 3);
		setTrainDetails(compoundTag.getString(KEY_TRAIN_ID), compoundTag.getString(KEY_BASE_TRAIN_TYPE), false);
		unlimitedTrains = transportMode.continuousMovement || compoundTag.getBoolean(KEY_UNLIMITED_TRAINS);
		maxTrains = compoundTag.getInt(KEY_MAX_TRAINS);
		isManual = compoundTag.getBoolean(KEY_IS_MANUAL);
		maxManualSpeed = compoundTag.getInt(KEY_MAX_MANUAL_SPEED);
		enablePredictiveBraking = compoundTag.getBoolean(KEY_ENABLE_PREDICTIVE_BRAKING);
		enableDelayedAcceleration = compoundTag.getBoolean(KEY_ENABLE_DELAYED_ACCELERATION);
		repeatIndex1 = compoundTag.getInt(KEY_REPEAT_INDEX_1);
		repeatIndex2 = compoundTag.getInt(KEY_REPEAT_INDEX_2);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : Train.ACCELERATION_DEFAULT;
		decelerationConstant = accelerationConstant;

		final CompoundTag tagPath = compoundTag.getCompound(KEY_PATH);
		final int pathCount = tagPath.getAllKeys().size();
		for (int i = 0; i < pathCount; i++) {
			path.add(new PathData(tagPath.getCompound(KEY_PATH + i)));
		}

		generateTimeSegments(path, timeSegments, platformTimes);

		final CompoundTag tagTrains = compoundTag.getCompound(KEY_TRAINS);
		tagTrains.getAllKeys().forEach(key -> trains.add(new TrainServer(id, railLength, timeSegments, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, decelerationConstant, isManual, maxManualSpeed, dwellTime, tagTrains.getCompound(key))));
		for (final TrainServer train : trains) {
			train.enablePredictiveBraking = enablePredictiveBraking;
			train.enableDelayedAcceleration = enableDelayedAcceleration;
		}
		generateDistances();
	}

	public Siding(FriendlyByteBuf packet) {
		super(packet);
		railLength = RailwayData.round(packet.readFloat(), 3);
		setTrainDetails(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readUtf(PACKET_STRING_READ_LENGTH), false);
		unlimitedTrains = packet.readBoolean() || transportMode.continuousMovement;
		maxTrains = packet.readInt();
		isManual = packet.readBoolean();
		maxManualSpeed = packet.readInt();
		enablePredictiveBraking = packet.readBoolean();
		final float tempAccelerationConstant = RailwayData.round(packet.readFloat(), Train.ACCELERATION_DECIMAL_PLACES);
		accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant < Train.MIN_ACCELERATION ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;
		final float tempDecelerationConstant = RailwayData.round(packet.readFloat(), Train.ACCELERATION_DECIMAL_PLACES);
		decelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempDecelerationConstant < Train.MIN_ACCELERATION ? accelerationConstant : tempDecelerationConstant;
		enableDelayedAcceleration = packet.readBoolean();
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		RailwayData.writeMessagePackDataset(messagePacker, trains, KEY_TRAINS);
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_RAIL_LENGTH).packFloat(railLength);
		messagePacker.packString(KEY_TRAIN_ID).packString(trainId);
		messagePacker.packString(KEY_BASE_TRAIN_TYPE).packString(baseTrainType);
		messagePacker.packString(KEY_UNLIMITED_TRAINS).packBoolean(unlimitedTrains);
		messagePacker.packString(KEY_MAX_TRAINS).packInt(maxTrains);
		messagePacker.packString(KEY_IS_MANUAL).packBoolean(isManual);
		messagePacker.packString(KEY_MAX_MANUAL_SPEED).packInt(maxManualSpeed);
		messagePacker.packString(KEY_ENABLE_PREDICTIVE_BRAKING).packBoolean(enablePredictiveBraking);
		messagePacker.packString(KEY_ENABLE_DELAYED_ACCELERATION).packBoolean(enableDelayedAcceleration);
		messagePacker.packString(KEY_REPEAT_INDEX_1).packInt(repeatIndex1);
		messagePacker.packString(KEY_REPEAT_INDEX_2).packInt(repeatIndex2);
		messagePacker.packString(KEY_ACCELERATION_CONSTANT).packFloat(accelerationConstant);
		messagePacker.packString(KEY_DECELERATION_CONSTANT).packFloat(decelerationConstant);
		RailwayData.writeMessagePackDataset(messagePacker, path, KEY_PATH);
	}

	@Override
	public int messagePackLength() {
		// One more map entry than before: the deceleration key. The count has to be exact, because it becomes
		// the map header of the save file and of the reduced hash.
		return super.messagePackLength() + 15;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 1;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeFloat(railLength);
		packet.writeUtf(trainId);
		packet.writeUtf(baseTrainType);
		packet.writeBoolean(unlimitedTrains);
		packet.writeInt(maxTrains);
		packet.writeBoolean(isManual);
		packet.writeInt(maxManualSpeed);
		packet.writeBoolean(enablePredictiveBraking);
		packet.writeFloat(accelerationConstant);
		packet.writeFloat(decelerationConstant);
		packet.writeBoolean(enableDelayedAcceleration);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		switch (key) {
			case KEY_BASE_TRAIN_TYPE:
				setTrainDetails(packet.readUtf(PACKET_STRING_READ_LENGTH), packet.readUtf(PACKET_STRING_READ_LENGTH), false);
				trains.clear();
				break;
			case KEY_UNLIMITED_TRAINS:
				final float oldAccelerationConstant = accelerationConstant;
				final float oldDecelerationConstant = decelerationConstant;
				final boolean oldEnablePredictiveBraking = enablePredictiveBraking;
				final boolean oldEnableDelayedAcceleration = enableDelayedAcceleration;
				name = packet.readUtf(PACKET_STRING_READ_LENGTH);
				color = packet.readInt();
				dwellTime = packet.readInt();
				dwellTime = transportMode.continuousMovement ? 1 : dwellTime;
				unlimitedTrains = packet.readBoolean() || transportMode.continuousMovement;
				maxTrains = packet.readInt();
				isManual = packet.readBoolean();
				maxManualSpeed = packet.readInt();
				enablePredictiveBraking = packet.readBoolean();
				final float newAccelerationConstant = RailwayData.round(packet.readFloat(), Train.ACCELERATION_DECIMAL_PLACES);
				accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : newAccelerationConstant;
				if (packet.readBoolean()) {
					trains.clear();
				}
				if (packet.isReadable()) {
					enableDelayedAcceleration = packet.readBoolean();
				}
				// Appended after everything the previous build wrote. A payload from an older writer simply
				// runs out here, and the braking rate then follows the acceleration rate, which is the one
				// value that writer knew about.
				if (packet.isReadable()) {
					final float newDecelerationConstant = RailwayData.round(packet.readFloat(), Train.ACCELERATION_DECIMAL_PLACES);
					decelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : newDecelerationConstant < Train.MIN_ACCELERATION ? accelerationConstant : newDecelerationConstant;
				} else {
					decelerationConstant = accelerationConstant;
				}
				if (oldAccelerationConstant != accelerationConstant || oldDecelerationConstant != decelerationConstant || oldEnablePredictiveBraking != enablePredictiveBraking || oldEnableDelayedAcceleration != enableDelayedAcceleration) {
					timeSegmentsDirty = true;
				}
				break;
			case KEY_ADC_TIME:
				adcTime = packet.readInt();
				adcTime = transportMode.continuousMovement ? 0 : adcTime;
				break;
			default:
				super.update(key, packet);
				break;
		}
	}

	public void setTrainIdAndBaseType(String customId, String trainType, Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_BASE_TRAIN_TYPE);
		packet.writeUtf(customId);
		packet.writeUtf(trainType);
		sendPacket.accept(packet);
		setTrainDetails(customId, trainType, false);
	}

	/**
	 * Sets this siding's driving parameters, with a separate braking rate.
	 * <p>
	 * See {@link #setUnlimitedTrains(boolean, int, boolean, int, boolean, float, int, boolean, Consumer)} for
	 * the compatibility rules; the two shorter overloads below exist only so that code written against MTR,
	 * which knows of a single "acceleration" value, still resolves and still behaves the same way.
	 */
	public void setUnlimitedTrains(boolean unlimitedTrains, int maxTrains, boolean isManual, int maxManualSpeed, boolean enablePredictiveBraking, float accelerationConstant, float decelerationConstant, int newDwellTime, boolean clearTrains, Consumer<FriendlyByteBuf> sendPacket) {
		final float oldAccelerationConstant = this.accelerationConstant;
		final float oldDecelerationConstant = this.decelerationConstant;
		final boolean oldEnablePredictiveBraking = this.enablePredictiveBraking;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_UNLIMITED_TRAINS);
		packet.writeUtf(name);
		packet.writeInt(color);
		writeDwellTimePacket(packet, newDwellTime);
		packet.writeBoolean(unlimitedTrains);
		packet.writeInt(maxTrains);
		packet.writeBoolean(isManual);
		packet.writeInt(maxManualSpeed);
		packet.writeBoolean(enablePredictiveBraking);
		final float tempAccelerationConstant = RailwayData.round(accelerationConstant, Train.ACCELERATION_DECIMAL_PLACES);
		packet.writeFloat(tempAccelerationConstant);
		packet.writeBoolean(clearTrains);
		packet.writeBoolean(enableDelayedAcceleration);
		final float tempDecelerationConstant = RailwayData.round(decelerationConstant, Train.ACCELERATION_DECIMAL_PLACES);
		packet.writeFloat(tempDecelerationConstant);
		sendPacket.accept(packet);
		this.unlimitedTrains = transportMode.continuousMovement || unlimitedTrains;
		this.maxTrains = maxTrains;
		this.isManual = isManual;
		this.maxManualSpeed = maxManualSpeed;
		this.enablePredictiveBraking = enablePredictiveBraking;
		this.accelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempAccelerationConstant;
		this.decelerationConstant = transportMode.continuousMovement ? Train.MAX_ACCELERATION : tempDecelerationConstant;
		if (clearTrains) {
			trains.clear();
		}
		if (oldAccelerationConstant != this.accelerationConstant || oldDecelerationConstant != this.decelerationConstant || oldEnablePredictiveBraking != this.enablePredictiveBraking) {
			timeSegmentsDirty = true;
		}
	}

	/**
	 * The signature shipped by MTR. Kept verbatim so that an addon compiled against MTR still finds it, and
	 * kept functional - it writes both rates as the single value it was given, which is precisely the
	 * behaviour that addon was written against.
	 */
	public void setUnlimitedTrains(boolean unlimitedTrains, int maxTrains, boolean isManual, int maxManualSpeed, boolean enablePredictiveBraking, float accelerationConstant, int newDwellTime, boolean clearTrains, Consumer<FriendlyByteBuf> sendPacket) {
		setUnlimitedTrains(unlimitedTrains, maxTrains, isManual, maxManualSpeed, enablePredictiveBraking, accelerationConstant, accelerationConstant, newDwellTime, clearTrains, sendPacket);
	}

	/**
	 * The pre-predictive-braking signature shipped by MTR. Kept verbatim; the predictive braking setting is
	 * preserved rather than dropped.
	 */
	public void setUnlimitedTrains(boolean unlimitedTrains, int maxTrains, boolean isManual, int maxManualSpeed, float accelerationConstant, int newDwellTime, boolean clearTrains, Consumer<FriendlyByteBuf> sendPacket) {
		setUnlimitedTrains(unlimitedTrains, maxTrains, isManual, maxManualSpeed, enablePredictiveBraking, accelerationConstant, accelerationConstant, newDwellTime, clearTrains, sendPacket);
	}

	public String getTrainId() {
		return trainId;
	}

	/**
	 * @return the vehicle model's type string, e.g. {@code train_24_2}. Used by the web dashboard; the
	 *         in-game screens reach the same value through the path generation code instead.
	 */
	public String getBaseTrainType() {
		return baseTrainType;
	}

	/**
	 * @return how many cars fit on this siding, derived from the rail length and the model's spacing.
	 *         0 until a train type has been resolved, which happens on path generation.
	 */
	public int getTrainCars() {
		return trainCars;
	}

	public float getAccelerationConstant() {
		return accelerationConstant;
	}

	/**
	 * @return the rate trains leaving this siding slow down at, in blocks per tick squared. Equal to
	 *         {@link #getAccelerationConstant()} whenever the two were never set apart.
	 */
	public float getDecelerationConstant() {
		return decelerationConstant;
	}

	public void setSidingData(Level world, Depot depot, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		this.world = world;
		this.depot = depot;

		if (depot == null) {
			trains.clear();
			path.clear();
			distances.clear();
		} else {
			if (path.isEmpty()) {
				generateDefaultPath(rails);
				generateDistances();
			}
			depot.platformTimes.clear();
			depot.platformTimes.putAll(platformTimes);
		}
	}

	public int generateRoute(MinecraftServer minecraftServer, List<PathData> mainPath, int successfulSegmentsMain, Map<BlockPos, Map<BlockPos, Rail>> rails, SavedRailBase firstPlatform, SavedRailBase lastPlatform, boolean repeatInfinitely, int cruisingAltitude, boolean useFastSpeed) {
		final List<PathData> tempPath = new ArrayList<>();
		final int successfulSegments;
		final int tempRepeatIndex1;
		final int tempRepeatIndex2;

		if (firstPlatform == null || lastPlatform == null) {
			successfulSegments = 0;
			tempRepeatIndex1 = 0;
			tempRepeatIndex2 = 0;
		} else {
			final List<SavedRailBase> depotAndFirstPlatform = new ArrayList<>();
			depotAndFirstPlatform.add(this);
			depotAndFirstPlatform.add(firstPlatform);
			PathFinder.findPath(tempPath, rails, depotAndFirstPlatform, 0, cruisingAltitude, useFastSpeed);

			if (tempPath.isEmpty()) {
				successfulSegments = 1;
				tempRepeatIndex1 = 0;
				tempRepeatIndex2 = 0;
			} else if (mainPath.isEmpty()) {
				tempPath.clear();
				successfulSegments = successfulSegmentsMain + 1;
				tempRepeatIndex1 = 0;
				tempRepeatIndex2 = 0;
			} else {
				tempRepeatIndex1 = repeatInfinitely ? tempPath.size() - (tempPath.get(tempPath.size() - 1).isOppositeRail(mainPath.get(0)) ? 0 : 1) : 0;
				PathFinder.appendPath(tempPath, mainPath);

				final List<SavedRailBase> lastPlatformAndDepot = new ArrayList<>();
				lastPlatformAndDepot.add(lastPlatform);
				lastPlatformAndDepot.add(this);
				final List<PathData> pathLastPlatformToDepot = new ArrayList<>();
				PathFinder.findPath(pathLastPlatformToDepot, rails, lastPlatformAndDepot, successfulSegmentsMain, cruisingAltitude, useFastSpeed);

				if (pathLastPlatformToDepot.isEmpty()) {
					successfulSegments = successfulSegmentsMain + 1;
					tempPath.clear();
					tempRepeatIndex2 = 0;
				} else {
					tempRepeatIndex2 = repeatInfinitely ? tempPath.size() - 1 : 0;
					PathFinder.appendPath(tempPath, pathLastPlatformToDepot);
					successfulSegments = successfulSegmentsMain + 2;
				}
			}
		}

		if (world != null) {
			RailwayData railwayData = RailwayData.getInstance(world);
			if (railwayData != null) {
				DataCache dataCache = railwayData.dataCache;

				for (PathData pd : tempPath) {
					if (pd.savedRailBaseId != 0 && pd.dwellTime > 0) {
						final Platform platform = dataCache.platformIdMap.get(pd.savedRailBaseId);
						if (platform != null) {
							pd.adcTime = platform.getAdcTime();
							if (depot != null) {
								final int stopIndex = pd.stopIndex - 1;
								RailwayData.useRoutesAndStationsFromIndex(stopIndex, depot.routeIds, dataCache, (currentStationIndex, thisRoute, nextRoute, thisStation, nextStation, lastStation) -> {
									if (thisRoute != null && currentStationIndex >= 0 && currentStationIndex < thisRoute.platformIds.size()) {
										final Route.RoutePlatform rp = thisRoute.platformIds.get(currentStationIndex);
										pd.stopWithoutOpeningDoors = rp.stopWithoutOpeningDoors;
										if (rp.customDwellTime) {
											pd.dwellTime = rp.dwellTime;
										}
										if (rp.customAdcTime) {
											pd.adcTime = rp.adcTime;
										}
									}
								});
							}
						}
					}
				}
			}
		}

		final List<TimeSegment> tempTimeSegments = new ArrayList<>();
		final Map<Long, Map<Long, Float>> tempPlatformTimes = new HashMap<>();
		generateTimeSegments(tempPath, tempTimeSegments, tempPlatformTimes);

		minecraftServer.execute(() -> {
			try {
				path.clear();
				if (tempPath.isEmpty()) {
					generateDefaultPath(rails);
				} else {
					path.addAll(tempPath);
				}

				timeSegments.clear();
				timeSegments.addAll(tempTimeSegments);
				platformTimes.clear();
				platformTimes.putAll(tempPlatformTimes);
				timeSegmentsDirty = false;
				generateDistances();

				if (tempRepeatIndex1 != repeatIndex1 || tempRepeatIndex2 != repeatIndex2) {
					trains.clear();
				}

				repeatIndex1 = tempRepeatIndex1;
				repeatIndex2 = tempRepeatIndex2;
			} catch (Exception e) {
				e.printStackTrace();
			}
		});

		return successfulSegments;
	}

	public void simulateTrain(DataCache dataCache, RailwayDataDriveTrainModule railwayDataDriveTrainModule, List<Map<UUID, Long>> trainPositions, SignalBlocks signalBlocks, Map<Player, Set<TrainServer>> trainsInPlayerRange, Set<TrainServer> trainsToSync, Map<Long, List<ScheduleEntry>> schedulesForPlatform, Map<Long, Map<BlockPos, TrainDelay>> trainDelays) {
		if (depot == null) {
			return;
		}

		if (timeSegmentsDirty) {
			timeSegmentsDirty = false;
			regenerateTimeSegments();
		}

		int trainsAtDepot = 0;
		boolean spawnTrain = true;

		final Set<Long> railProgressSet = new HashSet<>();
		final Set<TrainServer> trainsToRemove = new HashSet<>();
		boolean delayedAccelerationChanged = false;
		for (final TrainServer train : trains) {
			if (train.enableDelayedAcceleration != enableDelayedAcceleration) {
				train.enableDelayedAcceleration = enableDelayedAcceleration;
				delayedAccelerationChanged = true;
			}

			if (train.isCurrentlyManual() && railwayDataDriveTrainModule.drive(train)) {
				trainsToSync.add(train);
			}

			if (train.simulateTrain(world, 1, depot, dataCache, trainPositions, trainsInPlayerRange, schedulesForPlatform, trainDelays)) {
				trainsToSync.add(train);
			}

			if (train.closeToDepot(train.spacing * trainCars)) {
				spawnTrain = false;
			}

			if (!train.getIsOnRoute()) {
				trainsAtDepot++;
				if (trainsAtDepot > 1) {
					trainsToRemove.add(train);
				}
			}

			final long roundedRailProgress = Math.round(train.getRailProgress() * 10);
			if (railProgressSet.contains(roundedRailProgress)) {
				trainsToRemove.add(train);
			}
			railProgressSet.add(roundedRailProgress);

			if (trainPositions != null && !transportMode.continuousMovement) {
				train.writeTrainPositions(trainPositions, signalBlocks);
			}
		}

		if (trainCars > 0 && (trains.isEmpty() || spawnTrain && (unlimitedTrains || trains.size() <= maxTrains))) {
			final TrainServer train = new TrainServer(unlimitedTrains || maxTrains > 0 ? new Random().nextLong() : id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, decelerationConstant, timeSegments, isManual, maxManualSpeed, dwellTime);
			train.enablePredictiveBraking = enablePredictiveBraking;
			train.enableDelayedAcceleration = enableDelayedAcceleration;
			trains.add(train);
		}

		if (!trainsToRemove.isEmpty()) {
			trainsToRemove.forEach(trains::remove);
		}

		if (delayedAccelerationChanged) {
			trainsToSync.addAll(trains);
		}
	}

	public boolean isValidVehicle(int spacing) {
		return Math.max(2, railLength) >= spacing;
	}

	public int getMaxTrains() {
		return maxTrains;
	}

	public boolean getIsManual() {
		return isManual;
	}

	public int getMaxManualSpeed() {
		return maxManualSpeed;
	}

	public boolean getEnablePredictiveBraking() {
		return enablePredictiveBraking;
	}

	public void setEnablePredictiveBraking(boolean enablePredictiveBraking) {
		if (this.enablePredictiveBraking != enablePredictiveBraking) {
			this.enablePredictiveBraking = enablePredictiveBraking;
			timeSegmentsDirty = true;
		}
	}

	public boolean getEnableDelayedAcceleration() {
		return enableDelayedAcceleration;
	}

	public void setEnableDelayedAcceleration(boolean enableDelayedAcceleration) {
		if (this.enableDelayedAcceleration != enableDelayedAcceleration) {
			this.enableDelayedAcceleration = enableDelayedAcceleration;
			timeSegmentsDirty = true;
		}
	}

	public boolean getUnlimitedTrains() {
		return unlimitedTrains;
	}

	public void clearTrains() {
		trains.clear();
	}

	private void setTrainDetails() {
		for (final TrainType trainType : TrainType.values()) {
			if (TrainType.getTransportMode(trainType.baseTrainType) == transportMode && isValidVehicle(TrainType.getSpacing(trainType.baseTrainType))) {
				setTrainDetails(trainType.toString(), trainType.baseTrainType, true);
				return;
			}
		}
		setTrainDetails(TrainType.values()[0].toString(), TrainType.values()[0].baseTrainType, true);
	}

	private void setTrainDetails(String trainId, String baseTrainType, boolean force) {
		// TODO temporary code for backwards compatibility
		final String baseTrainType2 = baseTrainType.startsWith("base_") ? baseTrainType.replace("base_", "train_") : baseTrainType;
		// TODO temporary code end
		final int trainSpacing = TrainType.getSpacing(baseTrainType2);
		if (force || isValidVehicle(trainSpacing)) {
			this.baseTrainType = baseTrainType2.toLowerCase(Locale.ENGLISH);
			this.trainId = trainId.isEmpty() ? this.baseTrainType : trainId.toLowerCase(Locale.ENGLISH);
			trainCars = Math.min(transportMode.maxLength, (int) Math.floor(railLength / trainSpacing));
		} else {
			setTrainDetails();
		}
	}

	private void generateDefaultPath(Map<BlockPos, Map<BlockPos, Rail>> rails) {
		trains.clear();

		final List<BlockPos> orderedPositions = getOrderedPositions(RailwayData.newBlockPos(0, 0, 0), false);
		final BlockPos pos1 = orderedPositions.get(0);
		final BlockPos pos2 = orderedPositions.get(1);
		if (RailwayData.containsRail(rails, pos1, pos2)) {
			path.add(new PathData(rails.get(pos1).get(pos2), id, 0, 0, pos1, pos2, -1));
		}

		final TrainServer defaultTrain = new TrainServer(id, id, railLength, trainId, baseTrainType, trainCars, path, distances, repeatIndex1, repeatIndex2, accelerationConstant, decelerationConstant, timeSegments, isManual, maxManualSpeed, dwellTime);
		defaultTrain.enablePredictiveBraking = enablePredictiveBraking;
		defaultTrain.enableDelayedAcceleration = enableDelayedAcceleration;
		trains.add(defaultTrain);
	}

	/**
	 * Rebuilds the pre-simulated segments that the platform arrival times are read from.
	 * <p>
	 * Those segments are generated from the driving rates and from the two braking features, so a change to
	 * any of them has to rebuild them; otherwise every countdown would keep being computed with the rates
	 * from the last time the depot path happened to be generated, and the change the player just made would
	 * appear to have no effect on the predictions at all.
	 * <p>
	 * Only done on the server, and only from {@link #simulateTrain}, because the client has no arrival times
	 * to predict and because a single edit touches several of the settings this depends on.
	 */
	private void regenerateTimeSegments() {
		if (!path.isEmpty()) {
			generateTimeSegments(path, timeSegments, platformTimes);
		}
	}

	private void generateDistances() {
		distances.clear();

		double distanceSum = 0;
		for (final PathData pathData : path) {
			distanceSum += pathData.rail.getLength();
			distances.add(distanceSum);
		}

		if (path.size() != 1) {
			trains.removeIf(train -> (train.id == id) == unlimitedTrains);		}
	}

	private void generateTimeSegments(List<PathData> path, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes) {
		timeSegments.clear();

		double distanceSum1 = 0;
		final List<Double> stoppingDistances = new ArrayList<>();
		for (final PathData pathData : path) {
			distanceSum1 += pathData.rail.getLength();
			if (pathData.dwellTime > 0) {
				stoppingDistances.add(distanceSum1);
			}
		}

		final int spacing = TrainType.getSpacing(baseTrainType);
		final double[] segmentEnds = new double[path.size()];
		double segmentEnd = 0;
		for (int i = 0; i < path.size(); i++) {
			segmentEnd += path.get(i).rail.getLength();
			segmentEnds[i] = segmentEnd;
		}
		double railProgress = (railLength + trainCars * spacing) / 2;
		double nextStoppingDistance = 0;
		float speed = 0;
		float time = 0;
		float timeOld = 0;
		long savedRailBaseIdOld = 0;
		double distanceSum2 = 0;
		for (int i = 0; i < path.size(); i++) {
			if (railProgress >= nextStoppingDistance) {
				if (stoppingDistances.isEmpty()) {
					nextStoppingDistance = distanceSum1;
				} else {
					nextStoppingDistance = stoppingDistances.remove(0);
				}
			}

			final PathData pathData = path.get(i);
			final float currentRailSpeed = pathData.rail.railType.canAccelerate ? pathData.rail.railType.maxBlocksPerTick : Math.max(speed, RailType.getDefaultMaxBlocksPerTick(transportMode));
			distanceSum2 += pathData.rail.getLength();

			while (railProgress < distanceSum2) {
				float railSpeed;
				if (enablePredictiveBraking && !transportMode.continuousMovement) {
					railSpeed = getPredictiveBrakingSpeedInPreSim(path, i, railProgress, distanceSum2, speed, currentRailSpeed, decelerationConstant);
				} else {
					railSpeed = currentRailSpeed;
				}
				if (enableDelayedAcceleration && !transportMode.continuousMovement) {
					final float tailRailSpeed = getDelayedAccelerationSpeedInPreSim(path, segmentEnds, railProgress - (double) trainCars * spacing, i);
					if (tailRailSpeed < railSpeed) {
						railSpeed = tailRailSpeed;
					}
				}
				// The stopping-distance check is a *braking* question, so it is asked of the braking rate. With
				// a deceleration below the acceleration, the train has to start slowing down further out, and
				// this is the only place the predicted arrival times learn that.
				final int speedChange;
				if (speed > railSpeed || nextStoppingDistance - railProgress + 1 < 0.5 * speed * speed / decelerationConstant) {
					// The floor keeps the speed strictly positive so that later segments can divide by it,
					// and it is the braking rate itself, exactly as it was when the two rates were one value.
					speed = Math.max(speed - decelerationConstant, decelerationConstant);
					speedChange = -1;
				} else if (speed < railSpeed) {
					speed = Math.min(speed + accelerationConstant, railSpeed);
					speedChange = 1;
				} else {
					speedChange = 0;
				}

				if (timeSegments.isEmpty() || timeSegments.get(timeSegments.size() - 1).speedChange != speedChange) {
					timeSegments.add(new TimeSegment(railProgress, speed, time, speedChange, accelerationConstant, decelerationConstant));
				}

				railProgress = Math.min(railProgress + speed, distanceSum2);
				time++;

				final TimeSegment timeSegment = timeSegments.get(timeSegments.size() - 1);
				timeSegment.endRailProgress = railProgress;
				timeSegment.endTime = time;
				timeSegment.savedRailBaseId = nextStoppingDistance != distanceSum1 && railProgress == distanceSum2 && pathData.dwellTime > 0 ? pathData.savedRailBaseId : 0;
			}

			time += (pathData.dwellTime + pathData.adcTime) * 5;

			// The train came to a stand at a platform here, so it leaves the stop like any other departure:
			// TrainServer.startUp() puts the speed back to ACCELERATION_DEFAULT. Keeping the residual braking
			// speed instead made every following inter-station run faster in this pre-simulation than in the
			// live simulation, which is what made the platform countdowns run early - and the error grew with
			// every stop of the route, because the leftover speed was carried on to the next leg.
			// The stop has to be recognised exactly like the one that marks the time segment above, otherwise
			// a segment the train merely rolls through would be treated as a departure.
			if (pathData.dwellTime > 0 && railProgress == distanceSum2 && nextStoppingDistance == distanceSum2) {
				speed = Train.ACCELERATION_DEFAULT;
			}

			if (pathData.savedRailBaseId != 0) {
				if (savedRailBaseIdOld != 0) {
					if (!platformTimes.containsKey(savedRailBaseIdOld)) {
						platformTimes.put(savedRailBaseIdOld, new HashMap<>());
					}
					platformTimes.get(savedRailBaseIdOld).put(pathData.savedRailBaseId, time - timeOld);
				}
				savedRailBaseIdOld = pathData.savedRailBaseId;
				timeOld = time;
			}

			time += (pathData.dwellTime + pathData.adcTime) * 5;

			if (i + 1 < path.size() && pathData.isOppositeRail(path.get(i + 1))) {
				railProgress += spacing * trainCars;
			}
		}
	}

	private static float getPredictiveBrakingSpeedInPreSim(List<PathData> path, int currentIndex, double railProgress, double currentSegmentEnd, float currentSpeed, float currentRailSpeed, float decelerationConstant) {
		double cumulativeDistance = currentSegmentEnd - railProgress;

		float effectiveSpeed = currentRailSpeed;
		final float maxLookahead = 0.5F * currentRailSpeed * currentRailSpeed / decelerationConstant + 10;

		for (int i = currentIndex + 1; i < path.size(); i++) {
			final PathData pd = path.get(i);
			final RailType rt = pd.rail.railType;

			if (rt.canAccelerate) {
				final float segSpeed = rt.maxBlocksPerTick;
				if (segSpeed < currentSpeed) {
					// Exact braking distance: decelerate from current speed to target segment speed
					final double requiredBrakingDist = 0.5 * (currentSpeed * currentSpeed - segSpeed * segSpeed) / decelerationConstant;
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

	private static float getDelayedAccelerationSpeedInPreSim(List<PathData> path, double[] segmentEnds, double tailRailProgress, int headIndex) {
		int tailIndex = headIndex;
		for (int i = 0; i <= headIndex; i++) {
			if (tailRailProgress < segmentEnds[i]) {
				tailIndex = i;
				break;
			}
		}

		float minRailSpeed = Float.MAX_VALUE;
		for (int i = tailIndex; i < headIndex; i++) {
			final RailType railType = path.get(i).rail.railType;
			if (railType.canAccelerate) {
				minRailSpeed = Math.min(minRailSpeed, railType.maxBlocksPerTick);
			}
		}

		return minRailSpeed;
	}

	public static class TimeSegment {

		public double endRailProgress;
		public long savedRailBaseId;
		public long routeId;
		public int currentStationIndex;
		public float endTime;

		public final double startRailProgress;
		private final float startSpeed;
		private final float startTime;
		private final int speedChange;
		private final float accelerationConstant;
		/**
		 * The braking rate of the segment. Only used when {@link #speedChange} is negative; keeping both rates
		 * on the segment means the predicted arrival time of a stop is computed with the same physics the
		 * train will actually use, instead of assuming the two rates are equal.
		 */
		private final float decelerationConstant;

		private TimeSegment(double startRailProgress, float startSpeed, float startTime, int speedChange, float accelerationConstant) {
			this(startRailProgress, startSpeed, startTime, speedChange, accelerationConstant, accelerationConstant);
		}

		private TimeSegment(double startRailProgress, float startSpeed, float startTime, int speedChange, float accelerationConstant, float decelerationConstant) {
			this.startRailProgress = startRailProgress;
			this.startSpeed = startSpeed;
			this.startTime = startTime;
			this.speedChange = Integer.compare(speedChange, 0);
			final float tempAccelerationConstant = RailwayData.round(accelerationConstant, Train.ACCELERATION_DECIMAL_PLACES);
			this.accelerationConstant = tempAccelerationConstant < Train.MIN_ACCELERATION ? Train.ACCELERATION_DEFAULT : tempAccelerationConstant;
			final float tempDecelerationConstant = RailwayData.round(decelerationConstant, Train.ACCELERATION_DECIMAL_PLACES);
			this.decelerationConstant = tempDecelerationConstant < Train.MIN_ACCELERATION ? this.accelerationConstant : tempDecelerationConstant;
		}

		public double getTime(double railProgress) {
			final double distance = railProgress - startRailProgress;
			if (speedChange == 0) {
				return startTime + distance / startSpeed;
			} else {
				// A braking segment spends the braking rate, a powering segment the acceleration rate. Using
				// one rate for both is what made the predicted arrival time drift away from the real one as
				// soon as the two were set apart.
				final float acceleration = speedChange * (speedChange > 0 ? accelerationConstant : decelerationConstant);
				return startTime + (distance == 0 ? 0 : (Math.sqrt(2 * acceleration * distance + startSpeed * startSpeed) - startSpeed) / acceleration);
			}
		}
	}
}
