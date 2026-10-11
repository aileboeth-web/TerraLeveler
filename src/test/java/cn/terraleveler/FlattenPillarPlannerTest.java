package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.terraleveler.FlattenPillarPlanner.CellKind.OPEN_DRY;
import static cn.terraleveler.FlattenPillarPlanner.CellKind.SOLID;
import static cn.terraleveler.FlattenPillarPlanner.Status.MISSING_PLANNED_FILL;
import static cn.terraleveler.FlattenPillarPlanner.Status.READY;
import static cn.terraleveler.FlattenPillarPlanner.Status.SHAFT_BLOCKED;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlattenPillarPlannerTest {
    private static final FlattenPlan AREA = new FlattenPlan(
            new BlockPos(0, 77, 0), new BlockPos(8, 77, 8),
            77, 77, List.of(), List.of(), 0);

    @Test
    void surfaceOnlyPlanAllowsOneEscapeColumnBelowItsPlannedSurfaceCell() {
        BlockPos start = new BlockPos(3, 72, 4);
        Set<BlockPos> planned = Set.of(new BlockPos(3, 77, 4));

        FlattenPillarPlanner.Result result = FlattenPillarPlanner.plan(
                AREA, 77, start, planned::contains, ignored -> OPEN_DRY);

        assertEquals(READY, result.status());
        assertEquals(List.of(72, 73, 74, 75, 76, 77),
                result.placements().stream().map(BlockPos::getY).toList());
    }

    @Test
    void refusesAColumnWhoseFinalSurfaceCellIsNotInTheFillPlan() {
        BlockPos start = new BlockPos(3, 72, 4);
        Set<BlockPos> planned = new HashSet<>();
        for (int y = 72; y < 77; y++) planned.add(new BlockPos(3, y, 4));

        assertEquals(MISSING_PLANNED_FILL, FlattenPillarPlanner.plan(
                AREA, 77, start, planned::contains, ignored -> OPEN_DRY).status());
    }

    @Test
    void refusesToPillarIntoABlockedFinalHeadCell() {
        BlockPos start = new BlockPos(3, 76, 4);
        Set<BlockPos> planned = Set.of(new BlockPos(3, 76, 4), new BlockPos(3, 77, 4));

        assertEquals(SHAFT_BLOCKED, FlattenPillarPlanner.plan(
                AREA, 77, start, planned::contains,
                pos -> pos.getY() == 79 ? SOLID : OPEN_DRY).status());
    }
}
