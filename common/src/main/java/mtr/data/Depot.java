package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.packet.PacketTrainDataGuiServer;
import mtr.path.PathData;
import mtr.path.PathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Depot extends AreaBase implements IReducedSaveData {

	public int clientPathGenerationSuccessfulSegments;
	/**
	 * How many platforms the server actually used for the last path generation. It is sent with the
	 * result because the depot screen must not recompute that number from its own caches: a route the
	 * client has not received yet would be counted as zero platforms, and a half-generated path would
	 * then be displayed as "path created successfully".
	 */
	public int clientPathGenerationTotalSegments;
	/** One of the PATH_REASON_* constants, sent with the last path generation result. */
	public int clientPathGenerationReason;
	/**
	 * Name of the siding that produced the reported result, sent with it. With several sidings in one
	 * depot the depot-wide result is the worst one, and without this the player cannot tell which siding
	 * to look at. Empty when the generation succeeded or no siding was involved.
	 */
	public String clientPathGenerationSiding = "";

	/** {@link #clientPathGenerationSuccessfulSegments} describes the result. */
	public static final int PATH_REASON_SEGMENTS = 0;
	/** No siding of this transport mode is inside the depot region, so nothing was generated at all. */
	public static final int PATH_REASON_NO_SIDING = 1;
	/** The generation could not run or threw; the server log holds the stack trace. */
	public static final int PATH_REASON_NOT_GENERATED = 2;
	/**
	 * Result reported through the legacy {@code generatePathS2C} entry point, which only carries the
	 * segment count. The client then falls back to the platform count it computes from its own caches,
	 * exactly as every result did before this feature existed.
	 */
	public static final int PATH_REASON_LEGACY = 3;

	/**
	 * Bumped every time a path generation starts for this depot. Each generation remembers its own value
	 * and only publishes its result while that value is still the current one: PathFinder never polls the
	 * interrupt flag, so a restarted search runs to the end and would otherwise report a result computed
	 * from the rails as they were before the restart.
	 * <p>
	 * Private, so it adds no API surface; volatile because the worker thread reads it.
	 */
	private volatile int pathGenerationId;

	public long lastDeployedMillis;
	public boolean useRealTime;
	public boolean repeatInfinitely;
	public int cruisingAltitude = DEFAULT_CRUISING_ALTITUDE;
	private int deployIndex;
	private int departureOffset;
	private boolean isDirty = true;

	public final List<Long> routeIds = new ArrayList<>();
	public final Map<Long, Map<Long, Float>> platformTimes = new HashMap<>();
	public final List<Integer> departures = new ArrayList<>();
	public final List<Integer> tempDepartures = new ArrayList<>();

	private final int[] frequencies = new int[HOURS_IN_DAY];
	private final Map<Long, TrainServer> deployableSidings = new HashMap<>();

	public static final int HOURS_IN_DAY = 24;
	public static final int TRAIN_FREQUENCY_MULTIPLIER = 4;
	public static final int TICKS_PER_HOUR = 1000;
	public static final int MILLIS_PER_TICK = 50;
	public static final int MILLISECONDS_PER_DAY = HOURS_IN_DAY * 60 * 60 * 1000;
	public static final int DEFAULT_CRUISING_ALTITUDE = 256;
	private static final int TICKS_PER_DAY = HOURS_IN_DAY * TICKS_PER_HOUR;
	private static final int CONTINUOUS_MOVEMENT_FREQUENCY = 8000;
	private static final int THRESHOLD_ABOVE_MAX_BUILD_HEIGHT = 64;

	private static final String KEY_ROUTE_IDS = "route_ids";
	private static final String KEY_USE_REAL_TIME = "use_real_time";
	private static final String KEY_FREQUENCIES = "frequencies";
	private static final String KEY_DEPARTURES = "departures";
	private static final String KEY_LAST_DEPLOYED = "last_deployed";
	private static final String KEY_DEPLOY_INDEX = "deploy_index";
	private static final String KEY_REPEAT_INFINITELY = "repeat_infinitely";
	private static final String KEY_CRUISING_ALTITUDE = "cruising_altitude";

	public Depot(TransportMode transportMode) {
		super(transportMode);
	}

	public Depot(long id, TransportMode transportMode) {
		super(id, transportMode);
	}

	public Depot(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		messagePackHelper.iterateArrayValue(KEY_ROUTE_IDS, routeId -> routeIds.add(routeId.asIntegerValue().asLong()));
		useRealTime = messagePackHelper.getBoolean(KEY_USE_REAL_TIME);

		try {
			final ArrayValue frequenciesArray = map.get(KEY_FREQUENCIES).asArrayValue();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = frequenciesArray.get(i).asIntegerValue().asInt();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		messagePackHelper.iterateArrayValue(KEY_DEPARTURES, departure -> departures.add(departure.asIntegerValue().asInt()));

		deployIndex = messagePackHelper.getInt(KEY_DEPLOY_INDEX);
		repeatInfinitely = messagePackHelper.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = messagePackHelper.getInt(KEY_CRUISING_ALTITUDE);
		lastDeployedMillis = messagePackHelper.getLong(KEY_LAST_DEPLOYED);
	}

	@Deprecated
	public Depot(CompoundTag compoundTag) {
		super(compoundTag);

		final long[] routeIdsArray = compoundTag.getLongArray(KEY_ROUTE_IDS);
		for (final long routeId : routeIdsArray) {
			routeIds.add(routeId);
		}

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = compoundTag.getInt(KEY_FREQUENCIES + i);
		}

		lastDeployedMillis = compoundTag.getLong(KEY_LAST_DEPLOYED);
		deployIndex = compoundTag.getInt(KEY_DEPLOY_INDEX);
		repeatInfinitely = compoundTag.getBoolean(KEY_REPEAT_INFINITELY);
		cruisingAltitude = compoundTag.getInt(KEY_CRUISING_ALTITUDE);
	}

	public Depot(FriendlyByteBuf packet) {
		super(packet);

		final int routeIdCount = packet.readInt();
		for (int i = 0; i < routeIdCount; i++) {
			routeIds.add(packet.readLong());
		}

		useRealTime = packet.readBoolean();

		for (int i = 0; i < HOURS_IN_DAY; i++) {
			frequencies[i] = packet.readInt();
		}

		final int departuresCount = packet.readInt();
		for (int i = 0; i < departuresCount; i++) {
			departures.add(packet.readInt());
		}

		lastDeployedMillis = packet.readLong();
		deployIndex = packet.readInt();
		repeatInfinitely = packet.readBoolean();
		cruisingAltitude = packet.readInt();
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		toReducedMessagePack(messagePacker);
		messagePacker.packString(KEY_DEPLOY_INDEX).packInt(deployIndex);
		messagePacker.packString(KEY_LAST_DEPLOYED).packLong(lastDeployedMillis);
	}

	@Override
	public void toReducedMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);

		messagePacker.packString(KEY_ROUTE_IDS).packArrayHeader(routeIds.size());
		for (final long routeId : routeIds) {
			messagePacker.packLong(routeId);
		}

		messagePacker.packString(KEY_USE_REAL_TIME).packBoolean(useRealTime);
		messagePacker.packString(KEY_REPEAT_INFINITELY).packBoolean(repeatInfinitely);
		messagePacker.packString(KEY_CRUISING_ALTITUDE).packInt(cruisingAltitude);

		messagePacker.packString(KEY_FREQUENCIES).packArrayHeader(HOURS_IN_DAY);
		for (int i = 0; i < HOURS_IN_DAY; i++) {
			messagePacker.packInt(frequencies[i]);
		}

		messagePacker.packString(KEY_DEPARTURES).packArrayHeader(departures.size());
		for (final int departure : departures) {
			messagePacker.packInt(departure);
		}
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 7;
	}

	@Override
	public int reducedMessagePackLength() {
		return messagePackLength() - 2;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);

		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);

		packet.writeBoolean(useRealTime);

		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}

		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);

		packet.writeLong(lastDeployedMillis);
		packet.writeInt(deployIndex);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
	}

	@Override
	protected boolean hasTransportMode() {
		return true;
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		if (KEY_FREQUENCIES.equals(key)) {
			name = packet.readUtf(PACKET_STRING_READ_LENGTH);
			color = packet.readInt();
			useRealTime = packet.readBoolean();
			for (int i = 0; i < HOURS_IN_DAY; i++) {
				frequencies[i] = packet.readInt();
			}
			departures.clear();
			final int departuresCount = packet.readInt();
			for (int i = 0; i < departuresCount; i++) {
				departures.add(packet.readInt());
			}
			routeIds.clear();
			final int routeIdCount = packet.readInt();
			for (int i = 0; i < routeIdCount; i++) {
				routeIds.add(packet.readLong());
			}
			repeatInfinitely = packet.readBoolean();
			cruisingAltitude = packet.readInt();
		} else {
			super.update(key, packet);
		}
		isDirty = true;
	}

	public int getFrequency(int index) {
		if (index >= 0 && index < frequencies.length) {
			return frequencies[index];
		} else {
			return 0;
		}
	}

	public void setFrequency(int newFrequency, int index) {
		if (index >= 0 && index < frequencies.length) {
			frequencies[index] = newFrequency;
		}
		isDirty = true;
	}

	public void setData(Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_FREQUENCIES);
		packet.writeUtf(name);
		packet.writeInt(color);
		packet.writeBoolean(useRealTime);
		for (final int frequency : frequencies) {
			packet.writeInt(frequency);
		}
		departures.replaceAll(departure -> departure % MILLISECONDS_PER_DAY);
		departures.removeIf(departure -> departure % 1000 != 0);
		departures.sort(Integer::compareTo);
		packet.writeInt(departures.size());
		departures.forEach(packet::writeInt);
		packet.writeInt(routeIds.size());
		routeIds.forEach(packet::writeLong);
		packet.writeBoolean(repeatInfinitely);
		packet.writeInt(cruisingAltitude);
		sendPacket.accept(packet);
	}

	public void generateMainRoute(MinecraftServer minecraftServer, Level world, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails, Set<Siding> sidings, Consumer<Thread> callback) {
		// Signature deliberately left as it always was: addons compiled against the previous build keep
		// resolving this method, and the staleness guard below lives entirely inside this class.
		final int generationId = ++pathGenerationId;
		final List<SavedRailBase> platformsInRoute = new ArrayList<>();

		routeIds.forEach(routeId -> {
			final Route route = dataCache.routeIdMap.get(routeId);
			if (route != null) {
				route.platformIds.forEach(platformId -> {
					final Platform platform = dataCache.platformIdMap.get(platformId.platformId);
					if (platform != null && (platformsInRoute.isEmpty() || platform.id != platformsInRoute.get(platformsInRoute.size() - 1).id)) {
						platformsInRoute.add(platform);
					}
				});
			}
		});

		final boolean useFastSpeed = cruisingAltitude >= world.getMaxBuildHeight() + THRESHOLD_ABOVE_MAX_BUILD_HEIGHT;

		final Thread thread = new Thread(() -> {
			try {
				final List<PathData> tempPath = new ArrayList<>();
				final int successfulSegmentsMain = PathFinder.findPath(tempPath, rails, platformsInRoute, 1, cruisingAltitude, useFastSpeed);

				for (PathData pathData : tempPath) {
					if (pathData.savedRailBaseId != 0 && pathData.dwellTime > 0) {
						Platform platform = dataCache.platformIdMap.get(pathData.savedRailBaseId);
						if (platform != null) {
							pathData.adcTime = platform.getAdcTime();
						}
					}
				}

				final int totalSegments = platformsInRoute.size();
				final int[] successfulSegments = new int[]{Integer.MAX_VALUE};
				final int[] sidingsProcessed = new int[]{0};
				final List<String> sidingResults = new ArrayList<>();
				final String[] reportedSiding = new String[]{""};

				sidings.forEach(siding -> {
					final BlockPos sidingMidPos = siding.getMidPos();
					if (siding.isTransportMode(transportMode) && inArea(sidingMidPos.getX(), sidingMidPos.getZ())) {
						sidingsProcessed[0]++;
						final SavedRailBase firstPlatform = platformsInRoute.isEmpty() ? null : platformsInRoute.get(0);
						final SavedRailBase lastPlatform = platformsInRoute.isEmpty() ? null : platformsInRoute.get(platformsInRoute.size() - 1);
						final int result = siding.generateRoute(minecraftServer, tempPath, successfulSegmentsMain, rails, firstPlatform, lastPlatform, repeatInfinitely, cruisingAltitude, useFastSpeed);
						sidingResults.add(String.format("%s (siding id %d): %s", siding.name, siding.id, describeResult(dataCache, platformsInRoute, result)));
						if (result < successfulSegments[0]) {
							successfulSegments[0] = result;
							reportedSiding[0] = siding.name;
						}
					}
				});

				// A depot that owns no siding used to keep the Integer.MAX_VALUE sentinel above and ship it
				// to the client, which compares it against its own segment count and prints "path created
				// successfully" although nothing was generated. Report that case as its own reason instead.
				final int reason = sidingsProcessed[0] == 0 ? PATH_REASON_NO_SIDING : PATH_REASON_SEGMENTS;
				final int segments = reason == PATH_REASON_SEGMENTS ? successfulSegments[0] : 0;

				if (generationId != pathGenerationId) {
					// A newer generation for this depot is already running; its result is the one the player
					// asked for, so this stale one must not reach the client.
					System.out.println("Discarding superseded path generation" + (name.isEmpty() ? "" : " for " + name));
					return;
				}

				logPathGenerationResult(dataCache, platformsInRoute, reason, segments, sidingsProcessed[0], sidingResults);
				PacketTrainDataGuiServer.generatePathS2C(world, id, reason, segments, totalSegments, reason == PATH_REASON_SEGMENTS && segments < totalSegments + 2 ? reportedSiding[0] : "");
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("Failed to generate path" + (name.isEmpty() ? "" : " for " + name));
				if (generationId == pathGenerationId) {
					PacketTrainDataGuiServer.generatePathS2C(world, id, PATH_REASON_NOT_GENERATED, 0, platformsInRoute.size(), "");
				}
			}
		});
		callback.accept(thread);
		thread.start();
	}

	/**
	 * Explains a finished path generation in the server log. The depot screen can only name one leg, so on a
	 * long route (or a depot with several sidings) the console is where the whole picture is written down:
	 * the depot-level result plus one line per siding, because the depot-wide result is only the worst one.
	 */
	private void logPathGenerationResult(DataCache dataCache, List<SavedRailBase> platformsInRoute, int reason, int successfulSegments, int sidingsProcessed, List<String> sidingResults) {
		final String depotName = name.isEmpty() ? "(unnamed depot)" : name;

		if (reason == PATH_REASON_NO_SIDING) {
			System.out.println(String.format("Path generation for %s failed: no siding of this transport mode inside the depot area (checked %d siding(s)), so no path was generated - check that the depot region covers the siding rail", depotName, sidingsProcessed));
			return;
		}

		System.out.println(String.format("Path generation for %s: %s", depotName, describeResult(dataCache, platformsInRoute, successfulSegments)));

		// Only worth listing when there is more than one siding: with one siding the line above says it all.
		if (sidingResults.size() > 1) {
			sidingResults.forEach(sidingResult -> System.out.println("    " + sidingResult));
		}
	}

	/** The outcome of one generation result, in words, for the server log. */
	private static String describeResult(DataCache dataCache, List<SavedRailBase> platformsInRoute, int successfulSegments) {
		final int totalSegments = platformsInRoute.size();

		if (successfulSegments >= totalSegments + 2) {
			return String.format("all %d platform(s) connected", totalSegments);
		} else if (successfulSegments == 0) {
			return "no platform of the route still exists";
		} else if (successfulSegments == 1) {
			return String.format("no path from the depot to %s", totalSegments == 0 ? "the first platform" : describePlatform(dataCache, platformsInRoute.get(0)));
		} else if (successfulSegments >= totalSegments + 1) {
			return String.format("no path from %s back to the depot", describePlatform(dataCache, platformsInRoute.get(totalSegments - 1)));
		} else {
			return String.format("no path between %s and %s", describePlatform(dataCache, platformsInRoute.get(successfulSegments - 2)), describePlatform(dataCache, platformsInRoute.get(successfulSegments - 1)));
		}
	}

	private static String describePlatform(DataCache dataCache, SavedRailBase platform) {
		final Station station = dataCache.platformIdToStation.get(platform.id);
		return String.format("%s%s (platform id %d)", platform.name, station == null ? "" : " at " + station.name, platform.id);
	}

	public void requestDeploy(long sidingId, TrainServer train) {
		deployableSidings.put(sidingId, train);
	}

	public void deployTrain(RailwayData railwayData, Level world) {
		if (isDirty) {
			generateTempDepartures(world);
		}

		if (!deployableSidings.isEmpty() && getMillisUntilDeploy(1) == 0) {
			final List<Siding> sidingsInDepot = railwayData.sidings.stream().filter(siding -> {
				final BlockPos sidingPos = siding.getMidPos();
				return siding.isTransportMode(transportMode) && inArea(sidingPos.getX(), sidingPos.getZ());
			}).sorted().collect(Collectors.toList());

			final int sidingsInDepotSize = sidingsInDepot.size();
			for (int i = deployIndex; i < deployIndex + sidingsInDepotSize; i++) {
				final TrainServer train = deployableSidings.get(sidingsInDepot.get(i % sidingsInDepotSize).id);
				if (train != null) {
					lastDeployedMillis = System.currentTimeMillis();
					deployIndex++;
					if (deployIndex >= sidingsInDepotSize) {
						deployIndex = 0;
					}
					train.deployTrain();
					break;
				}
			}
		}

		departureOffset = 0;
		deployableSidings.clear();
	}

	public int getNextDepartureMillis() {
		departureOffset++;
		final int millisUntilDeploy = getMillisUntilDeploy(departureOffset);
		return millisUntilDeploy >= 0 ? millisUntilDeploy : -1;
	}

	public int getMillisUntilDeploy(int offset) {
		return getMillisUntilDeploy(offset, 0);
	}

	public int getMillisUntilDeploy(int offset, int currentTimeOffset) {
		final long millis = (System.currentTimeMillis() + currentTimeOffset) % MILLISECONDS_PER_DAY;

		// 单班次特殊处理：原逻辑的"发车窗口"覆盖整24小时，首次发车后lastDeployedMillis
		// 始终落在窗口内，导致后续检查全部拒绝。改为按日历日判断是否当天已发车。
		if (tempDepartures.size() == 1) {
			final long thisDeparture = tempDepartures.get(0);
			final long now = System.currentTimeMillis() + currentTimeOffset;
			final long tod = now % MILLISECONDS_PER_DAY;

			if (offset > 1) {
				final int daysAfter = offset - 1;
				return (int) (wrapTime(thisDeparture + (long) daysAfter * MILLISECONDS_PER_DAY, millis) - millis);
			}

			if (tod < thisDeparture) {
				return (int) (thisDeparture - tod);
			}
			final long todayDay = now / MILLISECONDS_PER_DAY;
			final long lastDeployedDay = (lastDeployedMillis + currentTimeOffset) / MILLISECONDS_PER_DAY;
			if (lastDeployedDay >= todayDay) {
				return (int) (thisDeparture + MILLISECONDS_PER_DAY - tod);
			}
			return 0;
		}

		for (int i = 0; i < tempDepartures.size(); i++) {
			final long thisDeparture = tempDepartures.get(i);
			final long nextDeparture = wrapTime(tempDepartures.get((i + 1) % tempDepartures.size()), thisDeparture);
			final long newMillis = wrapTime(millis, thisDeparture);
			if (newMillis > thisDeparture && newMillis <= nextDeparture) {
				if (offset > 1) {
					if (offset <= tempDepartures.size()) {
						return (int) (wrapTime(tempDepartures.get((i + offset) % tempDepartures.size()), millis) - millis);
					}
				} else {
					return wrapTime(lastDeployedMillis + currentTimeOffset, newMillis) - MILLISECONDS_PER_DAY >= thisDeparture ? (int) (nextDeparture - newMillis) : 0;
				}
			}
		}
		return -1;
	}

	public void generateTempDepartures(Level world) {
		tempDepartures.clear();
		if (useRealTime && !transportMode.continuousMovement) {
			tempDepartures.addAll(departures);
		} else if (world != null) {
			int millisOffset = 0;
			while (millisOffset < MILLISECONDS_PER_DAY) {
				final int tempFrequency = getFrequency(getHour(world, millisOffset));
				if (tempFrequency == 0 && !transportMode.continuousMovement) {
					millisOffset = (int) (Math.floor((float) millisOffset / MILLIS_PER_TICK / TICKS_PER_HOUR) + 1) * TICKS_PER_HOUR * MILLIS_PER_TICK;
				} else {
					tempDepartures.add((int) ((lastDeployedMillis + millisOffset) % MILLISECONDS_PER_DAY));
					millisOffset += transportMode.continuousMovement ? CONTINUOUS_MOVEMENT_FREQUENCY : TICKS_PER_HOUR * MILLIS_PER_TICK * TRAIN_FREQUENCY_MULTIPLIER / tempFrequency;
				}
			}
			tempDepartures.sort(Integer::compareTo);
		}
		isDirty = false;
	}

	private static int getHour(Level world, int offsetMillis) {
		return (int) wrapTime(world.getDayTime() + (float) offsetMillis / MILLIS_PER_TICK) / TICKS_PER_HOUR;
	}

	private static float wrapTime(float time) {
		return (time + 6000 + TICKS_PER_DAY) % TICKS_PER_DAY;
	}

	private static long wrapTime(long time, long mustBeGreaterThan) {
		long newTime = time % MILLISECONDS_PER_DAY;
		while (newTime <= mustBeGreaterThan) {
			newTime += MILLISECONDS_PER_DAY;
		}
		return newTime;
	}
}
