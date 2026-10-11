package cn.terraleveler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandRegionPlannerTest {
    @Test
    void partitionIsChunkAlignedAndCoversEveryColumnExactlyOnce() {
        Set<BlockPos> columns = rectangle(-70, 70, -10, 10);

        List<IslandRegionPlanner.Region> regions = IslandRegionPlanner.partition(columns, 4);

        Set<BlockPos> covered = new HashSet<>();
        for (IslandRegionPlanner.Region region : regions) {
            for (BlockPos column : region.columns()) {
                assertTrue(covered.add(column), "column in two regions: " + column);
                assertEquals(region.regionX(), Math.floorDiv(column.getX(), 64));
                assertEquals(region.regionZ(), Math.floorDiv(column.getZ(), 64));
            }
            assertTrue(region.chunks().size() <= 16);
        }
        assertEquals(columns, covered);
        // x -70..70 spans region columns -2..1, z -10..10 spans -1..0.
        assertEquals(8, regions.size());
    }

    @Test
    void negativeCoordinatesUseFloorDivision() {
        List<IslandRegionPlanner.Region> regions = IslandRegionPlanner.partition(
                Set.of(new BlockPos(-1, 0, -1), new BlockPos(0, 0, 0)), 4);

        assertEquals(2, regions.size());
        assertTrue(regions.stream().anyMatch(region -> region.regionX() == -1 && region.regionZ() == -1));
        assertTrue(regions.stream().anyMatch(region -> region.regionX() == 0 && region.regionZ() == 0));
    }

    @Test
    void nextLoadedSkipsNearerRegionsWithAnyUnloadedChunk() {
        List<IslandRegionPlanner.Region> regions = IslandRegionPlanner.partition(rectangle(0, 191, 0, 63), 4);
        long partiallyUnloadedChunk = IslandSurveyCache.chunkKey(4, 0); // inside region x=64..127

        IslandRegionPlanner.Region next = IslandRegionPlanner.nextLoaded(regions, 90, 30,
                chunk -> chunk != partiallyUnloadedChunk).orElseThrow();

        assertEquals(0, next.regionX());
        assertEquals(1, IslandRegionPlanner.nearest(regions, 90, 30).orElseThrow().regionX());
    }

    @Test
    void nextLoadedIsEmptyWhenNothingIsFullyLoaded() {
        List<IslandRegionPlanner.Region> regions = IslandRegionPlanner.partition(rectangle(0, 63, 0, 63), 4);

        assertFalse(IslandRegionPlanner.nextLoaded(regions, 0, 0, chunk -> false).isPresent());
    }

    @Test
    void regionFingerprintMatchesIslandPlanFingerprintForSameColumns() {
        Set<BlockPos> columns = rectangle(3, 5, 7, 9);

        assertEquals(FlattenPlan.columnsFingerprint(columns), FlattenPlan.columnsFingerprint(new HashSet<>(columns)));
    }

    private static Set<BlockPos> rectangle(int minX, int maxX, int minZ, int maxZ) {
        Set<BlockPos> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) result.add(new BlockPos(x, 0, z));
        return result;
    }
}
