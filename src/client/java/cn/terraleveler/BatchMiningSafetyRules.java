package cn.terraleveler;

final class BatchMiningSafetyRules {
    private BatchMiningSafetyRules() { }

    static boolean allowsCandidate(boolean moving, int candidateY, int stationaryLayerY,
                                   boolean ownedScaffold, boolean routeSupport) {
        if (ownedScaffold || routeSupport) return false;
        return moving || candidateY == stationaryLayerY;
    }

    /**
     * Digging ahead of the queue from where the player stands (0.9.13): blocks at the floor the
     * player stands on or higher, never a pit below the floor.
     */
    static boolean allowsReachPromotion(int candidateY, int feetY) {
        return candidateY >= feetY - 1;
    }

    static boolean allowsFallingColumnPosition(boolean fallingBlockAbove) {
        // The top falling block is safe to remove. Never batch the supporting
        // block below another falling block; the next layer becomes eligible
        // only after the server confirms that the top layer is gone.
        return !fallingBlockAbove;
    }
}
