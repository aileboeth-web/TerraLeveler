package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static cn.terraleveler.ConstructionSwitchRules.Action.CANCEL_SCAN;
import static cn.terraleveler.ConstructionSwitchRules.Action.NEXT_ISLAND_REGION;
import static cn.terraleveler.ConstructionSwitchRules.Action.START;
import static cn.terraleveler.ConstructionSwitchRules.Action.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class Improvements0902Test {
    @Test
    void pressingWhileIdleStarts() {
        assertEquals(START, ConstructionSwitchRules.action(false, false, false));
    }

    @Test
    void pressingWhileRunningOrPausedStopsInsteadOfRestarting() {
        assertEquals(STOP, ConstructionSwitchRules.action(true, false, false));
        assertEquals(STOP, ConstructionSwitchRules.action(true, true, true));
    }

    @Test
    void pressingDuringAnIslandScanCancelsIt() {
        assertEquals(CANCEL_SCAN, ConstructionSwitchRules.action(false, true, false));
    }

    @Test
    void pressingBetweenIslandRegionsContinuesWithTheNextRegion() {
        assertEquals(NEXT_ISLAND_REGION, ConstructionSwitchRules.action(false, false, true));
    }
}
