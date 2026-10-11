package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable cut-and-fill plan for one rectangular or masked horizontal surface. */
public final class FlattenPlan implements WorkArea {
    private final BlockPos first;
    private final BlockPos second;
    private final int targetGroundY;
    private final int scannedTopY;
    private final List<BlockPos> excavationTargets;
    private final List<BlockPos> treeTargets;
    private final Map<BlockPos, Integer> treeComponentIds;
    private final List<BlockPos> deferredFoliageTargets;
    private final Set<BlockPos> interiorFoliageTargets;
    private final List<BlockPos> fillTargets;
    private final long estimatedExcavationBlocks;
    private final Set<BlockPos> includedColumns;
    private final int detectedLandColumns;
    private final int shoreExcludedColumns;
    private final int includedInteriorWaterColumns;
    private final int narrowConnectionsCut;

    public FlattenPlan(BlockPos first, BlockPos second, int targetGroundY, int scannedTopY,
                       List<BlockPos> excavationTargets, List<BlockPos> fillTargets,
                       long estimatedExcavationBlocks) {
        this(first, second, targetGroundY, scannedTopY, excavationTargets, List.of(), List.of(), fillTargets,
                estimatedExcavationBlocks);
    }

    public FlattenPlan(BlockPos first, BlockPos second, int targetGroundY, int scannedTopY,
                       List<BlockPos> excavationTargets, List<BlockPos> deferredFoliageTargets,
                       List<BlockPos> fillTargets, long estimatedExcavationBlocks) {
        this(first, second, targetGroundY, scannedTopY, excavationTargets, List.of(),
                deferredFoliageTargets, Set.copyOf(deferredFoliageTargets), fillTargets, estimatedExcavationBlocks);
    }

    public FlattenPlan(BlockPos first, BlockPos second, int targetGroundY, int scannedTopY,
                       List<BlockPos> excavationTargets, List<BlockPos> treeTargets,
                       List<BlockPos> deferredFoliageTargets, List<BlockPos> fillTargets,
                       long estimatedExcavationBlocks) {
        this(first, second, targetGroundY, scannedTopY, excavationTargets, treeTargets,
                deferredFoliageTargets, Set.copyOf(deferredFoliageTargets), fillTargets, estimatedExcavationBlocks);
    }

    public FlattenPlan(BlockPos first, BlockPos second, int targetGroundY, int scannedTopY,
                       List<BlockPos> excavationTargets, List<BlockPos> treeTargets,
                       List<BlockPos> deferredFoliageTargets, Set<BlockPos> interiorFoliageTargets,
                       List<BlockPos> fillTargets, long estimatedExcavationBlocks) {
        this(first, second, targetGroundY, scannedTopY, excavationTargets, treeTargets,
                deferredFoliageTargets, interiorFoliageTargets, fillTargets, estimatedExcavationBlocks,
                null, 0, 0, 0, 0);
    }

    FlattenPlan(BlockPos first, BlockPos second, int targetGroundY, int scannedTopY,
                List<BlockPos> excavationTargets, List<BlockPos> treeTargets,
                List<BlockPos> deferredFoliageTargets, Set<BlockPos> interiorFoliageTargets,
                List<BlockPos> fillTargets, long estimatedExcavationBlocks,
                Set<BlockPos> includedColumns, int detectedLandColumns, int shoreExcludedColumns,
                int includedInteriorWaterColumns, int narrowConnectionsCut) {
        this.first = first.immutable();
        this.second = second.immutable();
        this.targetGroundY = targetGroundY;
        this.scannedTopY = scannedTopY;
        this.excavationTargets = List.copyOf(excavationTargets);
        this.treeTargets = List.copyOf(treeTargets);
        this.treeComponentIds = TreeExcavationScheduler.componentIds(this.treeTargets);
        this.deferredFoliageTargets = List.copyOf(deferredFoliageTargets);
        this.interiorFoliageTargets = Set.copyOf(interiorFoliageTargets);
        this.fillTargets = List.copyOf(fillTargets);
        this.estimatedExcavationBlocks = estimatedExcavationBlocks;
        this.includedColumns = includedColumns == null ? null : normalizeColumns(includedColumns);
        this.detectedLandColumns = detectedLandColumns;
        this.shoreExcludedColumns = shoreExcludedColumns;
        this.includedInteriorWaterColumns = includedInteriorWaterColumns;
        this.narrowConnectionsCut = narrowConnectionsCut;
    }

