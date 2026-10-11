package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.terraleveler.SafeRoutePlanner.CellKind.OPEN;
import static cn.terraleveler.SafeRoutePlanner.CellKind.SOLID;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.FOUND;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.SEARCH_LIMIT;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.BRIDGE_OFF_BOUNDARY;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.BRIDGE_REQUIRED;
import static cn.terraleveler.SafeRoutePlanner.StepType.BRIDGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlattenRouteBoundaryTest {
    @Test
    void flattenBridgesWorkInsideTheOwnedSelection() {
        FlattenPlan area = flatten(0, 8, 0, 4);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(2, 1, 2);
        BlockPos goal = new BlockPos(5, 1, 2);
        environment.addStand(start);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(
                environment, start, List.of(goal), area,
                SafeRoutePlanner.RoutePolicy.flatteningFloorWithBridges());

        assertEquals(FOUND, result.status());
        assertEquals(List.of(BRIDGE, BRIDGE, BRIDGE),
                result.steps().stream().map(SafeRoutePlanner.RouteStep::type).toList());
        assertTrue(result.steps().stream()
                .allMatch(step -> area.permitsScaffoldAt(
                        step.position().getX(), step.position().getZ())));
    }

    @Test
    void flattenBridgesCannotPassTheOwnedSelectionPerimeter() {
        FlattenPlan area = flatten(0, 8, 0, 4);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos boundary = new BlockPos(8, 1, 2);
        BlockPos permittedOutside = new BlockPos(9, 1, 2);
        BlockPos forbiddenOutside = new BlockPos(10, 1, 2);
        environment.addStand(boundary);

        assertEquals(BRIDGE_REQUIRED,
                SafeRoutePlanner.validateStep(environment, boundary,
                        new SafeRoutePlanner.RouteStep(permittedOutside, BRIDGE), area,
                        SafeRoutePlanner.RoutePolicy.flatteningFloorWithBridges()).status());
        environment.addStand(permittedOutside);
        assertEquals(BRIDGE_OFF_BOUNDARY,
                SafeRoutePlanner.validateStep(environment, permittedOutside,
                        new SafeRoutePlanner.RouteStep(forbiddenOutside, BRIDGE), area,
                        SafeRoutePlanner.RoutePolicy.flatteningFloorWithBridges()).status());
    }

    @Test
    void visitLimitDoesNotExecuteASectionThatOnlyMovesAwayFromTheGoal() {
        FlattenPlan area = flatten(0, 10, 0, 0);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos wrongWay = new BlockPos(0, 1, 1);
        BlockPos goal = new BlockPos(10, 1, 0);
        environment.addStand(start).addStand(wrongWay).addStand(goal);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRouteWithinVisitLimit(
                environment, start, List.of(goal), area,
                SafeRoutePlanner.RoutePolicy.flatteningFloor(), 1);

        assertEquals(SEARCH_LIMIT, result.status());
        assertTrue(result.steps().isEmpty());
    }

    private static FlattenPlan flatten(int minX, int maxX, int minZ, int maxZ) {
        return new FlattenPlan(new BlockPos(minX, 1, minZ), new BlockPos(maxX, 1, maxZ),
                0, 1, List.of(), List.of(), 0);
    }

    private static final class FakeEnvironment implements SafeRoutePlanner.RouteEnvironment {
        private final Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();

        FakeEnvironment addStand(BlockPos feet) {
            cells.put(feet.below().immutable(), SOLID);
            return this;
        }

        @Override
        public SafeRoutePlanner.CellKind cell(BlockPos position) {
            return cells.getOrDefault(position, OPEN);
        }
    }
}
