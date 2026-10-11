package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LightingPlacementRulesTest {
    @Test
    void lightingPlanKeepsTheFullThreeDimensionalSelection() {
        LightingPlan plan = new LightingPlan(new BlockPos(7, 20, -3), new BlockPos(4, 18, 1));
        assertEquals(4, plan.minX());
        assertEquals(7, plan.maxX());
        assertEquals(18, plan.minY());
        assertEquals(20, plan.maxY());
        assertEquals(-3, plan.minZ());
        assertEquals(1, plan.maxZ());
        assertEquals(60L, plan.volume());
        assertFalse(plan.isStrictInteriorXZ(5, 0));
        assertFalse(plan.permitsScaffoldAt(5, 0));
    }

    @Test
    void torchCandidateRequiresAirSurvivalSafeSupportAndLowBlockLight() {
        assertTrue(LightingPlacementRules.needsTorch(true, true, true, 0, 0));
        assertTrue(LightingPlacementRules.needsTorch(true, true, true, 3, 3));
        assertFalse(LightingPlacementRules.needsTorch(false, true, true, 0, 0));
        assertFalse(LightingPlacementRules.needsTorch(true, false, true, 0, 0));
        assertFalse(LightingPlacementRules.needsTorch(true, true, false, 0, 0));
        assertFalse(LightingPlacementRules.needsTorch(true, true, true, 1, 0));
    }

    @Test
    void clickedSelectionBlocksAreScannedAsTorchSupports() {
        assertEquals(new BlockPos(4, 19, -2),
                LightingPlacementRules.targetAboveScannedSupport(new BlockPos(4, 18, -2)));
    }

    @Test
    void workModeParsesTheIndependentLightingMode() {
        assertEquals(WorkMode.LIGHTING, WorkMode.parse("LIGHTING"));
    }
}
