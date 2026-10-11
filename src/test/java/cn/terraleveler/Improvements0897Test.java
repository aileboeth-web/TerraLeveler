package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Only water surrounded by island land is an island pond (0.8.95 test run: two village farm
 * channels across the boundary river were filled as ponds and could never be reached).
 */
class Improvements0897Test {
    /**
     * Island: x 0..30, z 0..30 without the north-east corner. A two-wide river cuts that
     * corner off; behind it lies village land (x 15..30, z 0..12) with a three-cell farm
     * channel at x 22. The island itself has a one-cell pond at (5, 20). Outside is sea.
     */
    private static IslandFlattenAreaDetector.Cell cell(int x, int z) {
        if (x < 0 || x > 30 || z < 0 || z > 30) return IslandFlattenAreaDetector.Cell.WATER;
        if (x == 5 && z == 20) return IslandFlattenAreaDetector.Cell.WATER;
        if (x == 22 && z >= 5 && z <= 7) return IslandFlattenAreaDetector.Cell.WATER;
        boolean river = (x == 13 || x == 14) && z <= 14 || (z == 13 || z == 14) && x >= 13;
        return river ? IslandFlattenAreaDetector.Cell.WATER : IslandFlattenAreaDetector.Cell.LAND;
    }

    @Test
    void farmChannelAcrossTheRiverIsNotAnIslandPond() {
        IslandFlattenAreaDetector.Settings settings = new IslandFlattenAreaDetector.Settings(64, 100000, 0, true, false, 4);
        IslandFlattenAreaDetector.Job job = new IslandFlattenAreaDetector.Job(2, 2, settings, Improvements0897Test::cell);
        while (!job.step(Long.MAX_VALUE)) { }
        IslandFlattenAreaDetector.Result result = job.result();

        assertEquals(IslandFlattenAreaDetector.Status.READY, result.status());
        assertTrue(result.columns().contains(new BlockPos(5, 0, 20)), "the pond inside the island is still filled");
        assertEquals(1, result.includedInteriorWaterColumns());
        for (int z = 5; z <= 7; z++) {
            assertFalse(result.columns().contains(new BlockPos(22, 0, z)), "farm channel cell z=" + z);
        }
        assertFalse(result.columns().contains(new BlockPos(20, 0, 6)), "village land stays outside");
        assertEquals(3, job.foreignWaterColumns());
    }

    @Test
    void withoutTheSwitchNoWaterIsIncluded() {
        IslandFlattenAreaDetector.Settings settings = new IslandFlattenAreaDetector.Settings(64, 100000, 0, false, false, 4);
        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(2, 2, settings, Improvements0897Test::cell);

        assertEquals(0, result.includedInteriorWaterColumns());
        assertFalse(result.columns().contains(new BlockPos(5, 0, 20)));
        assertFalse(result.columns().contains(new BlockPos(22, 0, 6)));
    }
}
