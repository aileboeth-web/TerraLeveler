package cn.terraleveler;

/** What one press of the construction switch does (0.9.02: start ↔ stop instead of restart). */
final class ConstructionSwitchRules {
    enum Action {
        /** A job is running or paused: stop it. */
        STOP,
        /** Nothing runs yet but an area/island scan is in progress: cancel it. */
        CANCEL_SCAN,
        /** An island job waits between regions and the player is on the island: start the next region. */
        NEXT_ISLAND_REGION,
        /** Nothing runs: plan and start (an island may first show its preview). */
        START
    }

    private ConstructionSwitchRules() { }

    static Action action(boolean jobRunningOrPaused, boolean scanPending, boolean islandRegionAwaitingHere) {
        if (jobRunningOrPaused) return Action.STOP;
        if (scanPending) return Action.CANCEL_SCAN;
        if (islandRegionAwaitingHere) return Action.NEXT_ISLAND_REGION;
        return Action.START;
    }
}
