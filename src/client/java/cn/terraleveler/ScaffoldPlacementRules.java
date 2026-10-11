package cn.terraleveler;

final class ScaffoldPlacementRules {
    private ScaffoldPlacementRules() { }

    static boolean shouldClearTarget(boolean air, boolean fluidEmpty, boolean collisionEmpty) {
        return !air && fluidEmpty && collisionEmpty;
    }

    static boolean shouldContinueClearance(boolean targetNeedsClear, boolean breakTransactionActive) {
        return targetNeedsClear || breakTransactionActive;
    }
}
