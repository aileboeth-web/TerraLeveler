package cn.terraleveler;

import net.minecraft.world.InteractionResult;

final class WaterBucketRecoveryRules {
    private WaterBucketRecoveryRules() { }

    static boolean mayRunRollingOverflowRelease(boolean activeWaterBucketFall) {
        return !activeWaterBucketFall;
    }

    static boolean mayAdvanceRouteStep(boolean waterBucketStep, boolean activeWaterBucketTransaction) {
        return !waterBucketStep || !activeWaterBucketTransaction;
    }

    static boolean placementRequestAccepted(InteractionResult result) {
        return result != null && result.consumesAction();
    }

    static boolean pickupConfirmed(boolean waterPresent, int waterBucketCount) {
        return !waterPresent && waterBucketCount > 0;
    }

    static boolean mayPlaceWater(boolean onGround, double playerY, int originY,
                                 double verticalVelocity, double horizontalDistanceSquared,
                                 double reachSquared, double maximumReachSquared,
                                 int aimTicks) {
        return !onGround
                && playerY < originY - 0.25
                && verticalVelocity < -0.01
                && horizontalDistanceSquared <= 0.85 * 0.85
                && reachSquared <= maximumReachSquared
                && aimTicks >= 2;
    }

    static boolean mayPickupWater(boolean onGround, double playerY, int landingY,
                                  double horizontalDistanceSquared) {
        return onGround
                && Math.abs(playerY - landingY) <= 0.25
                && horizontalDistanceSquared <= 1.0;
    }
}
