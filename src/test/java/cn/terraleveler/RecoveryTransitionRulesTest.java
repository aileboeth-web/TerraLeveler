package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecoveryTransitionRulesTest {
    @Test
    void touchingWaterAgainKeepsOneRecoveryTransactionAndClearsDryCounters() {
        var result = RecoveryTransitionRules.waterStep(true, false, 4, 7, 10, 160);

        assertEquals(RecoveryTransitionRules.WaterAction.IN_WATER, result.action());
        assertEquals(0, result.stableDryTicks());
        assertEquals(0, result.unstableDryTicks());
    }

    @Test
    void stableDryGroundMustBeConfirmedConsecutivelyBeforeResuming() {
        var confirming = RecoveryTransitionRules.waterStep(false, true, 8, 5, 10, 160);
        var resumed = RecoveryTransitionRules.waterStep(false, true, 9, 0, 10, 160);

        assertEquals(RecoveryTransitionRules.WaterAction.CONFIRM_DRY, confirming.action());
        assertEquals(9, confirming.stableDryTicks());
        assertEquals(0, confirming.unstableDryTicks());
        assertEquals(RecoveryTransitionRules.WaterAction.RESUME_TASK, resumed.action());
        assertEquals(10, resumed.stableDryTicks());
    }

    @Test
    void dryButUnstablePlayerDoesNotRestartFallAndWaterStates() {
        var result = RecoveryTransitionRules.waterStep(false, false, 6, 41, 10, 160);

        assertEquals(RecoveryTransitionRules.WaterAction.WAIT_UNSTABLE, result.action());
        assertEquals(0, result.stableDryTicks());
        assertEquals(42, result.unstableDryTicks());
    }

    @Test
    void prolongedDryInstabilityReachesTheFallSafetyLimit() {
        var result = RecoveryTransitionRules.waterStep(false, false, 0, 160, 10, 160);

        assertEquals(RecoveryTransitionRules.WaterAction.PAUSE_UNSTABLE, result.action());
        assertEquals(161, result.unstableDryTicks());
    }

    @Test
    void dryUnstableEdgeKeepsClimbingOnlyTowardAKnownSafeBank() {
        assertTrue(RecoveryTransitionRules.shouldContinueBankClimb(
                RecoveryTransitionRules.WaterAction.WAIT_UNSTABLE, true));
        assertFalse(RecoveryTransitionRules.shouldContinueBankClimb(
                RecoveryTransitionRules.WaterAction.WAIT_UNSTABLE, false));
        assertFalse(RecoveryTransitionRules.shouldContinueBankClimb(
                RecoveryTransitionRules.WaterAction.CONFIRM_DRY, true));
        assertFalse(RecoveryTransitionRules.shouldContinueBankClimb(
                RecoveryTransitionRules.WaterAction.PAUSE_UNSTABLE, true));
    }

    @Test
    void upwardAssistStopsAsSoonAsFeetClearTheBankTop() {
        assertTrue(RecoveryTransitionRules.needsUpwardBankAssist(54.99, 55));
        assertFalse(RecoveryTransitionRules.needsUpwardBankAssist(55.05, 55));
        assertFalse(RecoveryTransitionRules.needsUpwardBankAssist(55.48, 55));
    }
}
