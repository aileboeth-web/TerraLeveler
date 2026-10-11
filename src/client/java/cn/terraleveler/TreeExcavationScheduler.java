package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Keeps connected logs together so one tree is removed before the next tree starts. */
final class TreeExcavationScheduler {
    record Result(List<BlockPos> targets, int excludedScaffolds, int treeCount, int tileCount) {
        Result {
            targets = List.copyOf(targets);
        }
    }

    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(BlockPos::getZ)
                    .thenComparingInt(BlockPos::getY);

    private TreeExcavationScheduler() { }

    static Result schedule(List<BlockPos> source, BlockPos origin, Set<BlockPos> excludedScaffolds) {
        if (source == null || source.isEmpty()) return new Result(List.of(), 0, 0, 0);
        Set<BlockPos> exclusions = excludedScaffolds == null ? Set.of() : excludedScaffolds;
        Set<BlockPos> targets = new LinkedHashSet<>();
        int excluded = 0;
        for (BlockPos candidate : source) {
            if (candidate == null) continue;
            BlockPos target = candidate.immutable();
            if (exclusions.contains(target)) {
                excluded++;
            } else {
                targets.add(target);
            }
        }
        if (targets.isEmpty()) return new Result(List.of(), excluded, 0, 0);

        List<List<BlockPos>> remainingTrees = connectedTrees(targets);
        int treeCount = remainingTrees.size();
        BlockPos cursor = origin == null ? targets.stream().min(POSITION_ORDER).orElseThrow() : origin;
        List<BlockPos> ordered = new ArrayList<>(targets.size());
        while (!remainingTrees.isEmpty()) {
            int treeIndex = nearestTreeIndex(remainingTrees, cursor);
            List<BlockPos> tree = remainingTrees.remove(treeIndex);
            List<BlockPos> orderedTree = orderTreeTopDown(tree, cursor);
            ordered.addAll(orderedTree);
            cursor = orderedTree.getLast();
        }

        Set<Long> tiles = new HashSet<>();
        for (BlockPos target : targets) {
            int tileX = Math.floorDiv(target.getX(), 16);
            int tileZ = Math.floorDiv(target.getZ(), 16);
            tiles.add(((long) tileX << 32) ^ (tileZ & 0xffffffffL));
        }
        return new Result(ordered, excluded, treeCount, tiles.size());
    }

    /**
     * Connected trees keyed by the 16x16 tile of each tree's lowest block (its trunk base),
     * for scheduling a tree just before the ground of its own tile (0.8.72).
     */
    static Map<Long, List<List<BlockPos>>> treesByTile(List<BlockPos> source, Set<BlockPos> excludedScaffolds) {
        if (source == null || source.isEmpty()) return Map.of();
        Set<BlockPos> exclusions = excludedScaffolds == null ? Set.of() : excludedScaffolds;
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (BlockPos candidate : source) {
            if (candidate == null) continue;
            BlockPos target = candidate.immutable();
            if (!exclusions.contains(target)) targets.add(target);
        }
        Map<Long, List<List<BlockPos>>> byTile = new LinkedHashMap<>();
        for (List<BlockPos> tree : connectedTrees(targets)) {
            BlockPos base = tree.stream()
                    .min(Comparator.comparingInt((BlockPos position) -> position.getY()).thenComparing(POSITION_ORDER))
                    .orElseThrow();
            byTile.computeIfAbsent(tileKey(Math.floorDiv(base.getX(), 16), Math.floorDiv(base.getZ(), 16)),
                    ignored -> new ArrayList<>()).add(tree);
        }
        return byTile;
    }

