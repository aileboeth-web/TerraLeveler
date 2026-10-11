package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Orders flatten excavation locally while preserving top-down safety inside each chunk tile. */
final class FlattenExcavationScheduler {
    record Result(List<BlockPos> targets, int excludedScaffolds, int tileCount) {
        Result {
            targets = List.copyOf(targets);
        }
    }

    private record Tile(int x, int z) { }

    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(position -> position.getZ())
                    .thenComparingInt(position -> position.getY());

    /**
     * Height of one terrace band. Every tile is cut through one band before any tile goes
     * deeper, so neighbouring tiles never differ by much more than a band and the player can
     * walk between them. Until 0.8.81 each 16x16 tile was dug straight down to the target
     * ground: on a mountain (island region 5/24, top Y=120) that left 40-block pits whose
     * walls could only be climbed with long scaffold stairs, and the player was stranded.
     */
    static final int LAYER_BAND = 6;

    private FlattenExcavationScheduler() { }

    static int band(int topY, int y) {
        return Math.max(0, Math.floorDiv(topY - y, LAYER_BAND));
    }

    static Result schedule(List<BlockPos> source, BlockPos origin,
                           Set<BlockPos> excludedScaffolds) {
        if (source == null || source.isEmpty()) return new Result(List.of(), 0, 0);
        Set<BlockPos> exclusions = excludedScaffolds == null ? Set.of() : excludedScaffolds;
        Map<Tile, List<BlockPos>> remainingTiles = new HashMap<>();
        Set<BlockPos> uniqueTargets = new LinkedHashSet<>();
        int excluded = 0;
        for (BlockPos candidate : source) {
            if (candidate == null || !uniqueTargets.add(candidate)) continue;
            if (exclusions.contains(candidate)) {
                excluded++;
                continue;
            }
            BlockPos target = candidate.immutable();
            Tile tile = new Tile(Math.floorDiv(target.getX(), 16),
                    Math.floorDiv(target.getZ(), 16));
            remainingTiles.computeIfAbsent(tile, ignored -> new ArrayList<>()).add(target);
        }
        if (remainingTiles.isEmpty()) return new Result(List.of(), excluded, 0);

        int tileCount = remainingTiles.size();
        Map<Tile, int[]> tileBounds = new HashMap<>();
        remainingTiles.forEach((tile, targets) -> tileBounds.put(tile, columnBounds(targets)));
        BlockPos cursor = origin == null
                ? remainingTiles.values().stream().flatMap(List::stream).min(POSITION_ORDER).orElseThrow()
                : origin;
        List<BlockPos> ordered = new ArrayList<>(uniqueTargets.size() - excluded);
        while (!remainingTiles.isEmpty()) {
            Tile nextTile = selectNearestTile(remainingTiles, tileBounds, cursor);
            List<BlockPos> tileTargets = remainingTiles.remove(nextTile);
            List<BlockPos> orderedTile = orderTileTopDown(tileTargets, cursor);
            ordered.addAll(orderedTile);
            cursor = orderedTile.getLast();
        }
        return new Result(ordered, excluded, tileCount);
    }

