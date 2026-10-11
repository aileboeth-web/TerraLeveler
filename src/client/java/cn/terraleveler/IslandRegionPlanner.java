package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;

/**
 * Splits an island that is larger than the client's loaded terrain into
 * chunk-aligned regions. Each region is small enough that, standing anywhere in
 * a finished region, an edge-adjacent region is normally still loaded, so it
 * can be planned and rescanned from live terrain like an ordinary island plan.
 */
final class IslandRegionPlanner {
    /** Region side in chunks. 2*4-1 = 7 chunks reach fits servers with view distance 8+. */
    static final int REGION_CHUNKS = 4;

    record Region(int regionX, int regionZ, Set<BlockPos> columns, Set<Long> chunks,
                  int minX, int maxX, int minZ, int maxZ) {
        Region {
            columns = Set.copyOf(columns);
            chunks = Set.copyOf(chunks);
        }

        String id() { return regionX + "," + regionZ; }
        int centerX() { return Math.floorDiv(minX + maxX, 2); }
        int centerZ() { return Math.floorDiv(minZ + maxZ, 2); }

        long distanceSquared(int x, int z) {
            long dx = centerX() - x;
            long dz = centerZ() - z;
            return dx * dx + dz * dz;
        }

        /** Horizontal distance to the region's nearest own column; 0 when standing on one. */
        long nearestColumnDistanceSquared(int x, int z) {
            long best = Long.MAX_VALUE;
            for (BlockPos column : columns) {
                long dx = (long) column.getX() - x;
                long dz = (long) column.getZ() - z;
                best = Math.min(best, dx * dx + dz * dz);
                if (best == 0L) break;
            }
            return best;
        }
    }

    private IslandRegionPlanner() { }

    static List<Region> partition(Set<BlockPos> columns, int regionChunks) {
        int size = Math.max(1, regionChunks) * 16;
        Map<Long, List<BlockPos>> grouped = new TreeMap<>();
        for (BlockPos column : columns) {
            int regionX = Math.floorDiv(column.getX(), size);
            int regionZ = Math.floorDiv(column.getZ(), size);
            grouped.computeIfAbsent(IslandSurveyCache.chunkKey(regionX, regionZ), ignored -> new ArrayList<>())
                    .add(new BlockPos(column.getX(), 0, column.getZ()));
        }
        List<Region> regions = new ArrayList<>(grouped.size());
        for (List<BlockPos> members : grouped.values()) {
            members.sort(Comparator.comparingInt((BlockPos pos) -> pos.getZ()).thenComparingInt(BlockPos::getX));
            Set<Long> chunks = new LinkedHashSet<>();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos column : members) {
                chunks.add(IslandSurveyCache.chunkKey(column.getX() >> 4, column.getZ() >> 4));
                minX = Math.min(minX, column.getX());
                maxX = Math.max(maxX, column.getX());
                minZ = Math.min(minZ, column.getZ());
                maxZ = Math.max(maxZ, column.getZ());
            }
            BlockPos first = members.getFirst();
            regions.add(new Region(Math.floorDiv(first.getX(), size), Math.floorDiv(first.getZ(), size),
                    new LinkedHashSet<>(members), chunks, minX, maxX, minZ, maxZ));
        }
        return regions;
    }

    /** Nearest remaining region whose every chunk is loaded now, measured from the player. */
    static Optional<Region> nextLoaded(List<Region> remaining, int playerX, int playerZ, LongPredicate chunkLoaded) {
        return byDistance(remaining, playerX, playerZ).stream()
                .filter(region -> region.chunks().stream().allMatch(chunkLoaded::test))
                .findFirst();
    }

    static Optional<Region> nearest(List<Region> remaining, int playerX, int playerZ) {
        return byDistance(remaining, playerX, playerZ).stream().findFirst();
    }

    /**
     * Regions are ranked by the distance to their nearest own column, so the region the player
     * stands in comes first. Until 0.8.83 the bounding-box centre decided: on a restart the
     * player stood on the unfinished mountain of one region (centre 31 blocks away), but the
     * thin strip at its foot (centre 21 blocks away) was started instead, and its first target
     * hung on the mountain's cliff face out of reach (NO_SAFE_GOAL, paused at once).
     */
    private static List<Region> byDistance(List<Region> remaining, int playerX, int playerZ) {
        Map<Region, Long> nearest = new java.util.IdentityHashMap<>();
        for (Region region : remaining) nearest.put(region, region.nearestColumnDistanceSquared(playerX, playerZ));
        List<Region> ordered = new ArrayList<>(remaining);
        ordered.sort(Comparator.comparingLong((Region region) -> nearest.get(region))
                .thenComparingLong(region -> region.distanceSquared(playerX, playerZ))
                .thenComparingInt(Region::regionZ).thenComparingInt(Region::regionX));
        return ordered;
    }
}
