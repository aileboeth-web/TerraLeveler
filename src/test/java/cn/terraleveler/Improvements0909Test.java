package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0909Test {
    @Test
    void onlyAnimalsThatNobodyMarkedAsTheirsMayBeClearedFromAPlacementCell() {
        assertTrue(DefensiveCombat.clearableAnimal(false, false, false), "wild cow, wild horse");
        assertFalse(DefensiveCombat.clearableAnimal(true, false, false), "tamed pet or horse");
        assertFalse(DefensiveCombat.clearableAnimal(false, true, false), "named");
        assertFalse(DefensiveCombat.clearableAnimal(false, false, true), "leashed");
    }

    @Test
    void oneBoxOpeningTakesUpToFourStacksButNoMoreThanTheFillStillNeeds() {
        assertEquals(256, ConstructionController.fillExtractionAmount(20, 5000, 64));
        assertEquals(80, ConstructionController.fillExtractionAmount(20, 100, 64));
        assertEquals(1, ConstructionController.fillExtractionAmount(30, 20, 64));
    }

    @Test
    void stacksTakenOutForTheFillAreNotStoredStraightBack() {
        // Allowance 64, plenty of fill left: up to five stacks above the allowance stay loose.
        assertEquals(64 + 320, ConstructionController.overflowStoreThreshold(64, 5000, true));
        assertEquals(64 + 100, ConstructionController.overflowStoreThreshold(64, 100, true));
        // While still digging, the old rule (one extra stack) applies.
        assertEquals(ConstructionController.overflowStoreThreshold(64, 5000),
                ConstructionController.overflowStoreThreshold(64, 5000, false));
    }

    @Test
    void newInstallsWriteTheDiagnosticLogSoPlayersCanSendIt() {
        assertTrue(new TerraLevelerConfig().diagnosticsEnabled);
        assertTrue(new TerraLevelerConfig().clearBlockingAnimals);
    }
}
