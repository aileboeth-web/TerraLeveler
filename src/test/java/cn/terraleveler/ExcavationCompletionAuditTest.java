package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExcavationCompletionAuditTest {
    @Test
    void everyResidualSolidIsRequeuedWithoutMaterialPreference() {
        BlockPos stone = new BlockPos(0, 64, 0);
        BlockPos coalOre = new BlockPos(1, 64, 0);
        BlockPos ironOre = new BlockPos(2, 64, 0);
        BlockPos clear = new BlockPos(3, 64, 0);
        Map<BlockPos, ExcavationPlanner.Operation> operations = Map.of(
                stone, ExcavationPlanner.Operation.BREAK,
                coalOre, ExcavationPlanner.Operation.BREAK,
                ironOre, ExcavationPlanner.Operation.BREAK,
                clear, ExcavationPlanner.Operation.NONE);

        ExcavationCompletionAudit.Result result = ExcavationCompletionAudit.scan(
                List.of(stone, coalOre, ironOre, clear), operations::get);

        assertTrue(result.ready());
        assertEquals(List.of(stone, coalOre, ironOre), result.residualTargets());
    }

    @Test
    void fluidIsResidualAndAnUnloadedCellPreventsCompletion() {
        BlockPos fluid = new BlockPos(0, 63, 0);
        BlockPos unloaded = new BlockPos(1, 63, 0);
        ExcavationCompletionAudit.Result result = ExcavationCompletionAudit.scan(
                List.of(fluid, unloaded), position -> position.equals(fluid)
                        ? ExcavationPlanner.Operation.CONTAIN_FLUID
                        : ExcavationPlanner.Operation.WAIT_FOR_CHUNK);

        assertFalse(result.ready());
        assertEquals(unloaded, result.unloadedTarget());
        assertEquals(List.of(fluid), result.residualTargets());
    }
}
