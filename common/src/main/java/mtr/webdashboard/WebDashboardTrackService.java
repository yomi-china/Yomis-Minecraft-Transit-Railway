package mtr.webdashboard;

import mtr.data.Platform;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Builds the track overlay response for the web dashboard.
 *
 * <p>The whole build runs on the game thread, so it is governed by a hard cooperative deadline:
 * every scan/sample/assembly loop checks the clock and gives up with a 504 rather than risking a
 * tick spike. There is deliberately no watchdog thread: interrupting the server thread could throw
 * an interrupt into arbitrary game code. A single-flight gate in the servlet ensures builds never
 * pile up on the game thread.
 */
public final class WebDashboardTrackService {

	public static final int DEFAULT_DETAIL = 1;
	public static final long BUILD_BUDGET_MILLIS = 50L;
	public static final double MAX_SPAN = 2097152.0;
	public static final int MAX_RENDER_RAILS = 5000;
	public static final int MAX_POINTS = 30000;
	public static final int MAX_POINTS_PER_RAIL = 1024;

	private static final double MAX_COORDINATE = 30000000.0;
	private static final double CURVE_MARGIN = 64.0;
	private static final double[] DETAIL_STEPS = {4.0, 1.0, 0.5};
	private static final long NANOS_PER_MILLI = 1000000L;
	private static final long BUDGET_NANOS = BUILD_BUDGET_MILLIS * NANOS_PER_MILLI;

	private WebDashboardTrackService() {
	}

	public record Query(String dimension, double minX, double minZ, double maxX, double maxZ, int detail, String mode) {
	}

	public record Result(int status, String body) {
	}

	public static boolean isValidCoordinate(double value) {
		return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
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

		final Map<Long, Long> platformByPos = new HashMap<>();
		int platformsScanned = 0;
		for (final Platform platform : railwayData.platforms) {
			if ((++platformsScanned & 0x3F) == 0 && expired(deadline, cancelled)) {
				return timeout(started);
			}
			for (final BlockPos pos : platform.getOrderedPositions(RailwayData.newBlockPos(0, 0, 0), false)) {
				if (pos != null) {
					platformByPos.putIfAbsent(pos.asLong(), platform.id);
				}
			}
		}

		if (expired(deadline, cancelled)) {
			return timeout(started);
		}

		final List<Candidate> candidates = new ArrayList<>();
		final Map<RailType, Integer> railTypes = new LinkedHashMap<>();
		final Map<BlockPos, Map<BlockPos, Rail>> railsMap = railwayData.getRailsMap();
		int totalRails = 0;
		int skipped = 0;
		long scanned = 0;
		double totalLength = 0;

		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> outer : railsMap.entrySet()) {
			final BlockPos start = outer.getKey();
			final Map<BlockPos, Rail> connections = outer.getValue();
			if (start == null || connections == null) {
				continue;
			}
			for (final Map.Entry<BlockPos, Rail> inner : connections.entrySet()) {
				scanned++;
				if ((scanned & 0x3F) == 0 && expired(deadline, cancelled)) {
					return timeout(started);
				}

				final Rail rail = inner.getValue();
				if (rail == null || rail.railType == RailType.NONE) {
					continue;
				}
				if (query.mode() != null && !query.mode().equals(rail.transportMode.name())) {
					continue;
				}
				final BlockPos end = inner.getKey();
				if (end == null || !coarseIntersects(start, end, query)) {
					continue;
				}
				if (isReverseDuplicate(railsMap, start, end)) {
					continue;
				}

				final double length = rail.getLength();
				if (!Double.isFinite(length) || length <= 0) {
					skipped++;
					continue;
				}

				totalRails++;
				if (totalRails > MAX_RENDER_RAILS) {
					if (expired(deadline, cancelled)) {
						return timeout(started);
					}
					return tooDense(started, query, totalRails, skipped);
				}
				totalLength += length;
				railTypes.put(rail.railType, rail.railType.color);
				candidates.add(new Candidate(start, end, rail, length));
			}
		}

		final int decimals = query.detail() >= 2 ? 2 : 1;
		final double coordinateScale = decimals == 2 ? 100.0 : 10.0;
		final double baseStep = DETAIL_STEPS[Math.max(0, Math.min(2, query.detail()))];
		final double step = Math.max(baseStep, totalLength / (double) MAX_POINTS);

