package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static cn.terraleveler.FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
import static cn.terraleveler.FinalScaffoldPillarPlanner.CellKind.SOLID;
import static cn.terraleveler.FinalScaffoldPillarPlanner.Status.INSUFFICIENT_MATERIAL;
import static cn.terraleveler.FinalScaffoldPillarPlanner.Status.NO_CLEAR_COLUMN;
import static cn.terraleveler.FinalScaffoldPillarPlanner.Status.NOT_FINAL_SINGLE_SCAFFOLD;
import static cn.terraleveler.FinalScaffoldPillarPlanner.Status.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalScaffoldPillarPlannerTest {
    @Test
    void ordinaryHighAccessDoesNotRequireFinalCleanupState() {
        BlockPos target = new BlockPos(0, 20, 0);
        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.planAccess(
                target, new BlockPos(0, 1, 3), 64, 48, -64,
                position -> position.equals(new BlockPos(1, 0, 0)) ? SOLID : OPEN_DRY);

        assertEquals(READY, result.status());
        assertEquals(new BlockPos(1, 1, 0), result.candidates().getFirst().baseFeet());
        assertEquals(18, result.candidates().getFirst().requiredBlocks());
    }

    @Test
    void plansTheTwentyBlockHighFieldFailureFromTheDiagnosticLog() {
        BlockPos target = new BlockPos(-3716, 82, -10013);
        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.planAccess(
                target, new BlockPos(-3712, 62, -10006), 64, 48, -64,
                position -> position.equals(new BlockPos(-3715, 61, -10013)) ? SOLID : OPEN_DRY);

        assertEquals(READY, result.status());
        assertEquals(new BlockPos(-3715, 62, -10013), result.candidates().getFirst().baseFeet());
        assertEquals(19, result.candidates().getFirst().requiredBlocks());
    }

    @Test
    void plansRecoverableVerticalColumnForThirtyBlockHighTarget() {
        BlockPos target = new BlockPos(0, 93, 0);
        Set<BlockPos> floors = Set.of(
            new BlockPos(0, 62, -1), new BlockPos(0, 62, 1),
            new BlockPos(-1, 62, 0), new BlockPos(1, 62, 0));

        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.plan(
            target, new BlockPos(10, 63, 10), true, 1, 64, 48, -64,
            pos -> floors.contains(pos) ? SOLID : OPEN_DRY);

        assertEquals(READY, result.status());
        assertEquals(29, result.candidates().getFirst().requiredBlocks());
        assertEquals(63, result.candidates().getFirst().placements().getFirst().getY());
        assertEquals(91, result.candidates().getFirst().placements().getLast().getY());
        assertEquals(92, result.candidates().getFirst().topFeet().getY());
    }

    @Test
    void rejectsBlockedShaft() {
        BlockPos target = new BlockPos(0, 93, 0);
        Set<BlockPos> solid = new HashSet<>();
        solid.add(new BlockPos(0, 92, -1));
        solid.add(new BlockPos(0, 92, 1));
        solid.add(new BlockPos(-1, 92, 0));
        solid.add(new BlockPos(1, 92, 0));

        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.plan(
            target, new BlockPos(0, 63, -1), true, 1, 64, 48, -64,
            pos -> solid.contains(pos) ? SOLID : OPEN_DRY);

        assertEquals(NO_CLEAR_COLUMN, result.status());
    }

    @Test
    void rejectsInsufficientMaterialBeforeClimbing() {
        BlockPos target = new BlockPos(0, 93, 0);
        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.plan(
            target, new BlockPos(10, 63, 10), true, 1, 28, 48, -64,
            pos -> pos.getY() == 62 ? SOLID : OPEN_DRY);

        assertEquals(INSUFFICIENT_MATERIAL, result.status());
        assertEquals(29, result.minimumRequiredBlocks());
    }

    @Test
    void doesNotActivateWhileMultipleScaffoldsRemain() {
        FinalScaffoldPillarPlanner.Result result = FinalScaffoldPillarPlanner.plan(
            new BlockPos(0, 93, 0), new BlockPos(0, 63, 0), true, 2, 64, 48, -64,
            pos -> pos.getY() == 62 ? SOLID : OPEN_DRY);

        assertEquals(NOT_FINAL_SINGLE_SCAFFOLD, result.status());
    }

    @Test
    void descendsByRemovingSupportsTopToBottom() {
        FinalScaffoldPillarPlanner.Candidate candidate = new FinalScaffoldPillarPlanner.Candidate(
            new BlockPos(1, 63, 0), new BlockPos(1, 66, 0),
            java.util.List.of(new BlockPos(1, 63, 0), new BlockPos(1, 64, 0), new BlockPos(1, 65, 0)));

        assertEquals(java.util.List.of(65, 64, 63), FinalScaffoldPillarPlanner.descentOrder(candidate).stream().map(BlockPos::getY).toList());
    }
}
