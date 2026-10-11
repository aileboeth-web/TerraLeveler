package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** High targets: a pillar before a scaffold stair, and a way back down from a stuck stair (0.8.85 test run). */
class Improvements0886Test {
    @Test
    void aPillarFromTheGroundReachesTheOldScaffoldBridge() {
        // Old scaffold bridge block 51 above the finished ground at Y=62; everything else open air.
        BlockPos target = new BlockPos(-3522, 114, -10005);
        BlockPos start = new BlockPos(-3510, 63, -10037);
        java.util.function.Function<BlockPos, FinalScaffoldPillarPlanner.CellKind> world = pos -> pos.getY() <= 62 || pos.equals(target)
                ? FinalScaffoldPillarPlanner.CellKind.SOLID : FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;

        assertEquals(FinalScaffoldPillarPlanner.Status.PILLAR_TOO_TALL,
                FinalScaffoldPillarPlanner.planAccess(target, start, Integer.MAX_VALUE, 48, -64, world).status());
        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.planAccess(target, start, Integer.MAX_VALUE, 64, -64, world);
        assertTrue(result.ready());
        assertEquals(63, result.candidates().getFirst().baseFeet().getY());
        assertEquals(50, result.candidates().getFirst().requiredBlocks());
    }

    @Test
    void onlyAStairTowardAHighTargetIsReplacedByAPillar() {
        assertTrue(ClimbBudgetRules.preferPillarAccess(51, 49));
        assertTrue(ClimbBudgetRules.preferPillarAccess(ClimbBudgetRules.PILLAR_FIRST_RISE, 1));
        assertFalse(ClimbBudgetRules.preferPillarAccess(ClimbBudgetRules.PILLAR_FIRST_RISE - 1, 20));
        assertFalse(ClimbBudgetRules.preferPillarAccess(51, 0));
    }

    @Test
    void aStrandedPlayerGetsGroundGoalsNearestFirst() {
        BlockPos start = new BlockPos(-3525, 87, -10008);
        // Finished ground at Y=62 everywhere, plus an unfinished 1-block step at x>=-3520 (ground 63).
        List<BlockPos> goals = ClimbBudgetRules.descentGoals(start, 62, feet ->
                feet.getY() == (feet.getX() >= -3520 ? 64 : 63));
        assertFalse(goals.isEmpty());
        assertEquals(new BlockPos(-3525, 63, -10008), goals.getFirst());
        assertTrue(goals.stream().allMatch(feet -> Math.abs(feet.getX() - start.getX()) + Math.abs(feet.getZ() - start.getZ())
                <= ClimbBudgetRules.DESCENT_RADIUS));
        assertTrue(goals.contains(new BlockPos(-3518, 64, -10008)));
        // Nothing to stand on below: no goals.
        assertTrue(ClimbBudgetRules.descentGoals(start, 62, feet -> false).isEmpty());
    }
}
