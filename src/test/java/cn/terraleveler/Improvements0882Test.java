package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Terrace bands instead of digging each 16x16 tile straight down (0.8.80 island test run). */
class Improvements0882Test {
    /** A mountain tile at x 0..15 reaching Y=100 next to a lower tile at x 16..31 reaching Y=75. */
    private static List<BlockPos> mountain() {
        List<BlockPos> ground = new ArrayList<>();
        for (int x = 0; x < 32; x += 3) {
            int top = x < 16 ? 100 : 75;
            for (int y = 63; y <= top; y++) ground.add(new BlockPos(x, y, 0));
        }
        return ground;
    }

    @Test
    void bandsNeverGoBackUp() {
        List<BlockPos> order = FlattenExcavationScheduler.scheduleWithTrees(mountain(), List.of(), new BlockPos(20, 76, 0), Set.of())
                .targets();
        assertEquals(mountain().size(), order.size());
        int previousBand = 0;
        for (BlockPos target : order) {
            int band = FlattenExcavationScheduler.band(100, target.getY());
            assertTrue(band >= previousBand, "went back up to band " + band + " at " + target);
            previousBand = band;
        }
    }

    @Test
    void theMountainTileIsNotDugToTheBottomBeforeItsNeighbourStarts() {
        List<BlockPos> order = FlattenExcavationScheduler.scheduleWithTrees(mountain(), List.of(), new BlockPos(0, 101, 0), Set.of())
                .targets();
        int firstLowTile = -1;
        int lastMountainBottom = -1;
        for (int index = 0; index < order.size(); index++) {
            BlockPos target = order.get(index);
            if (target.getX() >= 16 && firstLowTile < 0) firstLowTile = index;
            if (target.getX() < 16 && target.getY() == 63) lastMountainBottom = index;
        }
        assertTrue(firstLowTile >= 0 && firstLowTile < lastMountainBottom);
        // When work moves on to the lower tile, the mountain has been cut down near the neighbour's top.
        int mountainLowestBeforeSwitch = Integer.MAX_VALUE;
        for (int index = 0; index < firstLowTile; index++) {
            mountainLowestBeforeSwitch = Math.min(mountainLowestBeforeSwitch, order.get(index).getY());
        }
        assertTrue(mountainLowestBeforeSwitch >= 75 - FlattenExcavationScheduler.LAYER_BAND,
                "mountain dug to " + mountainLowestBeforeSwitch + " before the neighbour started");
    }
}
