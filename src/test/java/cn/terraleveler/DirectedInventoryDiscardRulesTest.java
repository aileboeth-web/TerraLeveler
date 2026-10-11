package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectedInventoryDiscardRulesTest {
    @Test
    void confirmedPickupSurvivesTheClientInventoryVisibilityRace() {
        assertTrue(ConstructionController.shouldRetainInvisibleUnexpectedPickup(false, 0, 100, 140));
        assertTrue(ConstructionController.shouldRetainInvisibleUnexpectedPickup(true, 3, 141, 140));
        assertFalse(ConstructionController.shouldRetainInvisibleUnexpectedPickup(false, 0, 140, 140));
        assertFalse(ConstructionController.shouldRetainInvisibleUnexpectedPickup(true, 0, 101, 140));
    }

    @Test
    void directedDiscardYawPointsAwayFromTheWorkAreaCenter() {
        assertEquals(-90.0F, ConstructionController.discardYawAwayFrom(10.0, 0.0, 0.0, 0.0, 0.0F), 0.001F);
        assertEquals(0.0F, ConstructionController.discardYawAwayFrom(0.0, 10.0, 0.0, 0.0, 90.0F), 0.001F);
        assertEquals(-90.0F, ConstructionController.discardYawAwayFrom(0.0, 0.0, 0.0, 0.0, 90.0F), 0.001F);
    }
}
