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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Builds the live train snapshot for the web dashboard.
 *
 * <p>Runs on the game thread under the same cooperative deadline as the track overlay. Only one
 * snapshot is built at a time, and the payload is capped so polling cannot turn into a tick cost.
 */
public final class WebDashboardTrainService {

    public static final int DEFAULT_LIMIT = 16;
    public static final int MAX_LIMIT = 256;
    public static final long BUILD_BUDGET_MILLIS = 50L;

    private static final int MAX_CARS = 64;
    private static final Map<List<PathData>, double[]> DISTANCE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final long NANOS_PER_MILLI = 1000000L;
    private static final long BUDGET_NANOS = BUILD_BUDGET_MILLIS * NANOS_PER_MILLI;

    private WebDashboardTrainService() {
    }

    public record Query(String dimension, int limit, long routeId, long depotId) {
    }

    public record Result(int status, String body) {
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
        final List<Candidate> matches = new ArrayList<>();
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
                if (seenTrains.add(train.id)) {
                    matches.add(new Candidate(siding, train, depotId));
                }
            }
        }

        if (expired(deadline, cancelled)) {
            return timeout();
        }

        matches.sort(Comparator.comparingLong(candidate -> candidate.train().id));
        final boolean truncated = matches.size() > query.limit();
        final int rendered = Math.min(matches.size(), query.limit());

        final StringBuilder builder = new StringBuilder(256 + rendered * 512);
        builder.append("{\"time\":").append(System.currentTimeMillis());
        builder.append(",\"world\":\"");
        appendEscaped(builder, query.dimension());
        builder.append("\",\"total\":").append(matches.size());
        builder.append(",\"truncated\":").append(truncated);
        builder.append(",\"trains\":[");

        boolean firstTrain = true;
        for (int i = 0; i < rendered; i++) {
            final Candidate candidate = matches.get(i);
            final TrainServer train = candidate.train();
            final List<PathData> path = train.path;
            if (path == null || path.isEmpty() || train.spacing <= 0) {
                continue;
            }
            final double[] distances = DISTANCE_CACHE.computeIfAbsent(path, WebDashboardTrainService::cumulativeDistances);
            if (distances == null) {
                continue;
            }
            if (expired(deadline, cancelled)) {
                return timeout();
            }

            if (!firstTrain) {
                builder.append(',');
            }
            firstTrain = false;
            final Route route = dataCache == null ? null : dataCache.routeIdMap.get(train.getRouteId());
            appendTrain(builder, candidate, route, distances);
        }

        builder.append("]}");
        return new Result(200, builder.toString());
    }

    private static void appendTrain(StringBuilder builder, Candidate candidate, Route route, double[] distances) {
        final TrainServer train = candidate.train();
        final List<PathData> path = train.path;
        final int cars = Math.min(Math.max(train.trainCars, 1), MAX_CARS);

        builder.append("{\"id\":\"").append(train.id).append("\",\"sidingId\":\"").append(train.sidingId).append('"');
        builder.append(",\"model\":\"");
        appendEscaped(builder, train.baseTrainType);
        builder.append("\",\"cars\":").append(cars);
        builder.append(",\"spacing\":");
        appendCoordinate(builder, train.spacing, 2);
        builder.append(",\"width\":");
        appendCoordinate(builder, train.width, 2);
        builder.append(",\"color\":").append(route == null ? candidate.siding().color : route.color);
        builder.append(",\"routeId\":");
        appendId(builder, train.getRouteId());
        builder.append(",\"depotId\":");
        appendId(builder, candidate.depotId());
        builder.append(",\"nextPlatformId\":");
        // A train parked in a depot still has a path; its next stop only means something while on a route.
        appendId(builder, train.getIsOnRoute() ? train.getNextStopPlatformId() : 0);
        builder.append(",\"speed\":");
        appendCoordinate(builder, train.getSpeed(), 3);
        builder.append(",\"doors\":");
        appendCoordinate(builder, train.getDoorValue(), 3);
        builder.append(",\"onRoute\":").append(train.getIsOnRoute());
        builder.append(",\"manual\":").append(train.isCurrentlyManual());
        builder.append(",\"reversed\":").append(train.isReversed());
        builder.append(",\"points\":[");

        for (int i = 0; i <= cars; i++) {
            if (i > 0) {
                builder.append(',');
            }
            final int car = train.isReversed() ? cars - i : i;
            final double progress = Math.max(train.getRailProgress() - car * (double) train.spacing, 0);
            final int index = indexFor(progress, distances);
            final double local = progress - (index == 0 ? 0 : distances[index - 1]);
            final Vec3 position = path.get(index).rail.getPosition(local);
            appendCoordinate(builder, position.x, 3);
            builder.append(',');
            appendCoordinate(builder, position.z, 3);
        }

        // A car's block belongs at the rail midpoint of that car, not at the chord midpoint of its two
        // boundary points; the latter visibly slides inside curves.
        builder.append("],\"centers\":[");
        for (int i = 0; i < cars; i++) {
            if (i > 0) {
                builder.append(',');
            }
            final int car = train.isReversed() ? cars - 1 - i : i;
            final double progress = Math.max(train.getRailProgress() - (car + 0.5) * (double) train.spacing, 0);
            final int index = indexFor(progress, distances);
            final double local = progress - (index == 0 ? 0 : distances[index - 1]);
            final Vec3 position = path.get(index).rail.getPosition(local);
            appendCoordinate(builder, position.x, 3);
            builder.append(',');
            appendCoordinate(builder, position.z, 3);
        }

        builder.append("]}");
    }

    private static int indexFor(double progress, double[] distances) {
        for (int i = 0; i < distances.length; i++) {
            if (progress < distances[i]) {
                return i;
            }
        }
        return distances.length - 1;
    }

    private static double[] cumulativeDistances(List<PathData> path) {
        final double[] distances = new double[path.size()];
        double sum = 0;
        for (int i = 0; i < path.size(); i++) {
            final PathData pathData = path.get(i);
            if (pathData == null || pathData.rail == null) {
                return null;
            }
            sum += pathData.rail.getLength();
            distances[i] = sum;
        }
        return distances;
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

    private static void appendEscaped(StringBuilder builder, String value) {
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

    private static boolean expired(long deadline, BooleanSupplier cancelled) {
        return System.nanoTime() > deadline || cancelled.getAsBoolean();
    }

    private static Result timeout() {
        return new Result(504, "{\"error\":\"trains_timeout\"}");
    }

    private record Candidate(Siding siding, TrainServer train, long depotId) {
    }
}
