package mtr.webdashboard;

import mtr.data.DataCache;
import mtr.data.Depot;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Siding;
import mtr.data.TrainServer;
import mtr.path.PathData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Builds the train snapshot for the web dashboard.
 *
 * <p>Runs on the game thread. Geometry is only computed for the trains that are actually serialised,
 * and the cumulative distances come from the simulation ({@link mtr.data.Train#getIndex} and
 * {@link mtr.data.Train#getDistance}) instead of being rebuilt here.
 */
public final class WebDashboardTrainService {

	public static final int DEFAULT_LIMIT = 16;
	public static final int MAX_LIMIT = 256;
	public static final long BUILD_BUDGET_MILLIS = 50L;
	public static final int PROTOCOL_VERSION = 1;

	private static final int MAX_CARS = 64;
	private static final long NANOS_PER_MILLI = 1000000L;
	private static final long BUDGET_NANOS = BUILD_BUDGET_MILLIS * NANOS_PER_MILLI;

	private WebDashboardTrainService() {
	}

	public record Query(String dimension, int limit, long routeId, long depotId) {
	}

	public record Result(int status, String body) {
	}

	/**
	 * Envelope shared by {@code /api/trains} and the stream, so the two paths cannot drift apart.
	 * {@code total} and {@code truncated} describe the list after filtering and capping.
	 */
	public record SnapshotMeta(int version, long seq, long nowMillis, double hz, double buildMs, int total, boolean truncated, boolean partial) {
	}

	public static Result build(MinecraftServer server, Query query, BooleanSupplier cancelled) {
		final long started = System.nanoTime();
		final long deadline = started + BUDGET_NANOS;

		ServerLevel targetLevel = null;
		for (final ServerLevel level : server.getAllLevels()) {
			if (level.dimension().location().toString().equals(query.dimension())) {
				targetLevel = level;
				break;
			}
		}
		if (targetLevel == null) {
			return new Result(404, "{\"error\":\"world_not_found\"}");
		}

		final RailwayData railwayData = RailwayData.getInstance(targetLevel);
		if (railwayData == null) {
			return new Result(404, "{\"error\":\"world_not_found\"}");
		}

		final DataCache dataCache = railwayData.dataCache;
		final List<Candidate> candidates = new ArrayList<>();
		final Set<Long> seenTrains = new HashSet<>();
		int scanned = 0;
		for (final Siding siding : railwayData.sidings) {
			if (siding == null) {
				continue;
			}
			if ((++scanned & 0x1F) == 0 && expired(deadline, cancelled)) {
				return timeout();
			}
			final Depot depot = dataCache == null ? null : dataCache.sidingIdToDepot.get(siding.id);
			final long depotId = depot == null ? 0 : depot.id;
			if (query.depotId() != 0 && depotId != query.depotId()) {
				continue;
			}
			for (final TrainServer train : siding.getTrains()) {
				if (train == null) {
					continue;
				}
				if (query.routeId() != 0 && train.getRouteId() != query.routeId()) {
					continue;
				}
				if (train.path == null || train.path.isEmpty() || train.spacing <= 0) {
					continue;
				}
				if (seenTrains.add(train.id)) {
					candidates.add(new Candidate(siding, train));
				}
			}
		}

		candidates.sort(Comparator.comparingLong(candidate -> candidate.train().id));
		final int total = candidates.size();
		final boolean truncated = total > query.limit();
		final int rendered = Math.min(total, query.limit());

		final List<TrainFrame> trains = new ArrayList<>(rendered);
		for (int i = 0; i < rendered; i++) {
			if (expired(deadline, cancelled)) {
				return timeout();
			}
			final Candidate candidate = candidates.get(i);
			trains.add(computeFrame(candidate.train(), candidate.siding(), dataCache));
		}

		final double buildMs = round((System.nanoTime() - started) / 1_000_000.0, 3);
		final SnapshotMeta meta = new SnapshotMeta(PROTOCOL_VERSION, 0L, System.currentTimeMillis(), 0, buildMs, total, truncated, false);
		return new Result(200, writeFull(trains, meta));
	}

	/**
	 * Builds one train's frame. The caller guarantees a non-empty path and positive spacing.
	 */
	public static TrainFrame computeFrame(TrainServer train, Siding siding, DataCache dataCache) {
		final List<PathData> path = train.path;
		final int cars = Math.min(Math.max(train.trainCars, 1), MAX_CARS);
		final double[] centers = new double[cars * 2];
		for (int i = 0; i < cars; i++) {
			final int car = train.isReversed() ? cars - 1 - i : i;
			final double progress = Math.max(train.getRailProgress() - (car + 0.5) * (double) train.spacing, 0);
			final int index = train.getIndex(progress, false);
			final double local = progress - train.getDistance(index - 1);
			final Vec3 position = path.get(index).rail.getPosition(local);
			centers[i * 2] = position.x;
			centers[i * 2 + 1] = position.z;
		}

		double heading = 0;
		if (cars == 1) {
			final Vec3 head = positionAt(train, Math.max(train.getRailProgress(), 0));
			final Vec3 tail = positionAt(train, Math.max(train.getRailProgress() - train.spacing, 0));
			if (head != null && tail != null && (head.x != tail.x || head.z != tail.z)) {
				heading = Math.atan2(head.z - tail.z, head.x - tail.x);
			}
		}

		final Route route = dataCache == null ? null : dataCache.routeIdMap.get(train.getRouteId());
		final Depot depot = dataCache == null ? null : dataCache.sidingIdToDepot.get(train.sidingId);
		final long nextStopId = train.getNextStopId();
		final TrainFrame.StopKind nextStopKind = nextStopId == 0
				? TrainFrame.StopKind.NONE
				: train.isNextStopPlatform(dataCache) ? TrainFrame.StopKind.PLATFORM : TrainFrame.StopKind.SIDING;

		return new TrainFrame(
				train.id,
				train.sidingId,
				train.getRouteId(),
				depot == null ? 0 : depot.id,
				nextStopId,
				nextStopKind,
				cars,
				train.spacing,
				train.width,
				route == null ? siding.color : route.color,
				train.getSpeed(),
				train.getDoorValue(),
				train.getIsOnRoute(),
				train.isCurrentlyManual(),
				train.isReversed(),
				heading,
				centers);
	}

	private static Vec3 positionAt(TrainServer train, double progress) {
		final List<PathData> path = train.path;
		if (path == null || path.isEmpty()) {
			return null;
		}
		final int index = train.getIndex(progress, false);
		return path.get(index).rail.getPosition(progress - train.getDistance(index - 1));
	}

	public static String writeFull(List<TrainFrame> trains, SnapshotMeta meta) {
		final StringBuilder builder = header(meta, Math.max(256, 256 + trains.size() * 256));
		builder.append(",\"trains\":[");
		boolean first = true;
		for (final TrainFrame train : trains) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			appendTrain(builder, train);
		}
		builder.append("]}");
		return builder.toString();
	}

	public static String writeFrame(List<TrainFrame> changed, Set<Long> gone, SnapshotMeta meta) {
		final StringBuilder builder = header(meta, Math.max(256, 256 + changed.size() * 256));
		builder.append(",\"gone\":[");
		boolean firstGone = true;
		if (gone != null) {
			for (final Long id : gone) {
				if (id == null) {
					continue;
				}
				if (!firstGone) {
					builder.append(',');
				}
				firstGone = false;
				builder.append('"').append(id).append('"');
			}
		}
		builder.append("],\"trains\":[");
		boolean first = true;
		for (final TrainFrame train : changed) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			appendTrain(builder, train);
		}
		builder.append("]}");
		return builder.toString();
	}

	private static StringBuilder header(SnapshotMeta meta, int capacity) {
		final StringBuilder builder = new StringBuilder(capacity);
		builder.append("{\"v\":").append(meta.version());
		builder.append(",\"seq\":").append(meta.seq());
		builder.append(",\"now\":").append(meta.nowMillis());
		builder.append(",\"hz\":");
		appendCoordinate(builder, meta.hz(), 2);
		builder.append(",\"buildMs\":");
		appendCoordinate(builder, meta.buildMs(), 3);
		builder.append(",\"total\":").append(meta.total());
		builder.append(",\"truncated\":").append(meta.truncated());
		builder.append(",\"partial\":").append(meta.partial());
		return builder;
	}

	private static void appendTrain(StringBuilder builder, TrainFrame train) {
		builder.append("{\"id\":\"").append(train.id()).append('"');
		builder.append(",\"sidingId\":\"").append(train.sidingId()).append('"');
		builder.append(",\"routeId\":");
		appendId(builder, train.routeId());
		builder.append(",\"depotId\":");
		appendId(builder, train.depotId());
		builder.append(",\"nextStopId\":");
		appendId(builder, train.nextStopId());
		builder.append(",\"nextStopKind\":\"").append(train.nextStopKind().json()).append('"');
		builder.append(",\"cars\":").append(train.cars());
		builder.append(",\"spacing\":").append(train.spacing());
		builder.append(",\"width\":").append(train.width());
		builder.append(",\"color\":").append(train.color());
		builder.append(",\"speed\":");
		appendCoordinate(builder, train.speed(), 3);
		builder.append(",\"doors\":");
		appendCoordinate(builder, train.doors(), 3);
		builder.append(",\"onRoute\":").append(train.onRoute());
		builder.append(",\"manual\":").append(train.manual());
		builder.append(",\"reversed\":").append(train.reversed());
		builder.append(",\"heading\":");
		appendCoordinate(builder, train.heading(), 3);
		builder.append(",\"centers\":[");
		final double[] centers = train.centers();
		for (int i = 0; i < centers.length; i++) {
			if (i > 0) {
				builder.append(',');
			}
			appendCoordinate(builder, centers[i], 3);
		}
		builder.append("]}");
	}

	private static void appendId(StringBuilder builder, long id) {
		// Saved rail ids are random longs and may be negative; only zero means "none".
		if (id == 0) {
			builder.append("null");
		} else {
			builder.append('"').append(id).append('"');
		}
	}

	private static void appendCoordinate(StringBuilder builder, double value, int decimals) {
		long factor = 1L;
		for (int i = 0; i < decimals; i++) {
			factor *= 10;
		}
		long scaled = Math.round(value * factor);
		if (scaled == 0) {
			builder.append('0');
			return;
		}
		if (scaled < 0) {
			builder.append('-');
			scaled = -scaled;
		}
		builder.append(scaled / factor);
		final long fraction = scaled % factor;
		if (fraction != 0) {
			builder.append('.');
			final StringBuilder digits = new StringBuilder();
			long value2 = fraction;
			while (value2 > 0) {
				digits.append((char) ('0' + value2 % 10));
				value2 /= 10;
			}
			for (int i = digits.length(); i < decimals; i++) {
				builder.append('0');
			}
			builder.append(digits.reverse());
		}
	}

	private static boolean expired(long deadline, BooleanSupplier cancelled) {
		return System.nanoTime() > deadline || cancelled.getAsBoolean();
	}

	private static Result timeout() {
		return new Result(504, "{\"error\":\"trains_timeout\"}");
	}

	private static double round(double value, int decimals) {
		double factor = 1;
		for (int i = 0; i < decimals; i++) {
			factor *= 10;
		}
		return Math.round(value * factor) / factor;
	}

	private record Candidate(Siding siding, TrainServer train) {
	}
}
