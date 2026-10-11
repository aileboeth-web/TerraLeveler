package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkRouteCommitRulesTest {
    @Test
    void validatedWorkRouteCannotBePreemptedByOptionalInventoryWork() {
        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(true, false));
        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(false, true));
        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(true, true));
        assertTrue(RouteExecutionRules.mayRunOpportunisticInventory(false, false));

        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(false, false, false, false));
        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(false, false, true, true));
        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(true, false, true, false));
        assertTrue(RouteExecutionRules.mayRunOpportunisticInventory(false, false, true, false));
    }

    @Test
    void runtimeMismatchMayBorrowOnlyTheBoundedRemainingRouteBudget() {
        assertEquals(131, RouteExecutionRules.boundedTemporaryRouteLimit(128, 3, 128, 144));
        assertEquals(132, RouteExecutionRules.boundedTemporaryRouteLimit(128, 3, 132, 144));
        assertEquals(-1, RouteExecutionRules.boundedTemporaryRouteLimit(128, 17, 128, 144));
        assertEquals(-1, RouteExecutionRules.boundedTemporaryRouteLimit(128, 0, 128, 144));
    }

    @Test
    void thirdUncommittedArrivalTripsTheRouteLoopBreaker() {
        assertFalse(RouteExecutionRules.repeatedWorkRouteArrivalsExceeded(1, 3));
        assertFalse(RouteExecutionRules.repeatedWorkRouteArrivalsExceeded(2, 3));
        assertTrue(RouteExecutionRules.repeatedWorkRouteArrivalsExceeded(3, 3));
    }

    @Test
    void fullBudgetTwoStepBridgeCommitsItsTaskBeforePickingRetiredDrops() {
        assertTrue(RouteExecutionRules.mayBootstrapRollingScaffoldOverflow(
                128, 128, true, false, false));
        assertTrue(RouteExecutionRules.mayAdvanceRollingScaffoldOverflow(
                129, 128, true, true, false));

        assertFalse(RouteExecutionRules.mayRunOpportunisticInventory(false, true));
        assertFalse(RouteExecutionRules.mayReleaseRollingScaffoldOverflow(true, true, true));

        assertTrue(RouteExecutionRules.mayRunOpportunisticInventory(false, false));
        assertTrue(RouteExecutionRules.mayReleaseRollingScaffoldOverflow(true, true, false));
    }
}
