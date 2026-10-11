package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Landing with the water bucket from the sky, without a height limit (user decision after the 0.8.91 test run). */
class Improvements0892Test {
    private static final SafeRoutePlanner.RouteEnvironment FLATTENED = pos -> pos.getY() <= 61
            ? SafeRoutePlanner.CellKind.SOLID : SafeRoutePlanner.CellKind.OPEN;

    @Test
    void aLandingThirtySevenBlocksDownIsFound() {
        // 0.8.91: standing at -3526,99,-10001 over flattened ground with its top at Y=61.
        BlockPos start = new BlockPos(-3526, 99, -10001);
        WaterBucketFallPlanner.Result fall = WaterBucketFallPlanner.findLanding(
                FLATTENED, start, new BlockPos(-3525, 73, -10002), WaterBucketFallPlanner.MAX_BUCKET_FALL);
        assertNotNull(fall);
        assertEquals(62, fall.landingFeet().getY());
        assertEquals(37, fall.dropDistance());
    }

    @Test
    void onlyTheSkyWithATargetBelowOrFarAwayLandsFirst() {
        BlockPos support = new BlockPos(-3526, 98, -10001);
        int drop = ClimbBudgetRules.openDropBelow(support, FLATTENED);
        assertEquals(36, drop);
        BlockPos start = support.above();
        // Target 26 below: land first. Target 76 blocks west at about the same height: land first.
        assertTrue(ClimbBudgetRules.skyBucketFirst(start, new BlockPos(-3525, 73, -10002), drop));
        assertTrue(ClimbBudgetRules.skyBucketFirst(start, new BlockPos(-3602, 99, -10026), drop));
        // A target close by at the same height: keep working from the bridge.
        assertFalse(ClimbBudgetRules.skyBucketFirst(start, new BlockPos(-3522, 99, -10003), drop));
        // A terrace bridge (open air only 5 deep below it) never counts as the sky.
        assertFalse(ClimbBudgetRules.skyBucketFirst(start, new BlockPos(-3525, 73, -10002), 5));
    }
}