    @Override public int minX() { return Math.min(first.getX(), second.getX()); }
    @Override public int maxX() { return Math.max(first.getX(), second.getX()); }
    @Override public int minZ() { return Math.min(first.getZ(), second.getZ()); }
    @Override public int maxZ() { return Math.max(first.getZ(), second.getZ()); }
    public int targetGroundY() { return targetGroundY; }
    public int scannedTopY() { return scannedTopY; }
    public long columns() { return includedColumns == null
            ? (long) (maxX() - minX() + 1) * (maxZ() - minZ() + 1) : includedColumns.size(); }
    public long estimatedBlocks() { return estimatedExcavationBlocks; }
    public int excavationTargetCount() { return excavationTargets.size() + treeTargets.size() + deferredFoliageTargets.size(); }
    public int primaryExcavationTargetCount() { return excavationTargets.size() + treeTargets.size(); }
    public int treeTargetCount() { return treeTargets.size(); }
    public int treeCount() { return new java.util.HashSet<>(treeComponentIds.values()).size(); }
    public int deferredFoliageTargetCount() { return deferredFoliageTargets.size(); }
    public int interiorFoliageTargetCount() { return interiorFoliageTargets.size(); }
    public int boundaryFoliageTargetCount() { return deferredFoliageTargets.size() - interiorFoliageTargets.size(); }
    public long estimatedFillBlocks() { return fillTargets.size(); }
    public boolean islandArea() { return includedColumns != null; }
    public int detectedLandColumns() { return detectedLandColumns; }
    public int shoreExcludedColumns() { return shoreExcludedColumns; }
    public int includedInteriorWaterColumns() { return includedInteriorWaterColumns; }
    public int narrowConnectionsCut() { return narrowConnectionsCut; }

    @Override
    public boolean containsColumn(int x, int z) {
        return includedColumns == null
                ? x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ()
                : includedColumns.contains(new BlockPos(x, 0, z));
    }

    Set<BlockPos> includedColumns() {
        return includedColumns == null ? Set.of() : includedColumns;
    }

    long geometryFingerprint() {
        long hash = 1469598103934665603L;
        if (includedColumns == null) {
            hash = mix(hash, minX());
            hash = mix(hash, maxX());
            hash = mix(hash, minZ());
            return mix(hash, maxZ());
        }
        return columnsFingerprint(includedColumns);
    }

    /** Same geometry fingerprint as an island plan with exactly these columns. */
    static long columnsFingerprint(Set<BlockPos> columns) {
        long hash = 1469598103934665603L;
        List<BlockPos> ordered = new ArrayList<>(columns);
        ordered.sort(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                .thenComparingInt(pos -> pos.getZ()));
        for (BlockPos column : ordered) {
            hash = mix(hash, column.getX());
            hash = mix(hash, column.getZ());
        }
        return hash;
    }

    public List<BlockPos> excavationTargetsFrom(BlockPos origin) {
        return scheduledExcavationTargetsFrom(origin, Set.of()).targets();
    }

    FlattenExcavationScheduler.Result scheduledExcavationTargetsFrom(
            BlockPos origin, Set<BlockPos> excludedScaffolds) {
        // Each tree is cut when its own tile is reached (0.8.72), not all trees first.
        return FlattenExcavationScheduler.scheduleWithTrees(excavationTargets, treeTargets, origin, excludedScaffolds);
    }

    boolean isTreeTarget(BlockPos target) {
        return target != null && treeComponentIds.containsKey(target);
    }

    boolean isSameTree(BlockPos firstTarget, BlockPos secondTarget) {
        Integer firstComponent = treeComponentIds.get(firstTarget);
        return firstComponent != null && firstComponent.equals(treeComponentIds.get(secondTarget));
    }

    FlattenExcavationScheduler.Result scheduledDeferredFoliageTargetsFrom(
            BlockPos origin, Set<BlockPos> excludedScaffolds) {
        return FlattenExcavationScheduler.schedule(deferredFoliageTargets, origin, excludedScaffolds);
    }

    Set<BlockPos> interiorFoliageTargets() {
        return interiorFoliageTargets;
    }

    public List<BlockPos> fillTargetsFrom(BlockPos origin) {
        // Surface fill is a horizontal skin. Start near the real player and
        // sweep the shorter horizontal axis so long, narrow selections grow
        // as a compact front instead of one full-length strip at a time.
        boolean sweepAlongX = maxX() - minX() <= maxZ() - minZ();
        return continuousWithinLayers(fillTargets, origin, sweepAlongX);
    }

    @Override
    public boolean isStrictInteriorXZ(int x, int z) {
        // A flattening job must walk throughout the selected rectangle.
        return false;
    }

    @Override
    public boolean permitsScaffoldAt(int x, int z) {
        if (includedColumns != null) return containsColumn(x, z);
        return x >= minX() - 1 && x <= maxX() + 1
                && z >= minZ() - 1 && z <= maxZ() + 1;
    }

