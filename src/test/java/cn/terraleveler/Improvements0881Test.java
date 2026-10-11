package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Climbing back up to high ground without stranding the player (0.8.80 island test run). */
class Improvements0881Test {
    private static SafeRoutePlanner.RouteStep step(int x, int y, int z) {
        return new SafeRoutePlanner.RouteStep(new BlockPos(x, y, z), SafeRoutePlanner.StepType.SCAFFOLD_UP);
    }

    @Test
    void routeRiseIsMeasuredFromTheStartToTheHighestStep() {
        List<SafeRoutePlanner.RouteStep> stair = List.of(step(0, 65, 0), step(1, 80, 0), step(2, 102, 0), step(3, 101, 0));
        assertEquals(38, ClimbBudgetRules.routeRise(64, stair));
        assertEquals(0, ClimbBudgetRules.routeRise(64, List.of(step(0, 63, 0))));
    }

    @Test
    void theStairFromThePitAt126Of128NeedsReliefFirst() {
        // 13:46:31: rise 38, 19 route scaffolds, 126 active of 128.
        assertTrue(ClimbBudgetRules.needsReliefBeforeClimb(38, 126, 19, 128));
        // Enough left afterwards: 90 + 19 = 109 <= 128 - 16.
        assertFalse(ClimbBudgetRules.needsReliefBeforeClimb(38, 90, 19, 128));
        // Short steps and scaffold-free routes are not climbs that need a reserve.
        assertFalse(ClimbBudgetRules.needsReliefBeforeClimb(3, 126, 2, 128));
        assertFalse(ClimbBudgetRules.needsReliefBeforeClimb(38, 126, 0, 128));
    }

    @Test
    void strandedMeansHighAboveTheFinishedGroundOnAnOwnScaffold() {
        // 13:47:58: feet at Y=98 on an own scaffold, target ground Y=62.
        assertTrue(ClimbBudgetRules.isStranded(98, 62, true));
        assertFalse(ClimbBudgetRules.isStranded(98, 62, false));
        assertFalse(ClimbBudgetRules.isStranded(68, 62, true));
        assertTrue(ClimbBudgetRules.isStranded(69, 62, true));
    }
}