		final List<RenderedRail> rendered = new ArrayList<>(candidates.size());
		int renderedPoints = 0;
		for (final Candidate candidate : candidates) {
			if (expired(deadline, cancelled)) {
				return timeout(started);
			}

			final List<double[]> points = sampleRail(candidate.rail(), candidate.length(), step, coordinateScale, deadline, cancelled);
			if (points == null) {
				return timeout(started);
			}
			if (points.size() < 2 || !polylineIntersects(points, query)) {
				skipped++;
				continue;
			}

			final Long platformId = platformIdAt(platformByPos, candidate.start(), candidate.end());
			rendered.add(new RenderedRail(candidate.rail(), points, platformId == null ? "" : String.valueOf(platformId)));
			renderedPoints += points.size();
		}

		if (expired(deadline, cancelled)) {
			return timeout(started);
		}

		final StringBuilder builder = new StringBuilder(256 + rendered.size() * 96 + renderedPoints * 14);
		appendHeader(builder, query, false, totalRails, rendered.size(), renderedPoints, skipped, railTypes);
		for (int i = 0; i < rendered.size(); i++) {
			if ((i & 0x1F) == 0 && expired(deadline, cancelled)) {
				return timeout(started);
			}
			if (i > 0) {
				builder.append(',');
			}
			appendRail(builder, rendered.get(i), decimals);
		}
		builder.append("],\"timeMs\":").append(elapsedMillis(started)).append('}');

