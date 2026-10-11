package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.terraleveler.FlattenEscapePlanner.CellKind.BREAKABLE_SOLID;
import static cn.terraleveler.FlattenEscapePlanner.CellKind.OPEN;
import static cn.terraleveler.FlattenEscapePlanner.CellKind.PROTECTED_SOLID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenEscapePlannerTest {
    private static final FlattenPlan AREA = new FlattenPlan(
            new BlockPos(0, 77, 0), new BlockPos(10, 77, 10),
            77, 77, List.of(), List.of(), 0);

    @Test
    void digsFeetAndHeadAheadThenMovesTowardTask() {
        BlockPos start = new BlockPos(2, 72, 5);
        BlockPos task = new BlockPos(8, 72, 5);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(new BlockPos(3, 71, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 72, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 73, 5), BREAKABLE_SOLID);

        FlattenEscapePlanner.Step step = FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).orElseThrow();

        assertEquals(task, step.taskKey());
        assertEquals(new BlockPos(3, 72, 5), step.destination());
        assertEquals(List.of(new BlockPos(3, 72, 5), new BlockPos(3, 73, 5)), step.breakTargets());
    }

    @Test
    void neverTunnelsThroughAProtectedHeadBlock() {
        BlockPos start = new BlockPos(2, 72, 5);
        BlockPos task = new BlockPos(8, 72, 5);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(new BlockPos(3, 71, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 72, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 73, 5), PROTECTED_SOLID);

        assertTrue(FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).isEmpty());
    }

    @Test
    void refusesToBreakAboveTheFinalGroundLayer() {
        BlockPos start = new BlockPos(2, 77, 5);
        BlockPos task = new BlockPos(8, 77, 5);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(new BlockPos(3, 76, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 77, 5), BREAKABLE_SOLID);
        cells.put(new BlockPos(3, 78, 5), BREAKABLE_SOLID);
        cells.put(start.above(2), PROTECTED_SOLID);

        assertTrue(FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).isEmpty());
    }

    @Test
    void predictedAirMustFinishItsOwnedBreakBeforeTheHeadCellStarts() {
        assertTrue(FlattenEscapePlanner.mustFinishActiveBreak(
                OPEN, true, false));
        assertFalse(FlattenEscapePlanner.mustFinishActiveBreak(
                OPEN, false, false));
        assertFalse(FlattenEscapePlanner.mustFinishActiveBreak(
                OPEN, true, true));
    }

    @Test
    void selfBuriedPlayerBreaksOnlyTheFirstOverheadBlockBeforeReplanning() {
        BlockPos start = new BlockPos(3, 75, 4);
        BlockPos task = start;
        BlockPos firstCeiling = new BlockPos(3, 77, 4);
        BlockPos secondCeiling = new BlockPos(3, 78, 4);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(firstCeiling, BREAKABLE_SOLID);
        cells.put(secondCeiling, BREAKABLE_SOLID);

        FlattenEscapePlanner.Step first = FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).orElseThrow();

        assertEquals(start, first.destination());
        assertEquals(List.of(firstCeiling), first.breakTargets());

        cells.put(firstCeiling, OPEN);
        FlattenEscapePlanner.Step second = FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).orElseThrow();
        assertEquals(List.of(secondCeiling), second.breakTargets());
    }

    @Test
    void upwardEscapeNeverBreaksThroughProtectedCeiling() {
        BlockPos start = new BlockPos(3, 75, 4);
        BlockPos protectedCeiling = new BlockPos(3, 77, 4);

        assertTrue(FlattenEscapePlanner.nextStep(
                AREA, start, start, 77,
                pos -> pos.equals(protectedCeiling) ? PROTECTED_SOLID : OPEN).isEmpty());
    }

    @Test
    void carvesOneStepUpWhenAlreadyAtTargetGroundY() {
        BlockPos start = new BlockPos(3, 77, 4);
        BlockPos task = start;
        BlockPos eastFloor = new BlockPos(4, 77, 4);
        BlockPos destination = eastFloor.above();
        BlockPos currentClearance = start.above(2);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(eastFloor, BREAKABLE_SOLID);
        cells.put(currentClearance, BREAKABLE_SOLID);
        cells.put(destination, BREAKABLE_SOLID);
        cells.put(destination.above(), BREAKABLE_SOLID);

        FlattenEscapePlanner.Step step = FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).orElseThrow();

        assertEquals(destination, step.destination());
        assertEquals(List.of(currentClearance, destination, destination.above()), step.breakTargets());
    }

    @Test
    void stepUpKeepsItsFloorAndRejectsProtectedClearance() {
        BlockPos start = new BlockPos(3, 76, 4);
        BlockPos task = start;
        BlockPos eastFloor = new BlockPos(4, 76, 4);
        BlockPos protectedHead = new BlockPos(4, 78, 4);
        Map<BlockPos, FlattenEscapePlanner.CellKind> cells = new HashMap<>();
        cells.put(eastFloor, BREAKABLE_SOLID);
        cells.put(protectedHead, PROTECTED_SOLID);

        assertTrue(FlattenEscapePlanner.nextStep(
                AREA, start, task, 77, pos -> cells.getOrDefault(pos, OPEN)).isEmpty());
    }
}
