package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A target hanging over open air goes straight to a pillar, without a full route search first (0.8.88 test run). */
class Improvements0889Test {
    @Test
    void anOldBridgeBlockOverOpenAirIsFloating() {
        BlockPos target = new BlockPos(-3542, 107, -10009);
        BlockPos start = new BlockPos(-3534, 62, -10013);
        Function<BlockPos, FinalScaffoldPillarPlanner.CellKind> world = pos -> pos.getY() <= 61 || pos.equals(target)
                ? FinalScaffoldPillarPlanner.CellKind.SOLID : FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
        FinalScaffoldPillarPlanner.Result geometry = FinalScaffoldPillarPlanner.planAccess(target, start, Integer.MAX_VALUE, 64, -64, world);
        assertTrue(ClimbBudgetRules.floatingTarget(geometry.ready(), geometry.minimumRequiredBlocks()));
    }

    @Test
    void aBlockOnASlopeIsNotFloating() {
        // Terrain rises one block per column toward the target: beside it the ground is only 2 below.
        BlockPos target = new BlockPos(0, 80, 0);
        BlockPos start = new BlockPos(-20, 62, 0);
        Function<BlockPos, FinalScaffoldPillarPlanner.CellKind> world = pos -> pos.getY() <= 77 + Math.min(0, pos.getX())
                || pos.equals(target)
                ? FinalScaffoldPillarPlanner.CellKind.SOLID : FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
        FinalScaffoldPillarPlanner.Result geometry = FinalScaffoldPillarPlanner.planAccess(target, start, Integer.MAX_VALUE, 64, -64, world);
        assertFalse(ClimbBudgetRules.floatingTarget(geometry.ready(), geometry.minimumRequiredBlocks()));
        assertFalse(ClimbBudgetRules.floatingTarget(false, 40));
    }
}