		if (expired(deadline, cancelled)) {
			return timeout(started);
		}
		return new Result(200, builder.toString());
	}

	private static Result tooDense(long started, Query query, int totalRails, int skipped) {
		final StringBuilder builder = new StringBuilder(512);
		appendHeader(builder, query, true, totalRails, 0, 0, skipped, Collections.emptyMap());
		builder.append("],\"timeMs\":").append(elapsedMillis(started)).append('}');
		return new Result(200, builder.toString());
	}

	private static Result timeout(long started) {
		return new Result(504, "{\"error\":\"tracks_timeout\",\"budgetMs\":" + BUILD_BUDGET_MILLIS
				+ ",\"elapsedMs\":" + elapsedMillis(started) + "}");
	}

	private static boolean expired(long deadline, BooleanSupplier cancelled) {
		return (cancelled != null && cancelled.getAsBoolean()) || System.nanoTime() > deadline;
	}

	private static long elapsedMillis(long started) {
		return Math.max(0L, (System.nanoTime() - started) / NANOS_PER_MILLI);
	}

	private static boolean coarseIntersects(BlockPos start, BlockPos end, Query query) {
		final double minX = Math.min(start.getX(), end.getX()) - CURVE_MARGIN;
		final double maxX = Math.max(start.getX(), end.getX()) + CURVE_MARGIN;
		final double minZ = Math.min(start.getZ(), end.getZ()) - CURVE_MARGIN;
		final double maxZ = Math.max(start.getZ(), end.getZ()) + CURVE_MARGIN;
		return maxX >= query.minX() && minX <= query.maxX() && maxZ >= query.minZ() && minZ <= query.maxZ();
	}

	/**
	 * MTR stores a two-way connection twice, once per direction. Counting or drawing both would double
	 * every rail and any tiny sampling difference between the two curve parameterisations would look like
	 * two precision levels drawn on top of each other. Keep one representative per connection:
	 * the canonical direction when both are drawable, or the only non-NONE direction for one-way
	 * track.
	 */
	static boolean isReverseDuplicate(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end) {
		if (start.asLong() < end.asLong()) {
			return false;
		}
		final Map<BlockPos, Rail> reverseConnections = rails.get(end);
		if (reverseConnections == null) {
			return false;
		}
		final Rail reverse = reverseConnections.get(start);
		return reverse != null && reverse.railType != RailType.NONE;
	}

	private static Long platformIdAt(Map<Long, Long> platformByPos, BlockPos start, BlockPos end) {
		Long platformId = platformByPos.get(start.asLong());
		if (platformId == null) {
			platformId = platformByPos.get(end.asLong());
		}
		return platformId;
	}

	private static List<double[]> sampleRail(Rail rail, double length, double step, double coordinateScale,
			long deadline, BooleanSupplier cancelled) {
		double railStep = Math.max(step, length / (double) MAX_POINTS_PER_RAIL);
		if (!Double.isFinite(railStep) || railStep <= 0) {
			railStep = Math.max(length, 0.01);
		}

		final List<double[]> points = new ArrayList<>();
		int checked = 0;
		double t = 0;
		while (t < length) {
			if ((++checked & 0x1F) == 0 && expired(deadline, cancelled)) {
				return null;
			}
			addPoint(points, rail.getPosition(t), coordinateScale);
			t += railStep;
		}
		addPoint(points, rail.getPosition(length), coordinateScale);

		if (points.size() > 2 && isStraight(points)) {
			final List<double[]> reduced = new ArrayList<>(2);
			reduced.add(points.get(0));
			reduced.add(points.get(points.size() - 1));
			return reduced;
		}
		return points;
	}

	private static void addPoint(List<double[]> points, Vec3 position, double coordinateScale) {
		if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.z)) {
			return;
		}
		final double x = Math.round(position.x * coordinateScale) / coordinateScale;
		final double z = Math.round(position.z * coordinateScale) / coordinateScale;
		if (!points.isEmpty()) {
			final double[] last = points.get(points.size() - 1);
			if (Math.abs(last[0] - x) < 0.0001 && Math.abs(last[1] - z) < 0.0001) {
				return;
			}
		}
		points.add(new double[]{x, z});
	}

	private static boolean isStraight(List<double[]> points) {
		final double[] first = points.get(0);
		final double[] last = points.get(points.size() - 1);
		final double dx = last[0] - first[0];
		final double dz = last[1] - first[1];
		final double lengthSquared = dx * dx + dz * dz;
		if (lengthSquared < 1E-9) {
			return false;
		}
		final double length = Math.sqrt(lengthSquared);
		for (int i = 1; i < points.size() - 1; i++) {
			final double[] point = points.get(i);
			final double distance = Math.abs(dx * (point[1] - first[1]) - dz * (point[0] - first[0])) / length;
			if (distance > 0.05) {
				return false;
			}
		}
		return true;
	}

	private static boolean polylineIntersects(List<double[]> points, Query query) {
		double minX = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (final double[] point : points) {
			minX = Math.min(minX, point[0]);
			minZ = Math.min(minZ, point[1]);
			maxX = Math.max(maxX, point[0]);
			maxZ = Math.max(maxZ, point[1]);
		}
		return maxX >= query.minX() && minX <= query.maxX() && maxZ >= query.minZ() && minZ <= query.maxZ();
	}

	private static void appendHeader(StringBuilder builder, Query query, boolean tooDense, int totalRails,
			int renderedRails, int renderedPoints, int skipped, Map<RailType, Integer> railTypes) {
		builder.append("{\"world\":\"");
		appendEscaped(builder, query.dimension());
		builder.append("\",\"detail\":").append(query.detail());
		builder.append(",\"bounds\":{\"minX\":");
		appendCoordinate(builder, query.minX(), 1);
		builder.append(",\"minZ\":");
		appendCoordinate(builder, query.minZ(), 1);
		builder.append(",\"maxX\":");
		appendCoordinate(builder, query.maxX(), 1);
		builder.append(",\"maxZ\":");
		appendCoordinate(builder, query.maxZ(), 1);
		builder.append("},\"tooDense\":").append(tooDense);
		builder.append(",\"counts\":{\"totalRails\":").append(totalRails)
				.append(",\"rails\":").append(renderedRails)
				.append(",\"points\":").append(renderedPoints)
				.append(",\"skipped\":").append(skipped).append('}');
		builder.append(",\"railTypes\":{");
		boolean first = true;
		for (final Map.Entry<RailType, Integer> entry : railTypes.entrySet()) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			builder.append('"').append(entry.getKey().name()).append("\":").append(entry.getValue());
		}
		builder.append("},\"rails\":[");
	}

	private static void appendRail(StringBuilder builder, RenderedRail rendered, int decimals) {
		final Rail rail = rendered.rail();
		builder.append("[\"").append(rail.railType.name()).append("\",\"").append(rail.transportMode.name()).append("\",\"");
		appendEscaped(builder, rendered.platformId());
		builder.append("\",[");
		final List<double[]> points = rendered.points();
		for (int i = 0; i < points.size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			final double[] point = points.get(i);
			appendCoordinate(builder, point[0], decimals);
			builder.append(',');
			appendCoordinate(builder, point[1], decimals);
		}
		builder.append("]]");
	}

	private static void appendCoordinate(StringBuilder builder, double value, int decimals) {
		final long factor = decimals >= 2 ? 100L : 10L;
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
			if (factor == 100 && fraction < 10) {
				builder.append('0');
			}
			builder.append(fraction);
		}
	}

	private static void appendEscaped(StringBuilder builder, String value) {
		if (value == null) {
			return;
		}
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c == '"' || c == '\\') {
				builder.append('\\').append(c);
			} else if (c < 0x20) {
				builder.append(String.format("\\u%04x", (int) c));
			} else {
				builder.append(c);
			}
		}
	}

	private record Candidate(BlockPos start, BlockPos end, Rail rail, double length) {
	}

	private record RenderedRail(Rail rail, List<double[]> points, String platformId) {
	}
}
