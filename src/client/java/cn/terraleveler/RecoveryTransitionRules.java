package cn.terraleveler;

/** Pure hysteresis rules for crossing repeatedly between water and dry air. */
final class RecoveryTransitionRules {
    enum WaterAction {
        IN_WATER,
        CONFIRM_DRY,
        RESUME_TASK,
        WAIT_UNSTABLE,
        PAUSE_UNSTABLE
    }

    record WaterResult(WaterAction action, int stableDryTicks, int unstableDryTicks) { }

    private RecoveryTransitionRules() { }

    static WaterResult waterStep(boolean inWater, boolean stableStand,
                                 int stableDryTicks, int unstableDryTicks,
                                 int requiredStableTicks, int maximumUnstableTicks) {
        if (inWater) return new WaterResult(WaterAction.IN_WATER, 0, 0);
        if (stableStand) {
            int nextStable = stableDryTicks + 1;
            WaterAction action = nextStable >= requiredStableTicks
                    ? WaterAction.RESUME_TASK : WaterAction.CONFIRM_DRY;
            return new WaterResult(action, nextStable, 0);
        }
        int nextUnstable = unstableDryTicks + 1;
        WaterAction action = nextUnstable > maximumUnstableTicks
                ? WaterAction.PAUSE_UNSTABLE : WaterAction.WAIT_UNSTABLE;
        return new WaterResult(action, 0, nextUnstable);
    }

    static boolean shouldContinueBankClimb(WaterAction action, boolean hasDryExit) {
        return hasDryExit && action == WaterAction.WAIT_UNSTABLE;
    }

    static boolean needsUpwardBankAssist(double playerY, int bankFeetY) {
        return playerY < bankFeetY + .05;
    }
}
