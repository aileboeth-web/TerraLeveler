package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Scaffold budget relief no longer stands still for 15-25 s per scan (0.8.84 test run). */
class Improvements0885Test {
    @Test
    void aReliefScanOnlyTriesTheNearestScaffolds() {
        List<BlockPos> sorted = new ArrayList<>();
        for (int i = 0; i < 330; i++) sorted.add(new BlockPos(i, 70, 0));
        List<BlockPos> scanned = ScaffoldBudgetRules.nearestReliefCandidates(sorted);
        assertEquals(ScaffoldBudgetRules.RELIEF_SCAN_MAX_CANDIDATES, scanned.size());
        assertEquals(sorted.subList(0, ScaffoldBudgetRules.RELIEF_SCAN_MAX_CANDIDATES), scanned);

        List<BlockPos> few = sorted.subList(0, 5);
        assertEquals(few, ScaffoldBudgetRules.nearestReliefCandidates(few));
    }

    @Test
    void aRollingSwapDoesNotEndTheCooldown() {
        BlockPos start = new BlockPos(-3515, 66, -10005);
        long exhausted = 1_000L;
        // One more scaffold (rolling overflow) or the same count: still resting.
        assertTrue(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 40, exhausted, 286, 285, start, start));
        assertTrue(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 40, exhausted, 285, 285, start.north(2), start));
        // A scaffold that went away, a real move or the end of the 30 s rest allows a new scan.
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 40, exhausted, 284, 285, start, start));
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 40, exhausted, 286, 285, start.north(8), start));
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 600, exhausted, 286, 285, start, start));
    }
}
