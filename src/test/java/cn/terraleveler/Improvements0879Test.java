package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Decaying leaves as route floor and tool box selection (0.8.78 test run). */
class Improvements0879Test {
    private static final TrenchPlan AREA = new TrenchPlan(new BlockPos(0, 1, 0), new BlockPos(4, 1, 1), 1, 1, 1, 0);

    @Test
    void onlyNaturalLeavesCutOffFromTheirLogsCountAsDecaying() {
        assertTrue(HazardousBlocks.isDecayingLeaves(false, 7));
        assertFalse(HazardousBlocks.isDecayingLeaves(false, 6));
        assertFalse(HazardousBlocks.isDecayingLeaves(true, 7));
    }

    @Test
    void decayingLeavesAreNeverPlannedAsFloor() {
        Grid grid = new Grid();
        for (int x = 0; x <= 4; x++) grid.stand(new BlockPos(x, 1, 0));
        grid.put(new BlockPos(2, 0, 0), SafeRoutePlanner.CellKind.UNSTABLE);
        BlockPos leafTop = new BlockPos(2, 1, 0);

        assertEquals(SafeRoutePlanner.StandStatus.BLOCKED, SafeRoutePlanner.inspectStandPosition(grid, leafTop));
        assertEquals(SafeRoutePlanner.PlanStatus.NO_ROUTE, SafeRoutePlanner.findRoute(grid, new BlockPos(0, 1, 0),
                List.of(new BlockPos(4, 1, 0)), AREA, SafeRoutePlanner.RoutePolicy.natural()).status());

        for (int x = 1; x <= 3; x++) grid.stand(new BlockPos(x, 1, 1));
        SafeRoutePlanner.PlanResult detour = SafeRoutePlanner.findRoute(grid, new BlockPos(0, 1, 0),
                List.of(new BlockPos(4, 1, 0)), AREA, SafeRoutePlanner.RoutePolicy.natural());
        assertEquals(SafeRoutePlanner.PlanStatus.FOUND, detour.status());
        assertTrue(detour.steps().stream().noneMatch(step -> step.position().equals(leafTop)));
    }

    @Test
    void playerAlreadyStandingOnDecayingLeavesCanStillWalkOff() {
        Grid grid = new Grid();
        grid.stand(new BlockPos(1, 1, 0));
        grid.put(new BlockPos(0, 0, 0), SafeRoutePlanner.CellKind.UNSTABLE);

        assertTrue(SafeRoutePlanner.standableOrigin(grid, new BlockPos(0, 1, 0)));
        assertEquals(SafeRoutePlanner.PlanStatus.FOUND, SafeRoutePlanner.findRoute(grid, new BlockPos(0, 1, 0),
                List.of(new BlockPos(1, 1, 0)), AREA, SafeRoutePlanner.RoutePolicy.natural()).status());
        // Leaves inside the body space still block like a solid block.
        grid.put(new BlockPos(1, 2, 0), SafeRoutePlanner.CellKind.UNSTABLE);
        assertEquals(SafeRoutePlanner.StandStatus.BLOCKED,
                SafeRoutePlanner.inspectStandPosition(grid, new BlockPos(1, 1, 0)));
    }

    @Test
    void differentlyNamedBoxesHoldingTheSameToolUseTheFirstConfiguredName() {
        ToolBoxSelectionRules.BoxSelection selection = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(9, true, "铲子", true),
                new ToolBoxSelectionRules.BoxCandidate(10, true, "镐子", true)), List.of("镐子", "铲子"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.FOUND, selection.status());
        assertEquals(10, selection.inventorySlot());

        ToolBoxSelectionRules.BoxSelection duplicate = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(9, true, "镐子", false),
                new ToolBoxSelectionRules.BoxCandidate(10, true, "镐子", true)), List.of("镐子", "铲子"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.AMBIGUOUS, duplicate.status());
    }

    private static final class Grid implements SafeRoutePlanner.RouteEnvironment {
        private final Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();

        void stand(BlockPos feet) {
            put(feet.below(), SafeRoutePlanner.CellKind.SOLID);
        }

        void put(BlockPos pos, SafeRoutePlanner.CellKind kind) {
            cells.put(pos.immutable(), kind);
        }

        @Override
        public SafeRoutePlanner.CellKind cell(BlockPos position) {
            return cells.getOrDefault(position, SafeRoutePlanner.CellKind.OPEN);
        }
    }
}