    /**
     * Ground targets and trees, terrace band by terrace band (see {@link #LAYER_BAND}); inside a
     * band the nearest tile is taken next. When a tile is reached for the first time, the trees
     * standing in it are cut first (so their trunk base still has ground under it), then the
     * tile's part of the band is excavated top-down. Until 0.8.71 every tree of the whole selection was
     * cut first, which in a 121-block strip meant about four minutes of walking between
     * trees before the first ground block (249 blocks in the first four minutes).
     */
    static Result scheduleWithTrees(List<BlockPos> groundSource, List<BlockPos> treeSource, BlockPos origin,
                                    Set<BlockPos> excludedScaffolds) {
        Set<BlockPos> exclusions = excludedScaffolds == null ? Set.of() : excludedScaffolds;
        Map<Long, List<List<BlockPos>>> treesByTile = TreeExcavationScheduler.treesByTile(treeSource, exclusions);
        int excluded = 0;
        Set<BlockPos> treeBlocks = new java.util.HashSet<>();
        if (treeSource != null) {
            for (BlockPos candidate : treeSource) {
                if (candidate != null && exclusions.contains(candidate)) excluded++;
            }
        }
        treesByTile.values().forEach(trees -> trees.forEach(treeBlocks::addAll));

        Map<Tile, List<BlockPos>> groundTiles = new HashMap<>();
        Set<BlockPos> uniqueTargets = new LinkedHashSet<>();
        if (groundSource != null) {
            for (BlockPos candidate : groundSource) {
                if (candidate == null || !uniqueTargets.add(candidate)) continue;
                if (exclusions.contains(candidate)) {
                    excluded++;
                    continue;
                }
                if (treeBlocks.contains(candidate)) continue;
                BlockPos target = candidate.immutable();
                groundTiles.computeIfAbsent(new Tile(Math.floorDiv(target.getX(), 16), Math.floorDiv(target.getZ(), 16)),
                        ignored -> new ArrayList<>()).add(target);
            }
        }

        // Tile distances use every block a tile will work on: its ground targets and its trees.
        Map<Tile, List<BlockPos>> tileBlocks = new HashMap<>();
        groundTiles.forEach((tile, targets) -> tileBlocks.computeIfAbsent(tile, ignored -> new ArrayList<>()).addAll(targets));
        Map<Tile, List<List<BlockPos>>> tileTrees = new HashMap<>();
        treesByTile.forEach((key, trees) -> {
            Tile tile = new Tile((int) (key >> 32), (int) key.longValue());
            tileTrees.put(tile, trees);
            List<BlockPos> blocks = tileBlocks.computeIfAbsent(tile, ignored -> new ArrayList<>());
            trees.forEach(blocks::addAll);
        });
        if (tileBlocks.isEmpty()) return new Result(List.of(), excluded, 0);

        int tileCount = tileBlocks.size();
        int topY = Integer.MIN_VALUE;
        for (List<BlockPos> targets : groundTiles.values()) {
            for (BlockPos target : targets) topY = Math.max(topY, target.getY());
        }
        TreeMap<Integer, Map<Tile, List<BlockPos>>> bands = new TreeMap<>();
        for (Map.Entry<Tile, List<BlockPos>> entry : groundTiles.entrySet()) {
            for (BlockPos target : entry.getValue()) {
                bands.computeIfAbsent(band(topY, target.getY()), ignored -> new HashMap<>())
                        .computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(target);
            }
        }
        // Trees are cut on a tile's first visit; a tile with trees but no ground is visited in
        // the band of its lowest tree block.
        for (Map.Entry<Tile, List<List<BlockPos>>> entry : tileTrees.entrySet()) {
            Tile tile = entry.getKey();
            boolean visited = bands.values().stream().anyMatch(tiles -> tiles.containsKey(tile));
            if (visited) continue;
            int lowest = Integer.MAX_VALUE;
            for (List<BlockPos> tree : entry.getValue()) {
                for (BlockPos block : tree) lowest = Math.min(lowest, block.getY());
            }
            int treeBand = topY == Integer.MIN_VALUE ? 0 : band(topY, lowest);
            bands.computeIfAbsent(treeBand, ignored -> new HashMap<>()).put(tile, new ArrayList<>());
        }

        BlockPos cursor = origin == null
                ? tileBlocks.values().stream().flatMap(List::stream).min(POSITION_ORDER).orElseThrow()
                : origin;
        List<BlockPos> ordered = new ArrayList<>();
        Set<Tile> treesCut = new java.util.HashSet<>();
        for (Map<Tile, List<BlockPos>> bandTiles : bands.values()) {
            Map<Tile, List<BlockPos>> remaining = new HashMap<>();
            Map<Tile, int[]> tileBounds = new HashMap<>();
            for (Map.Entry<Tile, List<BlockPos>> entry : bandTiles.entrySet()) {
                List<BlockPos> blocks = new ArrayList<>(entry.getValue());
                List<List<BlockPos>> trees = tileTrees.get(entry.getKey());
                if (trees != null && !treesCut.contains(entry.getKey())) trees.forEach(blocks::addAll);
                if (blocks.isEmpty()) continue;
                remaining.put(entry.getKey(), blocks);
                tileBounds.put(entry.getKey(), columnBounds(blocks));
            }
            while (!remaining.isEmpty()) {
                Tile nextTile = selectNearestTile(remaining, tileBounds, cursor);
                remaining.remove(nextTile);
                List<List<BlockPos>> trees = tileTrees.get(nextTile);
                if (trees != null && !trees.isEmpty() && treesCut.add(nextTile)) {
                    List<BlockPos> orderedTrees = TreeExcavationScheduler.orderTrees(trees, cursor);
                    ordered.addAll(orderedTrees);
                    cursor = orderedTrees.getLast();
                }
                List<BlockPos> ground = bandTiles.get(nextTile);
                if (ground != null && !ground.isEmpty()) {
                    List<BlockPos> orderedTile = orderTileTopDown(ground, cursor);
                    ordered.addAll(orderedTile);
                    cursor = orderedTile.getLast();
                }
            }
        }
        return new Result(ordered, excluded, tileCount);
    }