    /** Nearest tree first from {@code cursor}, each tree top-down; same order rules as {@link #schedule}. */
    static List<BlockPos> orderTrees(List<List<BlockPos>> trees, BlockPos cursor) {
        List<List<BlockPos>> remainingTrees = new ArrayList<>(trees);
        List<BlockPos> ordered = new ArrayList<>();
        BlockPos current = cursor;
        while (!remainingTrees.isEmpty()) {
            int treeIndex = nearestTreeIndex(remainingTrees, current);
            List<BlockPos> orderedTree = orderTreeTopDown(remainingTrees.remove(treeIndex), current);
            ordered.addAll(orderedTree);
            current = orderedTree.getLast();
        }
        return ordered;
    }

    static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xffffffffL);
    }

    static Map<BlockPos, Integer> componentIds(List<BlockPos> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Set<BlockPos> unique = new LinkedHashSet<>();
        for (BlockPos target : source) {
            if (target != null) unique.add(target.immutable());
        }
        Map<BlockPos, Integer> ids = new LinkedHashMap<>();
        List<List<BlockPos>> trees = connectedTrees(unique);
        for (int treeIndex = 0; treeIndex < trees.size(); treeIndex++) {
            for (BlockPos target : trees.get(treeIndex)) ids.put(target, treeIndex);
        }
        return Map.copyOf(ids);
    }

    private static List<List<BlockPos>> connectedTrees(Set<BlockPos> targets) {
        Set<BlockPos> remaining = new LinkedHashSet<>(targets);
        List<List<BlockPos>> trees = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            List<BlockPos> tree = new ArrayList<>();
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                tree.add(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos neighbour = current.offset(dx, dy, dz);
                            if (remaining.remove(neighbour)) queue.addLast(neighbour.immutable());
                        }
                    }
                }
            }
            trees.add(tree);
        }
        return trees;
    }

    private static int nearestTreeIndex(List<List<BlockPos>> trees, BlockPos cursor) {
        int bestIndex = 0;
        long bestDistance = minimumHorizontalDistance(trees.getFirst(), cursor);
        BlockPos bestPosition = trees.getFirst().stream().min(POSITION_ORDER).orElseThrow();
        for (int index = 1; index < trees.size(); index++) {
            List<BlockPos> candidate = trees.get(index);
            long distance = minimumHorizontalDistance(candidate, cursor);
            BlockPos position = candidate.stream().min(POSITION_ORDER).orElseThrow();
            if (distance < bestDistance
                    || distance == bestDistance && POSITION_ORDER.compare(position, bestPosition) < 0) {
                bestIndex = index;
                bestDistance = distance;
                bestPosition = position;
            }
        }
        return bestIndex;
    }

    private static List<BlockPos> orderTreeTopDown(List<BlockPos> tree, BlockPos origin) {
        Map<Integer, List<BlockPos>> layers = new TreeMap<>(Comparator.reverseOrder());
        for (BlockPos target : tree) layers.computeIfAbsent(target.getY(), ignored -> new ArrayList<>()).add(target);
        List<BlockPos> ordered = new ArrayList<>(tree.size());
        BlockPos cursor = origin;
        for (List<BlockPos> layer : layers.values()) {
            List<BlockPos> remaining = new ArrayList<>(layer);
            while (!remaining.isEmpty()) {
                int bestIndex = 0;
                for (int index = 1; index < remaining.size(); index++) {
                    BlockPos candidate = remaining.get(index);
                    BlockPos best = remaining.get(bestIndex);
                    long candidateDistance = horizontalDistanceSquared(candidate, cursor);
                    long bestDistance = horizontalDistanceSquared(best, cursor);
                    if (candidateDistance < bestDistance
                            || candidateDistance == bestDistance && POSITION_ORDER.compare(candidate, best) < 0) {
                        bestIndex = index;
                    }
                }
                cursor = remaining.remove(bestIndex);
                ordered.add(cursor);
            }
        }
        return ordered;
    }

    private static long minimumHorizontalDistance(List<BlockPos> tree, BlockPos cursor) {
        long minimum = Long.MAX_VALUE;
        for (BlockPos target : tree) minimum = Math.min(minimum, horizontalDistanceSquared(target, cursor));
        return minimum;
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
