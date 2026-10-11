package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlattenCleanupRouteCapacityTest {
    @Test
    void finalCleanupReservesItsVerifiedRoundTripAboveNormalBudget() {
        int reservedPeak = RouteExecutionRules.cleanupReservedPeak(113, 16);

        assertEquals(129, reservedPeak);
        assertEquals(128, RouteExecutionRules.effectiveScaffoldLimit(128, reservedPeak, false));
        assertEquals(129, RouteExecutionRules.effectiveScaffoldLimit(128, reservedPeak, true));
        assertFalse(RouteExecutionRules.mayPlaceScaffold(128, 128));
        assertTrue(RouteExecutionRules.mayPlaceScaffold(128, 129));
        assertFalse(RouteExecutionRules.mayPlaceScaffold(129, 129));
    }

    @Test
    void committedCleanupRoundTripCannotRollAwayItsOutboundSupports() {
        assertFalse(RouteExecutionRules.allowsRollingRetirement(true));
        assertTrue(RouteExecutionRules.allowsRollingRetirement(false));
    }

    @Test
    void invalidReservationsAndInsufficientMaterialAreRejected() {
        assertEquals(-1, RouteExecutionRules.cleanupReservedPeak(-1, 16));
        assertEquals(-1, RouteExecutionRules.cleanupReservedPeak(113, 0));
        assertEquals(-1, RouteExecutionRules.cleanupReservedPeak(Integer.MAX_VALUE, 1));
        assertTrue(RouteExecutionRules.hasCleanupRouteMaterial(16, 59, false));
        assertTrue(RouteExecutionRules.hasCleanupRouteMaterial(16, 15, true));
        assertFalse(RouteExecutionRules.hasCleanupRouteMaterial(16, 15, false));
        assertFalse(RouteExecutionRules.hasCleanupRouteMaterial(-1, 59, false));
    }
}
