package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Rules added in 0.8.75 after the 0.8.74 test run (waterfall drained from the bottom, unhittable zombie). */
class Improvements0875Test {
    // index:           0      1      2      3
    private static final boolean[] SOURCE = {false, true,  true,  false};
    private static final boolean[] DIRECT = {true,  false, false, true};
    private static final int[] HEIGHT =     {62,    69,    70,    63};

    private static int select(boolean[] skipped) {
        return FlattenFluidExecutionRules.selectNextPlacement(0, 4,
                index -> true,
                index -> skipped[index],
                index -> SOURCE[index],
                index -> DIRECT[index],
                index -> HEIGHT[index],
                index -> index);
    }

    @Test
    void sourcesComeFirstHighestFirstEvenWhenAFlowingCellIsWithinReach() {
        // 0.8.74 filled the reachable flowing cells at Y=62 while the source at Y=69-70 kept feeding them.
        assertEquals(2, select(new boolean[4]));
    }

    @Test
    void unreachableOrDryPlacementsAreNotChosenAgain() {
        // 0.8.74 "rotated" an unreachable cell to the back of the list and then selected it again.
        assertEquals(1, select(new boolean[]{false, false, true, false}));
        // With every source skipped, the higher directly reachable flowing cell is next.
        assertEquals(3, select(new boolean[]{false, true, true, false}));
        assertEquals(-1, select(new boolean[]{true, true, true, true}));
    }

    @Test
    void directReachBreaksTiesBetweenSources() {
        int selected = FlattenFluidExecutionRules.selectNextPlacement(0, 2,
                index -> true, index -> false, index -> true,
                index -> index == 1, index -> 64, index -> index);
        assertEquals(1, selected);
    }

    @Test
    void flowingWaterIsGivenTimeToRecedeOnceItsSourcesAreFilled() {
        assertTrue(FlattenFluidExecutionRules.shouldWaitForRecession(false, true, 0, 60));
        assertFalse(FlattenFluidExecutionRules.shouldWaitForRecession(false, true, 60, 60));
        assertFalse(FlattenFluidExecutionRules.shouldWaitForRecession(true, true, 0, 60));
        assertFalse(FlattenFluidExecutionRules.shouldWaitForRecession(false, false, 0, 60));
    }

    @Test
    void unhittableThreatsAreGivenUpAndIgnoredLonger() {
        assertTrue(ConstructionController.MAX_DEFENSE_APPROACH_STALLS > 0);
        assertTrue(ConstructionController.UNSEEN_THREAT_IGNORE_TICKS > 100L);
    }
}
