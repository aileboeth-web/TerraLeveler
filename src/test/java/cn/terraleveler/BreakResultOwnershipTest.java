package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakResultOwnershipTest {
    private static final BlockPos TARGET = new BlockPos(4, 70, -3);

    @Test
    void acceptsUnchangedBreakOwner() {
        assertTrue(ConstructionController.ownsBreakResult(
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.EXCAVATION,
                TARGET,
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.EXCAVATION,
                TARGET.immutable()));
    }

    @Test
    void acceptsUnchangedSpecializedBreakWithoutPurposeClaim() {
        assertTrue(ConstructionController.ownsBreakResult(
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.NONE,
                TARGET,
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.NONE,
                null));
    }

    @Test
    void rejectsResultAfterEmergencyStopClearsOwner() {
        assertFalse(ConstructionController.ownsBreakResult(
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.EXCAVATION,
                TARGET,
                ConstructionController.State.IDLE,
                ConstructionController.BreakPurpose.NONE,
                null));
    }

    @Test
    void rejectsResultAfterAnotherBreakReplacesTarget() {
        assertFalse(ConstructionController.ownsBreakResult(
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.EXCAVATION,
                TARGET,
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.EXCAVATION,
                TARGET.east()));
    }

    @Test
    void rejectsSpecializedBreakAfterStateChanges() {
        assertFalse(ConstructionController.ownsBreakResult(
                ConstructionController.State.EXCAVATING,
                ConstructionController.BreakPurpose.NONE,
                TARGET,
                ConstructionController.State.IDLE,
                ConstructionController.BreakPurpose.NONE,
                null));
    }
}