    /**
     * Same choice as scanning every target of every tile, but a tile's bounding
     * rectangle is a lower bound on its distance, so exact distances are only
     * computed (over distinct columns) for tiles that can still win.
     */
    private static Tile selectNearestTile(Map<Tile, List<BlockPos>> tiles, Map<Tile, int[]> tileBounds, BlockPos cursor) {
        List<Map.Entry<Tile, List<BlockPos>>> entries = new ArrayList<>(tiles.entrySet());
        long[] lowerBounds = new long[entries.size()];
        Integer[] order = new Integer[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            lowerBounds[index] = rectangleDistanceSquared(tileBounds.get(entries.get(index).getKey()), cursor);
            order[index] = index;
        }
        java.util.Arrays.sort(order, Comparator.comparingLong(index -> lowerBounds[index]));

        Tile best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int index : order) {
            if (best != null && lowerBounds[index] > bestDistance) break;
            Map.Entry<Tile, List<BlockPos>> entry = entries.get(index);
            long distance = minimumColumnDistanceSquared(entry.getValue(), cursor);
            Tile tile = entry.getKey();
            if (best == null || distance < bestDistance
                    || distance == bestDistance && tile.x() < best.x()
                    || distance == bestDistance && tile.x() == best.x() && tile.z() < best.z()) {
                best = tile;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int[] columnBounds(List<BlockPos> targets) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos target : targets) {
            minX = Math.min(minX, target.getX());
            maxX = Math.max(maxX, target.getX());
            minZ = Math.min(minZ, target.getZ());
            maxZ = Math.max(maxZ, target.getZ());
        }
        return new int[]{minX, maxX, minZ, maxZ};
    }

    private static long rectangleDistanceSquared(int[] bounds, BlockPos cursor) {
        int minX = bounds[0];
        int maxX = bounds[1];
        int minZ = bounds[2];
        int maxZ = bounds[3];
        long dx = cursor.getX() < minX ? (long) minX - cursor.getX() : cursor.getX() > maxX ? (long) cursor.getX() - maxX : 0L;
        long dz = cursor.getZ() < minZ ? (long) minZ - cursor.getZ() : cursor.getZ() > maxZ ? (long) cursor.getZ() - maxZ : 0L;
        return dx * dx + dz * dz;
    }

    private static long minimumColumnDistanceSquared(List<BlockPos> targets, BlockPos cursor) {
        long best = Long.MAX_VALUE;
        Set<Long> seenColumns = new java.util.HashSet<>();
        for (BlockPos target : targets) {
            if (!seenColumns.add(((long) target.getX() << 32) ^ (target.getZ() & 0xffffffffL))) continue;
            best = Math.min(best, horizontalDistanceSquared(target, cursor));
            if (best == 0L) break;
        }
        return best;
    }

    private static List<BlockPos> orderTileTopDown(List<BlockPos> targets, BlockPos origin) {
        Map<Integer, List<BlockPos>> layers = new TreeMap<>(Comparator.reverseOrder());
        for (BlockPos target : targets) {
            layers.computeIfAbsent(target.getY(), ignored -> new ArrayList<>()).add(target);
        }
        List<BlockPos> ordered = new ArrayList<>(targets.size());
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
                            || candidateDistance == bestDistance
                            && POSITION_ORDER.compare(candidate, best) < 0) {
                        bestIndex = index;
                    }
                }
                BlockPos next = remaining.remove(bestIndex);
                ordered.add(next);
                cursor = next;
            }
        }
        return ordered;
    }

    private static long horizontalDistanceSquared(BlockPos position, BlockPos origin) {
        long dx = (long) position.getX() - origin.getX();
        long dz = (long) position.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }
}
