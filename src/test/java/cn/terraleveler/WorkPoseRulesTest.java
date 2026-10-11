package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkPoseRulesTest {
    @Test
    void aBreakGoalCanNeverStandOnTheBlockItWillBreak() {
        BlockPos target = new BlockPos(4, 8, 9);

        assertFalse(WorkPoseRules.breakAllowed(target.above(), target));
        assertTrue(WorkPoseRules.breakAllowed(new BlockPos(3, 9, 9), target));
    }

    @Test
    void reachUsesTheSameEyePositionGeometryAsGoalSelection() {
        BlockPos feet = new BlockPos(0, 0, 0);

        assertTrue(WorkPoseRules.canReach(feet, new BlockPos(4, 1, 0)));
        assertFalse(WorkPoseRules.canReach(feet, new BlockPos(5, 1, 0)));
    }

    @Test
    void placementGoalMustKeepThePredictedPlayerBodyOutOfTheTarget() {
        BlockPos target = new BlockPos(2, 3, 4);

        assertFalse(WorkPoseRules.placementAllowed(target, target, target.below()));
        assertTrue(WorkPoseRules.placementAllowed(new BlockPos(1, 3, 4), target, target.below()));
    }

    @Test
    void waterSurfacePoseCanReachTheBottomSupportOfATwoBlockSandColumn() {
        BlockPos feet = new BlockPos(-424, 56, -187);
        BlockPos target = new BlockPos(-425, 54, -187);
        BlockPos support = new BlockPos(-425, 53, -187);

        assertTrue(WorkPoseRules.placementAllowed(feet, target, support));
    }

    @Test
    void fluidPlacementScansTheReachableHighBankThreeBlocksAboveItsSupport() {
        BlockPos target = new BlockPos(-204, 62, -98);
        BlockPos support = new BlockPos(-204, 62, -97);
        BlockPos highBankFeet = new BlockPos(-203, 65, -97);

        assertEquals(65, WorkPoseRules.maximumScanFeetY(target, support, true));
        assertTrue(WorkPoseRules.placementAllowed(highBankFeet, target, support));
        assertEquals(64, WorkPoseRules.maximumScanFeetY(target, support, false));
    }

    @Test
    void fluidPlacementAllowsADrySameLayerBankButNeverAGoalBelowTheInterface() {
        assertFalse(WorkPoseRules.fluidDeckPose(new BlockPos(0, 54, 0), 55));
        assertTrue(WorkPoseRules.fluidDeckPose(new BlockPos(0, 55, 0), 55));
        assertTrue(WorkPoseRules.fluidDeckPose(new BlockPos(0, 56, 0), 55));
        assertTrue(WorkPoseRules.fluidDeckPose(new BlockPos(0, 57, 0), 55));
    }
}
