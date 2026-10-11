package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The island region the player stands in is started first (0.8.83 test run). */
class Improvements0884Test {
    private static Set<BlockPos> rectangle(int minX, int maxX, int minZ, int maxZ) {
        Set<BlockPos> columns = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) columns.add(new BlockPos(x, 0, z));
        }
        return columns;
    }

    @Test
    void theRegionUnderThePlayerBeatsANeighbourWithACloserCentre() {
        // A full 64x64 region (the mountain) and a thin strip north of it, like region 5 and the strip at its foot.
        Set<BlockPos> columns = rectangle(0, 63, 0, 63);
        columns.addAll(rectangle(0, 63, 64, 70));
        List<IslandRegionPlanner.Region> regions = IslandRegionPlanner.partition(columns, 4);
        assertEquals(2, regions.size());
        IslandRegionPlanner.Region mountain = regions.stream().filter(region -> region.minZ() == 0).findFirst().orElseThrow();
        IslandRegionPlanner.Region strip = regions.stream().filter(region -> region.minZ() == 64).findFirst().orElseThrow();

        // Player inside the mountain region near the strip: the strip's centre is closer.
        int playerX = 10;
        int playerZ = 60;
        assertEquals(true, strip.distanceSquared(playerX, playerZ) < mountain.distanceSquared(playerX, playerZ));
        assertEquals(0L, mountain.nearestColumnDistanceSquared(playerX, playerZ));
        assertEquals(mountain, IslandRegionPlanner.nearest(regions, playerX, playerZ).orElseThrow());
        assertEquals(mountain, IslandRegionPlanner.nextLoaded(regions, playerX, playerZ, chunk -> true).orElseThrow());

        // Standing outside both, the region with the nearer column still wins.
        assertEquals(strip, IslandRegionPlanner.nearest(regions, 10, 75).orElseThrow());
    }
}
