package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExcavationPlannerRulesTest {
    @Test
    void dryAirIsCompleteWithoutAnyWorldAction() {
        ExcavationPlanner.CellKind kind = ExcavationPlanner.classifySnapshot(true, true, false, false);
        assertEquals(ExcavationPlanner.CellKind.CLEAR_AIR, kind);
        assertEquals(ExcavationPlanner.Operation.NONE, ExcavationPlanner.operationFor(kind));
        assertFalse(ExcavationPlanner.requiresWorldAction(kind));
    }

    @Test
    void fluidAndSolidRemainExplicitActions() {
        assertTrue(ExcavationPlanner.requiresWorldAction(
                ExcavationPlanner.classifySnapshot(true, false, false, true)));
        assertTrue(ExcavationPlanner.requiresWorldAction(
                ExcavationPlanner.classifySnapshot(true, false, false, false)));
    }

    @Test
    void unloadedAndBedrockNeverCreatePlacementTasks() {
        assertFalse(ExcavationPlanner.requiresWorldAction(
                ExcavationPlanner.classifySnapshot(false, false, false, false)));
        assertFalse(ExcavationPlanner.requiresWorldAction(
                ExcavationPlanner.classifySnapshot(true, false, true, false)));
    }

    @Test
    void aLargeDryCaveStillProducesZeroWorldActions() {
        long actions = java.util.stream.IntStream.range(0, 50_000)
                .mapToObj(ignored -> ExcavationPlanner.classifySnapshot(true, true, false, false))
                .map(ExcavationPlanner::operationFor)
                .filter(operation -> operation != ExcavationPlanner.Operation.NONE)
                .count();
        assertEquals(0, actions);
    }
}
