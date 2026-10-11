package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BatchMiningSafetyRulesTest {
    @Test
    void stationaryBubbleOnlyMinesTheReachableQueueHeadsLayer() {
        assertTrue(BatchMiningSafetyRules.allowsCandidate(false, 67, 67, false, false));
        assertFalse(BatchMiningSafetyRules.allowsCandidate(false, 66, 67, false, false));
        assertFalse(BatchMiningSafetyRules.allowsCandidate(false, 68, 67, false, false));
    }

    @Test
    void movingBubbleKeepsThreeDimensionalReachButPreservesRouteAndOwnership() {
        assertTrue(BatchMiningSafetyRules.allowsCandidate(true, 62, 67, false, false));
        assertFalse(BatchMiningSafetyRules.allowsCandidate(true, 62, 67, true, false));
        assertFalse(BatchMiningSafetyRules.allowsCandidate(true, 62, 67, false, true));
    }

    @Test
    void fallingColumnsAreClearedFromTheTopInsteadOfExcludedEntirely() {
        assertTrue(BatchMiningSafetyRules.allowsFallingColumnPosition(false));
        assertFalse(BatchMiningSafetyRules.allowsFallingColumnPosition(true));
    }
}