    private static Set<BlockPos> normalizeColumns(Set<BlockPos> source) {
        Set<BlockPos> normalized = new java.util.LinkedHashSet<>();
        for (BlockPos column : source) normalized.add(new BlockPos(column.getX(), 0, column.getZ()));
        return Set.copyOf(normalized);
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        return hash * 1099511628211L;
    }

    static List<BlockPos> localityWithinLayers(List<BlockPos> source, BlockPos origin) {
        if (origin == null || source.size() < 2) return source;
        List<BlockPos> result = new ArrayList<>(source.size());
        BlockPos previousFinish = origin;
        int start = 0;
        while (start < source.size()) {
            int y = source.get(start).getY();
            int end = start + 1;
            while (end < source.size() && source.get(end).getY() == y) end++;
            List<BlockPos> remaining = new ArrayList<>(source.subList(start, end));
            while (!remaining.isEmpty()) {
                int bestIndex = 0;
                long bestDistance = distanceSquared(remaining.getFirst(), previousFinish);
                for (int index = 1; index < remaining.size(); index++) {
                    long distance = distanceSquared(remaining.get(index), previousFinish);
                    if (distance < bestDistance) {
                        bestIndex = index;
                        bestDistance = distance;
                    }
                }
                BlockPos next = remaining.remove(bestIndex);
                result.add(next);
                previousFinish = next;
            }
            start = end;
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> continuousWithinLayers(List<BlockPos> source, BlockPos origin,
                                                          boolean sweepAlongX) {
        if (origin == null || source.size() < 2) return source;
        List<BlockPos> result = new ArrayList<>(source.size());
        BlockPos previousFinish = null;
        int start = 0;
        while (start < source.size()) {
            int y = source.get(start).getY();
            int end = start + 1;
            while (end < source.size() && source.get(end).getY() == y) end++;
            List<BlockPos> layer = new ArrayList<>(source.subList(start, end));
            List<BlockPos> best = null;
            long bestConnector = Long.MAX_VALUE;
            long bestTravel = Long.MAX_VALUE;
            for (boolean reverseRows : new boolean[]{false, true}) {
                for (boolean reverseFirstRow : new boolean[]{false, true}) {
                    List<BlockPos> candidate = serpentineLayer(
                            layer, sweepAlongX, reverseRows, reverseFirstRow);
                    long connector = distanceSquared(candidate.getFirst(),
                            previousFinish == null ? origin : previousFinish);
                    long travel = horizontalTravel(candidate);
                    boolean betterConnector = connector < bestConnector;
                    if (best == null || betterConnector
                            || connector == bestConnector && travel < bestTravel) {
                        best = candidate;
                        bestConnector = connector;
                        bestTravel = travel;
                    }
                }
            }
            result.addAll(best);
            previousFinish = best.getLast();
            start = end;
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> serpentineLayer(List<BlockPos> layer,
                                                   boolean sweepAlongX,
                                                   boolean reverseRows,
                                                   boolean reverseFirstRow) {
        Map<Integer, List<BlockPos>> rows = new TreeMap<>();
        for (BlockPos position : layer) {
            int row = sweepAlongX ? position.getZ() : position.getX();
            rows.computeIfAbsent(row, ignored -> new ArrayList<>()).add(position);
        }
        List<List<BlockPos>> orderedRows = new ArrayList<>(rows.values());
        if (reverseRows) Collections.reverse(orderedRows);

        List<BlockPos> result = new ArrayList<>(layer.size());
        for (int rowIndex = 0; rowIndex < orderedRows.size(); rowIndex++) {
            List<BlockPos> row = new ArrayList<>(orderedRows.get(rowIndex));
            if (sweepAlongX) {
                row.sort(Comparator.comparingInt(BlockPos::getX));
            } else {
                row.sort(Comparator.comparingInt(BlockPos::getZ));
            }
            boolean reverse = reverseFirstRow ^ (rowIndex % 2 == 1);
            if (reverse) Collections.reverse(row);
            result.addAll(row);
        }
        return result;
    }

    private static long horizontalTravel(List<BlockPos> positions) {
        long total = 0;
        for (int index = 1; index < positions.size(); index++) {
            BlockPos previous = positions.get(index - 1);
            BlockPos current = positions.get(index);
            total += Math.abs((long) current.getX() - previous.getX())
                    + Math.abs((long) current.getZ() - previous.getZ());
        }
        return total;
    }

    private static long distanceSquared(BlockPos pos, BlockPos origin) {
        long dx = (long) pos.getX() - origin.getX();
        long dz = (long) pos.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }
}
