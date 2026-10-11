package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InventoryPressure0871Test {
    @Test
    void freeSlotReserveDefinesInventoryPressure() {
        assertTrue(ConstructionController.inventoryUnderPressure(0));
        assertTrue(ConstructionController.inventoryUnderPressure(ConstructionController.INVENTORY_FREE_SLOT_RESERVE - 1));
        assertFalse(ConstructionController.inventoryUnderPressure(ConstructionController.INVENTORY_FREE_SLOT_RESERVE));
    }

    @Test
    void boxSupplyCoveringTheFillKeepsOnlyTheAllowanceLoose() {
        // 0.8.70 test run: allowance 127, ~870 dirt still to place, ~1700 dirt in the boxes.
        assertEquals(127, ConstructionController.configuredMaterialKeepLimit(127, 873, 1700, false));
    }

    @Test
    void demandTheBoxesCannotSupplyStaysLoose() {
        assertEquals(127 + 300, ConstructionController.configuredMaterialKeepLimit(127, 500, 200, false));
        assertEquals(127 + 500, ConstructionController.configuredMaterialKeepLimit(127, 500, 0, false));
    }

    @Test
    void nearlyFullInventoryFallsBackToThePlainAllowance() {
        assertEquals(127, ConstructionController.configuredMaterialKeepLimit(127, 500, 0, true));
    }

    @Test
    void nearbyPickupDetoursFollowTheConfiguredLimitUnlessSupplyIsShort() {
        assertEquals(64, ConstructionController.nearbyPickupLimit(64, 1000, false, false));
        assertEquals(1000, ConstructionController.nearbyPickupLimit(64, 1000, true, false));
        assertEquals(0, ConstructionController.nearbyPickupLimit(64, 1000, true, true));
    }

    @Test
    void largeExcessOrFullInventoryStoresAgainSoon() {
        assertFalse(ConstructionController.storeAgainSoon(150, 127, 10));
        assertTrue(ConstructionController.storeAgainSoon(256, 127, 10));
        assertTrue(ConstructionController.storeAgainSoon(150, 127, 1));
        assertFalse(ConstructionController.storeAgainSoon(120, 127, 0));
    }

    @Test
    void onlyQueuedUnprotectedPickupsMayBeDroppedFromTheCursor() {
        assertTrue(ConstructionController.mayDropCarriedPickup(false, true, 0, 0));
        assertFalse(ConstructionController.mayDropCarriedPickup(true, true, 0, 0));
        assertFalse(ConstructionController.mayDropCarriedPickup(false, false, 0, 0));
        // The carried stack also holds part of the item's start-of-job baseline.
        assertFalse(ConstructionController.mayDropCarriedPickup(false, true, 10, 32));
        assertTrue(ConstructionController.mayDropCarriedPickup(false, true, 32, 32));
    }

    @Test
    void throwFromTheMiddleOfANarrowStripDoesNotCountAsOutside() {
        // Work area x -6..2 (9 wide), long along z, as in the 0.8.70 test run.
        java.util.function.BiPredicate<Integer, Integer> strip = (x, z) -> x >= -6 && x <= 2 && z >= -475 && z <= -301;
        int full = ConstructionController.discardLandingFullScore();
        // Player in the middle column throwing towards +x (yaw -90): lands about x=1.5, still inside.
        assertTrue(ConstructionController.discardLandingOutsideScore(strip, -1.5, -400.5, -90.0F) < full);
        // Thrown along the strip (yaw 0 = +z) it lands inside as well.
        assertEquals(0, ConstructionController.discardLandingOutsideScore(strip, -1.5, -400.5, 0.0F));
    }

    @Test
    void throwFromTheStripEdgeLandsClearOfPickupReach() {
        java.util.function.BiPredicate<Integer, Integer> strip = (x, z) -> x >= -6 && x <= 2 && z >= -475 && z <= -301;
        int full = ConstructionController.discardLandingFullScore();
        // Standing on the east edge column x=2, throwing east (yaw -90 = +x): lands around x=6.
        assertEquals(full, ConstructionController.discardLandingOutsideScore(strip, 2.5, -400.5, -90.0F));
        // One column further in still clears the margin.
        assertEquals(full, ConstructionController.discardLandingOutsideScore(strip, 1.5, -400.5, -90.0F));
        // Two columns in, the landing neighbourhood touches the area.
        assertTrue(ConstructionController.discardLandingOutsideScore(strip, 0.5, -400.5, -90.0F) < full);
    }

    @Test
    void materialIsNotSwappedOverTheSelectionAppleOrTools() {
        // No empty hotbar slot; slot 8 holds the apple, 7 a pickaxe, 6 food.
        java.util.function.IntPredicate keep = slot -> slot == 8 || slot == 7;
        assertEquals(6, ConstructionController.materialHotbarDestination(slot -> false, slot -> false, keep));
        // An empty slot is still preferred.
        assertEquals(2, ConstructionController.materialHotbarDestination(slot -> slot == 2, slot -> false, keep));
        // Only kept slots left: the old rightmost choice applies.
        assertEquals(8, ConstructionController.materialHotbarDestination(slot -> false, slot -> slot < 7, keep));
        // The original two-argument rule is unchanged.
        assertEquals(8, ConstructionController.materialHotbarDestination(slot -> false, slot -> false));
    }

    @Test
    void oneApplePressIsHandledOnce() {
        long pos = 123456789L;
        assertTrue(SelectionInteractionRules.isRepeatedPress(pos, true, 1000L, pos, true, 1003L));
        assertTrue(SelectionInteractionRules.isRepeatedPress(pos, true, 1000L, pos, true, 1400L));
        assertFalse(SelectionInteractionRules.isRepeatedPress(pos, true, 1000L, pos, true, 1401L));
        assertFalse(SelectionInteractionRules.isRepeatedPress(pos, true, 1000L, pos + 1, true, 1003L));
        // Right click on the block just left-clicked is a new press (corner two).
        assertFalse(SelectionInteractionRules.isRepeatedPress(pos, true, 1000L, pos, false, 1003L));
        assertFalse(SelectionInteractionRules.isRepeatedPress(Long.MIN_VALUE, false, 0L, pos, false, 100L));
    }
}
