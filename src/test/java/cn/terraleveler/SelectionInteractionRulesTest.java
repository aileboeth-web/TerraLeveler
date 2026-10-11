package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectionInteractionRulesTest {
    @Test
    void appleSelectionIsAvailableWhileIdlePausedOrComplete() {
        assertTrue(SelectionInteractionRules.mayChangeSelection(
                true, true, true, ConstructionController.State.IDLE));
        assertTrue(SelectionInteractionRules.mayChangeSelection(
                true, true, true, ConstructionController.State.PAUSED));
        assertTrue(SelectionInteractionRules.mayChangeSelection(
                true, true, true, ConstructionController.State.COMPLETE));
        assertFalse(SelectionInteractionRules.mayChangeSelection(
                true, true, true, ConstructionController.State.EXCAVATING));
        assertFalse(SelectionInteractionRules.mayChangeSelection(
                true, true, true, ConstructionController.State.EATING));
        assertFalse(SelectionInteractionRules.mayChangeSelection(
                true, false, true, ConstructionController.State.IDLE));
        assertFalse(SelectionInteractionRules.mayChangeSelection(
                true, true, false, ConstructionController.State.IDLE));
        assertFalse(SelectionInteractionRules.mayChangeSelection(
                false, true, true, ConstructionController.State.IDLE));
    }
}
