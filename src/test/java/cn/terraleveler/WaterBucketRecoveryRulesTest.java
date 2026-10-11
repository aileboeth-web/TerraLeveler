package cn.terraleveler;

import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WaterBucketRecoveryRulesTest {
    @Test
    void rollingScaffoldCleanupCannotInterruptAnActiveBucketFall() {
        assertFalse(WaterBucketRecoveryRules.mayRunRollingOverflowRelease(true));
        assertTrue(WaterBucketRecoveryRules.mayRunRollingOverflowRelease(false));
    }

    @Test
    void bucketRouteStepWaitsForItsTransactionToFinish() {
        assertFalse(WaterBucketRecoveryRules.mayAdvanceRouteStep(true, true));
        assertTrue(WaterBucketRecoveryRules.mayAdvanceRouteStep(true, false));
        assertTrue(WaterBucketRecoveryRules.mayAdvanceRouteStep(false, true));
    }

    @Test
    void placementOnlyWaitsForServerConfirmationAfterAnAcceptedUse() {
        assertTrue(WaterBucketRecoveryRules.placementRequestAccepted(InteractionResult.SUCCESS));
        assertTrue(WaterBucketRecoveryRules.placementRequestAccepted(InteractionResult.CONSUME));
        assertFalse(WaterBucketRecoveryRules.placementRequestAccepted(InteractionResult.PASS));
        assertFalse(WaterBucketRecoveryRules.placementRequestAccepted(InteractionResult.FAIL));
        assertFalse(WaterBucketRecoveryRules.placementRequestAccepted(null));
    }

    @Test
    void pickupRequiresBothSourceRemovalAndReturnedWaterBucket() {
        assertTrue(WaterBucketRecoveryRules.pickupConfirmed(false, 1));
        assertFalse(WaterBucketRecoveryRules.pickupConfirmed(true, 1));
        assertFalse(WaterBucketRecoveryRules.pickupConfirmed(false, 0));
    }

    @Test
    void placementRequiresARealCenteredDownwardFall() {
        assertTrue(WaterBucketRecoveryRules.mayPlaceWater(
                false, 62.5, 64, -0.2, 0.1, 12.0, 20.25, 2));
        assertFalse(WaterBucketRecoveryRules.mayPlaceWater(
                true, 62.5, 64, -0.2, 0.1, 12.0, 20.25, 2));
        assertFalse(WaterBucketRecoveryRules.mayPlaceWater(
                false, 64.0, 64, -0.2, 0.1, 12.0, 20.25, 2));
        assertFalse(WaterBucketRecoveryRules.mayPlaceWater(
                false, 62.5, 64, 0.02, 0.1, 12.0, 20.25, 2));
        assertFalse(WaterBucketRecoveryRules.mayPlaceWater(
                false, 62.5, 64, -0.2, 1.0, 12.0, 20.25, 2));
    }

    @Test
    void pickupWaitsForStableGroundAtTheLanding() {
        assertTrue(WaterBucketRecoveryRules.mayPickupWater(true, 62.0, 62, 0.2));
        assertFalse(WaterBucketRecoveryRules.mayPickupWater(false, 62.0, 62, 0.2));
        assertFalse(WaterBucketRecoveryRules.mayPickupWater(true, 63.0, 62, 0.2));
        assertFalse(WaterBucketRecoveryRules.mayPickupWater(true, 62.0, 62, 1.2));
    }
}
