package cn.terraleveler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class IslandFlattenAreaDetector {
    enum Cell { LAND, WATER, UNLOADED }
    enum Status { READY, SEED_WATER, UNLOADED, OPEN_BOUNDARY, TOO_LARGE, EMPTY_AFTER_INSET }

    record Settings(int maximumRadius, int maximumColumns, int shoreInset,
                    boolean fillInteriorWater, boolean cutNarrowConnections, int maximumNeckWidth) {
        Settings {
            maximumRadius = Math.max(8, maximumRadius);
            maximumColumns = Math.max(64, maximumColumns);
            shoreInset = Math.max(0, shoreInset);
            maximumNeckWidth = Math.max(1, maximumNeckWidth);
        }
    }

    /**
     * {@code gaps} lists every unloaded, unsurveyed column the scan ran into (capped at
     * {@link #MAXIMUM_REPORTED_GAPS}); {@code problemPos} stays the first one found.
     */
    record Result(Status status, Set<BlockPos> columns, BlockPos problemPos,
                  int connectedLandColumns, int shoreExcludedColumns,
                  int includedInteriorWaterColumns, int narrowConnectionsCut,
                  String detail, String diagnostics, List<BlockPos> gaps) {
        Result {
            columns = Set.copyOf(columns);
            diagnostics = diagnostics == null ? "" : diagnostics;
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        Result(Status status, Set<BlockPos> columns, BlockPos problemPos,
               int connectedLandColumns, int shoreExcludedColumns,
               int includedInteriorWaterColumns, int narrowConnectionsCut,
               String detail, String diagnostics) {
            this(status, columns, problemPos, connectedLandColumns, shoreExcludedColumns,
                    includedInteriorWaterColumns, narrowConnectionsCut, detail, diagnostics, List.of());
        }

        boolean ready() {
            return status == Status.READY && !columns.isEmpty();
        }
    }

    @FunctionalInterface
    interface ColumnReader {
        Cell read(int x, int z);
        default String describe(int x, int z) { return ""; }
        /** Compact surface description used only for failure path diagnostics. */
        default PathSample pathSample(int x, int z) {
            return new PathSample(read(x, z).name(), Integer.MIN_VALUE, Integer.MIN_VALUE);
        }
    }

    /** surface is the run key, e.g. "stone/SURFACE_LAND"; Y values may be Integer.MIN_VALUE. */
    record PathSample(String surface, int surfaceY, int subsurfaceWaterY) { }

    static final int PATH_WIDTH_PROBE = 32;
    static final int PATH_MAX_RUNS = 48;
    static final int PATH_NARROWEST = 5;
    static final int MAXIMUM_REPORTED_GAPS = 20000;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private IslandFlattenAreaDetector() { }

    static Result detect(int seedX, int seedZ, Settings settings, ColumnReader source) {
        Job job = new Job(seedX, seedZ, settings, source);
        while (!job.step(Long.MAX_VALUE)) { }
        return job.result();
    }

    /**
     * Resumable detection (0.8.73): {@link #step} works until a deadline and keeps its
     * place, so a large island is identified over several client ticks instead of
     * freezing the game for seconds. Running it to the end gives exactly the result of
     * the former one-shot scan, except that unloaded columns no longer stop it: they are
     * collected as gaps and reported together (0.8.69 testing showed one gap per press).
     */
    static final class Job {
        private enum Phase { SEED, BFS, PERIMETER, FLOOD, INTERIOR, DONE }

        private final int seedX;
        private final int seedZ;
        private final Settings settings;
        private final ColumnReader reader;
        private Phase phase = Phase.SEED;
        private Result result;

        private final Set<Long> visited = new HashSet<>();
        private final Set<Long> connected = new LinkedHashSet<>();
        private final Map<Long, Long> parent = new HashMap<>();
        private final ArrayDeque<Long> queue = new ArrayDeque<>();
        private int narrowCuts;
        private long expanding;
        private int directionIndex = DIRECTIONS.length;

        private final LinkedHashSet<Long> gaps = new LinkedHashSet<>();
        private int gapCount;
        private BlockPos firstGap;
        private String firstGapDetail;
        private String firstGapDiagnostics = "";

        private Bounds bounds;
        private List<Long> perimeter;
        private int perimeterIndex;
        private final Set<Long> externalWater = new HashSet<>();
        private final ArrayDeque<Long> floodQueue = new ArrayDeque<>();
        private Set<Long> included;
        private int interiorWater;
        /** Water inside the bounds whose body has already been judged (pond or foreign). */
        private final Set<Long> interiorJudged = new HashSet<>();
        private int foreignWater;
        private int interiorX;
        private int interiorZ;

        Job(int seedX, int seedZ, Settings settings, ColumnReader source) {
            this.seedX = seedX;
            this.seedZ = seedZ;
            this.settings = settings;
            // Narrow-neck probes, the external-water flood and the interior pass revisit
            // the same columns many times; each read scans a whole column of blocks.
            this.reader = memoized(source);
        }

        boolean done() { return phase == Phase.DONE; }

        Result result() { return result; }

        /** Columns examined so far, for progress display. */
        int examinedColumns() { return visited.size() + externalWater.size(); }

        int connectedColumns() { return connected.size(); }

        /** Enclosed water left out because land outside the island surrounds it. */
        int foreignWaterColumns() { return foreignWater; }

        /** Runs until finished (true) or {@code deadlineNanos} passes (false). */
        boolean step(long deadlineNanos) {
            int work = 0;
            while (phase != Phase.DONE) {
                if ((++work & 31) == 0 && System.nanoTime() > deadlineNanos) return false;
                switch (phase) {
                    case SEED -> stepSeed();
                    case BFS -> stepBfs();
                    case PERIMETER -> stepPerimeter();
                    case FLOOD -> stepFlood();
                    case INTERIOR -> stepInterior();
                    default -> { }
                }
            }
            return true;
        }

        private void finish(Result value) {
            result = value;
            phase = Phase.DONE;
        }

        private void stepSeed() {
            Cell seed = reader.read(seedX, seedZ);
            if (seed == Cell.UNLOADED) {
                finish(failure(Status.UNLOADED, seedX, seedZ, "玩家所在列尚未加载"));
                return;
            }
            if (seed != Cell.LAND) {
                finish(failure(Status.SEED_WATER, seedX, seedZ, "请站在要铺平的岛屿陆地上"));
                return;
            }
            long seedKey = key(seedX, seedZ);
            visited.add(seedKey);
            connected.add(seedKey);
            queue.add(seedKey);
            phase = Phase.BFS;
        }

        private void recordGap(int x, int z, long from, String detail, boolean landPath) {
            if (firstGap == null) {
                firstGap = new BlockPos(x, 0, z);
                firstGapDetail = detail;
                if (landPath) {
                    firstGapDiagnostics = pathDiagnostics(seedX, seedZ, from, x, z, parent, connected, visited.size(), reader);
                }
            }
            gapCount++;
            if (gaps.size() < MAXIMUM_REPORTED_GAPS) gaps.add(key(x, z));
        }

        private Result gapFailure() {
            List<BlockPos> columns = new ArrayList<>(gaps.size());
            for (long gap : gaps) columns.add(new BlockPos(x(gap), 0, z(gap)));
            return new Result(Status.UNLOADED, Set.of(), firstGap, connected.size(), 0, 0, narrowCuts,
                    firstGapDetail, firstGapDiagnostics, columns);
        }

        /** One BFS expansion: the next direction of the node being expanded. */
        private void stepBfs() {
            if (directionIndex >= DIRECTIONS.length) {
                if (queue.isEmpty()) {
                    if (firstGap != null) {
                        finish(gapFailure());
                        return;
                    }
                    beginExternalWater();
                    return;
                }
                expanding = queue.removeFirst();
                directionIndex = 0;
            }
            int[] direction = DIRECTIONS[directionIndex++];
            int x = x(expanding);
            int z = z(expanding);
            int nextX = x + direction[0];
            int nextZ = z + direction[1];
            if (Math.max(Math.abs(nextX - seedX), Math.abs(nextZ - seedZ)) > settings.maximumRadius()) {
                Cell outside = reader.read(nextX, nextZ);
                if (outside == Cell.UNLOADED) {
                    if (visited.add(key(nextX, nextZ))) {
                        recordGap(nextX, nextZ, expanding, "最大识别半径边缘存在未加载区块，无法确认水域边界", true);
                    }
                    return;
                }
                if (outside == Cell.LAND) {
                    finish(failure(Status.OPEN_BOUNDARY, nextX, nextZ,
                            "陆地连通区域到达最大识别半径，无法确认水域边界"
                                    + "；seed=" + seedX + "," + seedZ
                                    + " currentLand=" + x + "," + z
                                    + " outsideLand=" + nextX + "," + nextZ
                                    + " direction=" + direction[0] + "," + direction[1]
                                    + " radius=" + settings.maximumRadius()
                                    + " connected=" + connected.size()
                                    + " currentData=" + reader.describe(x, z)
                                    + " outsideData=" + reader.describe(nextX, nextZ),
                            pathDiagnostics(seedX, seedZ, expanding, nextX, nextZ, parent, connected, visited.size(), reader)));
                }
                return;
            }
            long nextKey = key(nextX, nextZ);
            if (!visited.add(nextKey)) return;
            Cell next = reader.read(nextX, nextZ);
            if (next == Cell.UNLOADED) {
                recordGap(nextX, nextZ, expanding, "岛屿边界经过未加载区块", true);
                return;
            }
            if (next != Cell.LAND) return;
            if (settings.cutNarrowConnections()
                    && narrowAcross(nextX, nextZ, direction[0], direction[1], settings.maximumNeckWidth(), reader)) {
                narrowCuts++;
                return;
            }
            connected.add(nextKey);
            parent.put(nextKey, expanding);
            if (connected.size() > settings.maximumColumns()) {
                finish(failure(Status.TOO_LARGE, nextX, nextZ, "连通陆地面积超过岛屿识别上限",
                        pathDiagnostics(seedX, seedZ, nextKey, nextX, nextZ, parent, connected, visited.size(), reader)));
                return;
            }
            queue.addLast(nextKey);
        }

        private void beginExternalWater() {
            bounds = bounds(connected).expand(1);
            perimeter = new ArrayList<>();
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                perimeter.add(key(x, bounds.minZ()));
                perimeter.add(key(x, bounds.maxZ()));
            }
            for (int z = bounds.minZ() + 1; z < bounds.maxZ(); z++) {
                perimeter.add(key(bounds.minX(), z));
                perimeter.add(key(bounds.maxX(), z));
            }
            perimeterIndex = 0;
            phase = Phase.PERIMETER;
        }

        private void stepPerimeter() {
            if (perimeterIndex >= perimeter.size()) {
                phase = Phase.FLOOD;
                return;
            }
            long position = perimeter.get(perimeterIndex++);
            int x = x(position);
            int z = z(position);
            if (connected.contains(position) || externalWater.contains(position)) return;
            Cell cell = reader.read(x, z);
            if (cell == Cell.UNLOADED) {
                recordGap(x, z, 0L, "识别外部水域时遇到未加载区块", false);
            } else if (cell == Cell.WATER) {
                externalWater.add(position);
                floodQueue.addLast(position);
            }
        }

        private void stepFlood() {
            if (floodQueue.isEmpty()) {
                if (firstGap != null) {
                    finish(gapFailure());
                    return;
                }
                included = erodeAgainstExternalWater(connected, externalWater, settings.shoreInset());
                interiorZ = bounds.minZ() + 1;
                interiorX = bounds.minX() + 1;
                phase = settings.fillInteriorWater() ? Phase.INTERIOR : Phase.DONE;
                if (phase == Phase.DONE) finishReady();
                return;
            }
            long current = floodQueue.removeFirst();
            int x = x(current);
            int z = z(current);
            for (int[] direction : DIRECTIONS) {
                int nextX = x + direction[0];
                int nextZ = z + direction[1];
                if (!bounds.contains(nextX, nextZ)) continue;
                long next = key(nextX, nextZ);
                if (connected.contains(next) || externalWater.contains(next) || gaps.contains(next)) continue;
                Cell cell = reader.read(nextX, nextZ);
                if (cell == Cell.UNLOADED) {
                    recordGap(nextX, nextZ, 0L, "识别外部水域时遇到未加载区块", false);
                } else if (cell == Cell.WATER) {
                    externalWater.add(next);
                    floodQueue.addLast(next);
                }
            }
        }

        private void stepInterior() {
            if (interiorZ > bounds.maxZ() - 1) {
                if (firstGap != null) {
                    finish(gapFailure());
                    return;
                }
                finishReady();
                return;
            }
            int x = interiorX;
            int z = interiorZ;
            if (++interiorX > bounds.maxX() - 1) {
                interiorX = bounds.minX() + 1;
                interiorZ++;
            }
            long candidate = key(x, z);
            if (connected.contains(candidate) || externalWater.contains(candidate)
                    || interiorJudged.contains(candidate)) return;
            Cell cell = reader.read(x, z);
            if (cell == Cell.UNLOADED) {
                recordGap(x, z, 0L, "识别内部水塘时遇到未加载区块", false);
            } else if (cell == Cell.WATER) {
                judgeEnclosedWater(candidate);
            }
        }

        /**
         * A water body is an island pond only when island land surrounds it on every side.
         * Before 0.8.97 any water the outer flood did not reach counted: two village farm
         * channels (-3653/-3647, -10211..-10205) across the boundary river lay inside the
         * island's bounding box, enclosed by the village's own land, and were "filled" from
         * the island side, where no land route led to them.
         */
        private void judgeEnclosedWater(long start) {
            List<Long> body = new ArrayList<>();
            ArrayDeque<Long> pending = new ArrayDeque<>();
            interiorJudged.add(start);
            pending.add(start);
            boolean enclosed = true;
            while (!pending.isEmpty()) {
                long current = pending.removeFirst();
                body.add(current);
                for (int[] direction : DIRECTIONS) {
                    int nextX = x(current) + direction[0];
                    int nextZ = z(current) + direction[1];
                    long next = key(nextX, nextZ);
                    if (connected.contains(next) || interiorJudged.contains(next)) continue;
                    if (externalWater.contains(next) || !bounds.contains(nextX, nextZ)) {
                        enclosed = false;
                        continue;
                    }
                    if (reader.read(nextX, nextZ) == Cell.WATER) {
                        interiorJudged.add(next);
                        pending.addLast(next);
                    } else {
                        // Land outside the island; an unloaded column is reported by the scan itself.
                        enclosed = false;
                    }
                }
            }
            if (enclosed) {
                included.addAll(body);
                interiorWater += body.size();
            } else {
                foreignWater += body.size();
            }
        }

        private void finishReady() {
            int shoreExcluded = connected.size() - (included.size() - interiorWater);
            if (included.isEmpty()) {
                finish(failure(Status.EMPTY_AFTER_INSET, seedX, seedZ, "岸线内缩后没有剩余施工区域"));
                return;
            }
            if (included.size() > settings.maximumColumns()) {
                finish(failure(Status.TOO_LARGE, seedX, seedZ, "加入内部水塘后施工面积超过岛屿识别上限"));
                return;
            }
            Set<BlockPos> columns = new LinkedHashSet<>();
            for (long value : included) columns.add(new BlockPos(x(value), 0, z(value)));
            finish(new Result(Status.READY, columns, null, connected.size(), shoreExcluded,
                    interiorWater, narrowCuts, "岛屿范围已识别", ""));
        }
    }

    /** A group of neighbouring unsurveyed chunks, described for the player. */
    record GapCluster(int chunks, int columns, int centerX, int centerZ, int nearestX, int nearestZ,
                      int distance, String direction) { }

    /**
     * Groups gap columns into 8-connected chunk clusters around the seed, nearest first.
     * The centre is the mean of the cluster's chunk centres; {@code nearest} is the gap
     * column closest to the seed, where walking towards first helps most.
     */
    static List<GapCluster> gapClusters(Collection<BlockPos> gaps, int seedX, int seedZ) {
        Map<Long, List<BlockPos>> byChunk = new HashMap<>();
        for (BlockPos gap : gaps) {
            byChunk.computeIfAbsent(key(gap.getX() >> 4, gap.getZ() >> 4), ignored -> new ArrayList<>()).add(gap);
        }
        Set<Long> remaining = new HashSet<>(byChunk.keySet());
        List<GapCluster> clusters = new ArrayList<>();
        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<Long> pending = new ArrayDeque<>();
            pending.add(start);
            long sumX = 0;
            long sumZ = 0;
            int chunks = 0;
            int columns = 0;
            BlockPos nearest = null;
            long nearestDistance = Long.MAX_VALUE;
            while (!pending.isEmpty()) {
                long chunk = pending.removeFirst();
                chunks++;
                sumX += (x(chunk) << 4) + 8;
                sumZ += (z(chunk) << 4) + 8;
                for (BlockPos column : byChunk.get(chunk)) {
                    columns++;
                    long dx = column.getX() - seedX;
                    long dz = column.getZ() - seedZ;
                    long distance = dx * dx + dz * dz;
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = column;
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long neighbour = key(x(chunk) + dx, z(chunk) + dz);
                        if (remaining.remove(neighbour)) pending.addLast(neighbour);
                    }
                }
            }
            int centerX = (int) Math.round((double) sumX / chunks);
            int centerZ = (int) Math.round((double) sumZ / chunks);
            clusters.add(new GapCluster(chunks, columns, centerX, centerZ, nearest.getX(), nearest.getZ(),
                    (int) Math.round(Math.sqrt(nearestDistance)), compassDirection(centerX - seedX, centerZ - seedZ)));
        }
        clusters.sort(Comparator.comparingInt(GapCluster::distance).thenComparingInt(GapCluster::centerX)
                .thenComparingInt(GapCluster::centerZ));
        return clusters;
    }

    /** Eight-way compass direction in Minecraft coordinates (north = -Z, east = +X). */
    static String compassDirection(int dx, int dz) {
        if (dx == 0 && dz == 0) return "附近";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360.0;
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    /** Caches {@link ColumnReader#read} for one detection; diagnostics still read live. */
    static ColumnReader memoized(ColumnReader source) {
        Map<Long, Cell> cells = new HashMap<>();
        return new ColumnReader() {
            @Override public Cell read(int x, int z) {
                return cells.computeIfAbsent(key(x, z), ignored -> source.read(x, z));
            }

            @Override public String describe(int x, int z) {
                return source.describe(x, z);
            }

            @Override public PathSample pathSample(int x, int z) {
                return source.pathSample(x, z);
            }
        };
    }

    private static boolean narrowAcross(int x, int z, int moveX, int moveZ,
                                        int maximumWidth, ColumnReader reader) {
        int perpendicularX = -moveZ;
        int perpendicularZ = moveX;
        int width = 1;
        boolean firstWater = false;
        boolean secondWater = false;
        for (int sign : new int[]{-1, 1}) {
            for (int offset = 1; offset <= maximumWidth; offset++) {
                Cell cell = reader.read(x + perpendicularX * offset * sign,
                        z + perpendicularZ * offset * sign);
                if (cell == Cell.LAND) {
                    width++;
                    if (width > maximumWidth) return false;
                    continue;
                }
                if (cell == Cell.WATER) {
                    if (sign < 0) firstWater = true;
                    else secondWater = true;
                }
                break;
            }
        }
        return firstWater && secondWater && width <= maximumWidth;
    }

    private static Set<Long> erodeAgainstExternalWater(Set<Long> land, Set<Long> externalWater, int inset) {
        Set<Long> result = new LinkedHashSet<>(land);
        Set<Long> frontier = new HashSet<>(externalWater);
        for (int pass = 0; pass < inset; pass++) {
            Set<Long> removed = new HashSet<>();
            for (long position : result) {
                int x = x(position);
                int z = z(position);
                for (int[] direction : DIRECTIONS) {
                    if (frontier.contains(key(x + direction[0], z + direction[1]))) {
                        removed.add(position);
                        break;
                    }
                }
            }
            if (removed.isEmpty()) break;
            result.removeAll(removed);
            frontier = removed;
        }
        return result;
    }

    private static Result failure(Status status, int x, int z, String detail) {
        return failure(status, x, z, detail, "");
    }

    private static Result failure(Status status, int x, int z, String detail, String diagnostics) {
        return new Result(status, Set.of(), new BlockPos(x, 0, z), 0, 0, 0, 0, detail, diagnostics);
    }

    /** BFS land path from the seed (which has no parent) to {@code last}. */
    static List<Long> landPath(long last, Map<Long, Long> parent) {
        List<Long> path = new ArrayList<>();
        for (Long cursor = last; cursor != null; cursor = parent.get(cursor)) path.add(cursor);
        Collections.reverse(path);
        return path;
    }

    record Crossing(int x, int z, int width, Cell firstEnd, Cell secondEnd) { }

    /** Land width across the path at each step, narrowest first; a null end means the probe cap. */
    static List<Crossing> crossings(List<Long> path, ColumnReader reader) {
        List<Crossing> result = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            int x = x(path.get(i));
            int z = z(path.get(i));
            int perpendicularX = -(z - z(path.get(i - 1)));
            int perpendicularZ = x - x(path.get(i - 1));
            int width = 1;
            Cell[] ends = new Cell[2];
            for (int side = 0; side < 2; side++) {
                int sign = side == 0 ? -1 : 1;
                for (int offset = 1; offset <= PATH_WIDTH_PROBE; offset++) {
                    Cell cell = reader.read(x + perpendicularX * offset * sign, z + perpendicularZ * offset * sign);
                    if (cell != Cell.LAND) {
                        ends[side] = cell;
                        break;
                    }
                    width++;
                }
            }
            result.add(new Crossing(x, z, width, ends[0], ends[1]));
        }
        result.sort(Comparator.comparingInt(Crossing::width));
        return result;
    }

    private static String pathDiagnostics(int seedX, int seedZ, long last, int problemX, int problemZ,
                                          Map<Long, Long> parent, Set<Long> connected, int visited,
                                          ColumnReader reader) {
        List<Long> path = landPath(last, parent);
        Bounds reached = bounds(connected);
        StringBuilder out = new StringBuilder();
        out.append("seed=").append(seedX).append(',').append(seedZ)
                .append(" problem=").append(problemX).append(',').append(problemZ)
                .append(" offset=").append(problemX - seedX).append(',').append(problemZ - seedZ)
                .append(" pathLength=").append(path.size())
                .append(" manhattan=").append(Math.abs(problemX - seedX) + Math.abs(problemZ - seedZ))
                .append(" connected=").append(connected.size())
                .append(" visited=").append(visited)
                .append(" reachedBounds=x").append(reached.minX()).append("..").append(reached.maxX())
                .append(",z").append(reached.minZ()).append("..").append(reached.maxZ());

        // Consecutive path columns with the same surface block and column kind.
        out.append(" runs=");
        String runKey = null;
        int startX = 0, startZ = 0, endX = 0, endZ = 0, count = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, waterTop = Integer.MIN_VALUE;
        int runs = 0;
        for (int i = 0; i <= path.size(); i++) {
            PathSample sample = null;
            int px = 0, pz = 0;
            if (i < path.size()) {
                px = x(path.get(i));
                pz = z(path.get(i));
                sample = reader.pathSample(px, pz);
                if (sample.surface().equals(runKey)) {
                    endX = px;
                    endZ = pz;
                    count++;
                    if (sample.surfaceY() != Integer.MIN_VALUE) {
                        minY = Math.min(minY, sample.surfaceY());
                        maxY = Math.max(maxY, sample.surfaceY());
                    }
                    waterTop = Math.max(waterTop, sample.subsurfaceWaterY());
                    continue;
                }
            }
            if (runKey != null) {
                if (runs < PATH_MAX_RUNS) {
                    out.append('[').append(startX).append(',').append(startZ)
                            .append("->").append(endX).append(',').append(endZ)
                            .append(" n=").append(count).append(' ').append(runKey);
                    if (maxY != Integer.MIN_VALUE) out.append(" y=").append(minY).append("..").append(maxY);
                    if (waterTop != Integer.MIN_VALUE) out.append(" waterTop=").append(waterTop);
                    out.append(']');
                }
                runs++;
            }
            if (sample == null) break;
            runKey = sample.surface();
            startX = endX = px;
            startZ = endZ = pz;
            count = 1;
            minY = sample.surfaceY() == Integer.MIN_VALUE ? Integer.MAX_VALUE : sample.surfaceY();
            maxY = sample.surfaceY();
            waterTop = sample.subsurfaceWaterY();
        }
        if (runs > PATH_MAX_RUNS) out.append("[+").append(runs - PATH_MAX_RUNS).append(" runs omitted]");
        out.append(" runCount=").append(runs);

        List<Crossing> crossings = crossings(path, reader);
        out.append(" narrowest=");
        for (int i = 0; i < Math.min(PATH_NARROWEST, crossings.size()); i++) {
            Crossing crossing = crossings.get(i);
            out.append('[').append(crossing.x()).append(',').append(crossing.z())
                    .append(" width=").append(crossing.width())
                    .append(" ends=").append(crossing.firstEnd() == null ? "CAP" : crossing.firstEnd())
                    .append('/').append(crossing.secondEnd() == null ? "CAP" : crossing.secondEnd())
                    .append(' ').append(reader.pathSample(crossing.x(), crossing.z()).surface())
                    .append(']');
        }
        return out.toString();
    }

    private static Bounds bounds(Set<Long> positions) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long position : positions) {
            minX = Math.min(minX, x(position));
            maxX = Math.max(maxX, x(position));
            minZ = Math.min(minZ, z(position));
            maxZ = Math.max(maxZ, z(position));
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int x(long key) { return (int) (key >> 32); }
    private static int z(long key) { return (int) key; }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
        Bounds expand(int amount) { return new Bounds(minX - amount, maxX + amount, minZ - amount, maxZ + amount); }
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }

}
