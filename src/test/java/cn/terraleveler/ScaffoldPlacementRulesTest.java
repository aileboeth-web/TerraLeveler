package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScaffoldPlacementRulesTest {
    @Test
    void collisionlessDryPlantsAreClearedBeforeSolidScaffoldPlacement() {
        assertTrue(ScaffoldPlacementRules.shouldClearTarget(false, true, true));
        assertFalse(ScaffoldPlacementRules.shouldClearTarget(true, true, true));
        assertFalse(ScaffoldPlacementRules.shouldClearTarget(false, false, true));
        assertFalse(ScaffoldPlacementRules.shouldClearTarget(false, true, false));
    }

    @Test
    void predictedAirStillWaitsForTheActiveBreakTransaction() {
        assertTrue(ScaffoldPlacementRules.shouldContinueClearance(false, true));
        assertTrue(ScaffoldPlacementRules.shouldContinueClearance(true, false));
        assertFalse(ScaffoldPlacementRules.shouldContinueClearance(false, false));
    }
}
