package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.terraleveler.SafeRoutePlanner.CellKind.FLUID;
import static cn.terraleveler.SafeRoutePlanner.CellKind.OPEN;
import static cn.terraleveler.SafeRoutePlanner.CellKind.SOLID;
import static cn.terraleveler.SafeRoutePlanner.CellKind.UNLOADED;
import static cn.terraleveler.SafeRoutePlanner.InteriorPolicy.EXIT_ONLY;
import static cn.terraleveler.SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.FOUND;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.NO_ROUTE;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.PARTIAL;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.BRIDGE_OFF_BOUNDARY;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.BRIDGE_REQUIRED;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.DESTINATION_BLOCKED;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.DESTINATION_FLUID;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.DESTINATION_MISSING_FLOOR;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.DROP_PATH_CHANGED;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.SCAFFOLD_SUPPORT_MISSING;
import static cn.terraleveler.SafeRoutePlanner.StepStatus.VALID;
import static cn.terraleveler.SafeRoutePlanner.StepType.BRIDGE;
import static cn.terraleveler.SafeRoutePlanner.StepType.SCAFFOLD_UP;
import static cn.terraleveler.SafeRoutePlanner.StepType.STEP_DOWN;
import static cn.terraleveler.SafeRoutePlanner.StepType.STEP_UP;
import static cn.terraleveler.SafeRoutePlanner.StepType.WALK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SafeRoutePlannerTest {
    private static final TrenchPlan WIDE_TRENCH = trench(0, 4, 0, 4);

    @Test
    void visitBudgetReturnsAnExecutableProgressSegmentInsteadOfNoRoute() {
        FakeEnvironment environment = new FakeEnvironment();
        for (int x = 0; x <= 100; x++) environment.addStand(new BlockPos(x, 1, 0));
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos goal = new BlockPos(100, 1, 0);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRouteWithinVisitLimit(
                environment, start, List.of(goal), trench(0, 100, 0, 0),
                SafeRoutePlanner.RoutePolicy.natural(), 80);

        assertEquals(PARTIAL, result.status());
        assertTrue(result.hasProgressRoute());
        assertEquals(64, result.steps().size());
        assertTrue(result.steps().getLast().position().getX() > start.getX());
        assertTrue(result.steps().getLast().position().getX() < goal.getX());
    }


    @Test
    void routeMayDetourBeyondTheOldEightBlockSearchMargin() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos goal = new BlockPos(20, 1, 0);
        for (int z = 0; z <= 9; z++) {
            environment.addStand(new BlockPos(0, 1, z));
            environment.addStand(new BlockPos(20, 1, z));
        }
        for (int x = 0; x <= 20; x++) {
            environment.addStand(new BlockPos(x, 1, 9));
        }

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(
                environment, start, List.of(goal), trench(0, 20, 0, 0),
                SafeRoutePlanner.RoutePolicy.natural());

        assertEquals(FOUND, result.status());
        assertTrue(result.steps().stream().anyMatch(step -> step.position().getZ() == 9));
    }

    @Test
    void unsafeRequestedStartIsNotSnappedToNearbySafeCell() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos unsafeActualStart = new BlockPos(0, 2, 0);
        BlockPos nearbySafeGoal = new BlockPos(1, 2, 0);
        environment.addStand(nearbySafeGoal);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, unsafeActualStart,
                List.of(nearbySafeGoal), WIDE_TRENCH,
                new SafeRoutePlanner.RoutePolicy(false, EXIT_OR_TERMINAL_GOAL));

        assertEquals(START_REQUIRES_RECOVERY, result.status());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void normalRoutesDoNotDropThreeBlocksIntoAPit() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 4, 0);
        BlockPos landing = new BlockPos(1, 1, 0);
        environment.addStand(start).addStand(landing);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start, List.of(landing),
                trench(0, 1, 0, 0), SafeRoutePlanner.RoutePolicy.natural());

        assertEquals(NO_ROUTE, result.status());
    }

    @Test
    void lastScaffoldApproachCanUseARecordedNonlethalDrop() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 5, 0);
        BlockPos landing = new BlockPos(1, 1, 0);
        environment.addStand(start).addStand(landing);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(
                environment, start, List.of(landing), trench(0, 1, 0, 0),
                SafeRoutePlanner.RoutePolicy.flatteningFloorWithMaximumDrop(22));

        assertEquals(FOUND, result.status());
        assertEquals(STEP_DOWN, result.steps().getFirst().type());
        assertEquals(landing, result.steps().getFirst().position());
        assertEquals(VALID, validate(environment, start, result.steps().getFirst(),
                trench(0, 1, 0, 0),
                SafeRoutePlanner.RoutePolicy.flatteningFloorWithMaximumDrop(22)).status());
    }

    @Test
    void minimumWorkDeckUsesBridgesInsteadOfDroppingIntoBedrockPocket() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 4, 0);
        BlockPos lowPocket = new BlockPos(1, 1, 0);
        BlockPos goal = new BlockPos(2, 4, 0);
        environment.addStand(start).addStand(lowPocket);

        SafeRoutePlanner.RoutePolicy deck =
                SafeRoutePlanner.RoutePolicy.withBoundaryBridgesAtOrAbove(4);
        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start, List.of(goal),
                trench(0, 2, 0, 0), deck);

        assertEquals(FOUND, result.status());
        assertEquals(List.of(BRIDGE, BRIDGE),
                result.steps().stream().map(SafeRoutePlanner.RouteStep::type).toList());
        assertTrue(result.steps().stream().allMatch(step -> step.position().getY() >= 4));
    }


    @Test
    void stepDownStopsWhenAnIntermediateStandCellIsBlocked() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 4, 0);
        BlockPos landing = new BlockPos(1, 1, 0);
        environment.addStand(start).addStand(landing).set(new BlockPos(1, 3, 0), SOLID);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start, List.of(landing),
                trench(0, 1, 0, 0), SafeRoutePlanner.RoutePolicy.natural());

        assertEquals(NO_ROUTE, result.status());
    }


    @Test
    void everyStepKindCanBeRevalidatedAgainstChangedGeometry() {
        SafeRoutePlanner.RoutePolicy natural = new SafeRoutePlanner.RoutePolicy(
                false, EXIT_OR_TERMINAL_GOAL, Integer.MIN_VALUE, 3);

        FakeEnvironment walkWorld = new FakeEnvironment();
        BlockPos walkFrom = new BlockPos(0, 1, 0);
        BlockPos walkTo = new BlockPos(1, 1, 0);
        walkWorld.addStand(walkFrom).addStand(walkTo);
        assertEquals(VALID, validate(walkWorld, walkFrom, new SafeRoutePlanner.RouteStep(walkTo, WALK),
                trench(0, 1, 0, 0), natural).status());
        walkWorld.set(walkTo.below(), OPEN);
        assertEquals(DESTINATION_MISSING_FLOOR,
                validate(walkWorld, walkFrom, new SafeRoutePlanner.RouteStep(walkTo, WALK),
                        trench(0, 1, 0, 0), natural).status());

        FakeEnvironment upWorld = new FakeEnvironment();
        BlockPos upFrom = new BlockPos(0, 1, 0);
        BlockPos upTo = new BlockPos(1, 2, 0);
        upWorld.addStand(upFrom).addStand(upTo);
        assertEquals(VALID, validate(upWorld, upFrom, new SafeRoutePlanner.RouteStep(upTo, STEP_UP),
                trench(0, 1, 0, 0), natural).status());
        upWorld.set(upTo.above(), SOLID);
        assertEquals(DESTINATION_BLOCKED,
                validate(upWorld, upFrom, new SafeRoutePlanner.RouteStep(upTo, STEP_UP),
                        trench(0, 1, 0, 0), natural).status());

        FakeEnvironment downWorld = new FakeEnvironment();
        BlockPos downFrom = new BlockPos(0, 4, 0);
        BlockPos downTo = new BlockPos(1, 1, 0);
        downWorld.addStand(downFrom).addStand(downTo);
        assertEquals(VALID, validate(downWorld, downFrom, new SafeRoutePlanner.RouteStep(downTo, STEP_DOWN),
                trench(0, 1, 0, 0), natural).status());
        downWorld.set(new BlockPos(1, 2, 0), SOLID);
        assertEquals(DROP_PATH_CHANGED,
                validate(downWorld, downFrom, new SafeRoutePlanner.RouteStep(downTo, STEP_DOWN),
                        trench(0, 1, 0, 0), natural).status());

        FakeEnvironment bridgeWorld = new FakeEnvironment();
        BlockPos bridgeFrom = new BlockPos(-1, 1, 0);
        BlockPos bridgeTo = new BlockPos(0, 1, 0);
        bridgeWorld.addStand(bridgeFrom);
        SafeRoutePlanner.RoutePolicy bridges = SafeRoutePlanner.RoutePolicy.withBoundaryBridges();
        assertEquals(BRIDGE_REQUIRED,
                validate(bridgeWorld, bridgeFrom, new SafeRoutePlanner.RouteStep(bridgeTo, BRIDGE),
                        WIDE_TRENCH, bridges).status());
        bridgeWorld.set(bridgeTo.below(), SOLID);
        assertEquals(VALID, validate(bridgeWorld, bridgeFrom, new SafeRoutePlanner.RouteStep(bridgeTo, BRIDGE),
                WIDE_TRENCH, bridges).status());
        bridgeWorld.set(bridgeTo.below(), FLUID);
        assertEquals(DESTINATION_FLUID,
                validate(bridgeWorld, bridgeFrom, new SafeRoutePlanner.RouteStep(bridgeTo, BRIDGE),
                        WIDE_TRENCH, bridges).status());

        BlockPos offBoundaryFrom = new BlockPos(-3, 1, -1);
        BlockPos offBoundaryTo = new BlockPos(-2, 1, -1);
        bridgeWorld.set(bridgeTo.below(), OPEN).addStand(offBoundaryFrom);
        assertEquals(BRIDGE_OFF_BOUNDARY,
                validate(bridgeWorld, offBoundaryFrom, new SafeRoutePlanner.RouteStep(offBoundaryTo, BRIDGE),
                        WIDE_TRENCH, bridges).status());
    }

    @Test
    void exteriorServiceLaneCanBridgeBesideTrenchWithoutEnteringFinalExcavation() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-1, 1, 0);
        BlockPos outsideLane = new BlockPos(-1, 1, 1);
        environment.addStand(start);

        SafeRoutePlanner.RoutePolicy policy = SafeRoutePlanner.RoutePolicy.withBoundaryBridges();
        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start,
                List.of(outsideLane), WIDE_TRENCH, policy);

        assertEquals(FOUND, result.status());
        assertEquals(BRIDGE, result.steps().getFirst().type());
        assertTrue(SafeRoutePlanner.isScaffoldLane(WIDE_TRENCH,
                outsideLane.getX(), outsideLane.getZ()));
        assertTrue(!WIDE_TRENCH.isBoundaryXZ(outsideLane.getX(), outsideLane.getZ()));
    }

    @Test
    void exteriorServiceLaneCanBuildARealWallSupportedStaircaseUpward() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-1, 1, 0);
        BlockPos goal = new BlockPos(-1, 4, 3);
        environment.addStand(start);
        for (int z = 0; z <= 3; z++) {
            for (int y = 0; y <= 5; y++) {
                environment.set(new BlockPos(0, y, z), SOLID);
            }
        }

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start,
                List.of(goal), WIDE_TRENCH, SafeRoutePlanner.RoutePolicy.withBoundaryBridges());

        assertEquals(FOUND, result.status());
        assertEquals(List.of(SCAFFOLD_UP, SCAFFOLD_UP, SCAFFOLD_UP),
                result.steps().stream().map(SafeRoutePlanner.RouteStep::type).toList());
        assertEquals(goal, result.steps().getLast().position());
    }

    @Test
    void scaffoldAscentRequiresAnExistingWallSupportAndRevalidatesAfterPlacement() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos from = new BlockPos(-1, 1, 0);
        BlockPos to = new BlockPos(-1, 2, 1);
        BlockPos scaffold = to.below();
        environment.addStand(from);
        SafeRoutePlanner.RoutePolicy bridges = SafeRoutePlanner.RoutePolicy.withBoundaryBridges();
        SafeRoutePlanner.RouteStep step = new SafeRoutePlanner.RouteStep(to, SCAFFOLD_UP);

        assertEquals(SCAFFOLD_SUPPORT_MISSING,
                validate(environment, from, step, WIDE_TRENCH, bridges).status());

        environment.set(scaffold.relative(net.minecraft.core.Direction.EAST), SOLID);
        assertEquals(BRIDGE_REQUIRED,
                validate(environment, from, step, WIDE_TRENCH, bridges).status());

        environment.set(scaffold, SOLID);
        assertEquals(VALID, validate(environment, from, step, WIDE_TRENCH, bridges).status());
    }

    @Test
    void tallOpenCaveCanReachAWorkLayerByClimbingBesideTheSelectedWall() {
        TrenchPlan tallTrench = trench(0, 20, 0, 20);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-1, 1, 0);
        BlockPos goal = new BlockPos(-1, 16, 15);
        environment.addStand(start);
        for (int z = 0; z <= 15; z++) {
            for (int y = 0; y <= 18; y++) {
                environment.set(new BlockPos(0, y, z), SOLID);
            }
        }

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, start,
                List.of(goal), tallTrench, SafeRoutePlanner.RoutePolicy.withBoundaryBridges());

        assertEquals(FOUND, result.status());
        assertEquals(15, result.steps().size());
        assertTrue(result.steps().stream().allMatch(step -> step.type() == SCAFFOLD_UP));
    }

    @Test
    void anInteriorStartCanExitButAnOutsideRouteCannotUseInteriorAsShortcut() {
        FakeEnvironment escapeWorld = new FakeEnvironment();
        BlockPos startInside = new BlockPos(2, 1, 2);
        BlockPos goalOutside = new BlockPos(-1, 1, 2);
        for (int x = -1; x <= 2; x++) escapeWorld.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.PlanResult escape = SafeRoutePlanner.findRoute(escapeWorld, startInside,
                List.of(goalOutside), WIDE_TRENCH, new SafeRoutePlanner.RoutePolicy(false, EXIT_ONLY));

        assertEquals(FOUND, escape.status());
        assertEquals(List.of(new BlockPos(1, 1, 2), new BlockPos(0, 1, 2), goalOutside),
                escape.steps().stream().map(SafeRoutePlanner.RouteStep::position).toList());
        assertEquals(goalOutside, escape.steps().getLast().position());

        FakeEnvironment shortcutWorld = new FakeEnvironment();
        BlockPos westBoundary = new BlockPos(0, 1, 2);
        BlockPos eastOutside = new BlockPos(5, 1, 2);
        for (int x = 0; x <= 5; x++) shortcutWorld.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.PlanResult shortcut = SafeRoutePlanner.findRoute(shortcutWorld, westBoundary,
                List.of(eastOutside), WIDE_TRENCH,
                new SafeRoutePlanner.RoutePolicy(false, EXIT_OR_TERMINAL_GOAL));

        assertEquals(NO_ROUTE, shortcut.status());
    }

    @Test
    void explicitInteriorWorkGoalIsOnlyOneFinalStepAcrossTheBoundary() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos boundaryStart = new BlockPos(0, 1, 2);
        BlockPos interiorGoal = new BlockPos(1, 1, 2);
        for (int x = 0; x <= 3; x++) environment.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.RoutePolicy terminalPolicy =
                new SafeRoutePlanner.RoutePolicy(false, EXIT_OR_TERMINAL_GOAL);
        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, boundaryStart,
                List.of(interiorGoal), WIDE_TRENCH, terminalPolicy);

        assertEquals(FOUND, result.status());
        assertEquals(1, result.steps().size());
        SafeRoutePlanner.RouteStep finalStep = result.steps().getFirst();
        assertEquals(boundaryStart, finalStep.origin());
        assertEquals(interiorGoal, finalStep.position());
        assertEquals(interiorGoal, finalStep.interiorApproachGoal());
        assertEquals(VALID,
                SafeRoutePlanner.validateStep(environment, finalStep, WIDE_TRENCH, terminalPolicy).status());

        SafeRoutePlanner.PlanResult exitOnly = SafeRoutePlanner.findRoute(environment, boundaryStart,
                List.of(interiorGoal), WIDE_TRENCH,
                new SafeRoutePlanner.RoutePolicy(false, EXIT_ONLY));
        assertEquals(NO_ROUTE, exitOnly.status());
    }

    @Test
    void distantInteriorWorkPoseCannotTurnIntoACrossRectangleShortcut() {
        TrenchPlan longTrench = trench(0, 20, 0, 4);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos westBoundary = new BlockPos(0, 1, 2);
        BlockPos nearEastInterior = new BlockPos(19, 1, 2);
        for (int x = 0; x <= 19; x++) environment.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment, westBoundary,
                List.of(nearEastInterior), longTrench,
                new SafeRoutePlanner.RoutePolicy(false, EXIT_OR_TERMINAL_GOAL));

        assertEquals(NO_ROUTE, result.status());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void nextPlanningCallFromAnInteriorWorkPoseMustExitInsteadOfCrossing() {
        TrenchPlan longTrench = trench(0, 20, 0, 4);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos previousInteriorPose = new BlockPos(1, 1, 2);
        BlockPos eastOutsideGoal = new BlockPos(21, 1, 2);
        // x=0 is the only legal monotonic exit, but there is no exterior route
        // from it to the east goal. A planner that still wanders through the
        // interior would incorrectly follow x=1..21 and report FOUND.
        for (int x = 0; x <= 21; x++) environment.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment,
                previousInteriorPose, List.of(eastOutsideGoal), longTrench,
                new SafeRoutePlanner.RoutePolicy(false, EXIT_OR_TERMINAL_GOAL));

        assertEquals(NO_ROUTE, result.status());
        assertTrue(result.steps().isEmpty());
    }

    @Test
    void flatteningFloorCanTravelBetweenDistantInteriorWorkPoses() {
        TrenchPlan flatteningArea = trench(0, 8, 0, 4);
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos startInside = new BlockPos(2, 1, 2);
        BlockPos nextFillPose = new BlockPos(6, 1, 2);
        for (int x = 2; x <= 6; x++) environment.addStand(new BlockPos(x, 1, 2));

        SafeRoutePlanner.PlanResult trenchPolicy = SafeRoutePlanner.findRoute(environment,
                startInside, List.of(nextFillPose), flatteningArea,
                SafeRoutePlanner.RoutePolicy.natural());
        SafeRoutePlanner.PlanResult flattenPolicy = SafeRoutePlanner.findRoute(environment,
                startInside, List.of(nextFillPose), flatteningArea,
                SafeRoutePlanner.RoutePolicy.flatteningFloor());

        assertEquals(NO_ROUTE, trenchPolicy.status());
        assertEquals(FOUND, flattenPolicy.status());
        assertEquals(List.of(
                        new BlockPos(3, 1, 2), new BlockPos(4, 1, 2),
                        new BlockPos(5, 1, 2), nextFillPose),
                flattenPolicy.steps().stream().map(SafeRoutePlanner.RouteStep::position).toList());
        assertTrue(flattenPolicy.steps().stream()
                .allMatch(step -> step.interiorApproachGoal() == null));
        for (SafeRoutePlanner.RouteStep step : flattenPolicy.steps()) {
            assertEquals(VALID, SafeRoutePlanner.validateStep(environment, step,
                    flatteningArea, SafeRoutePlanner.RoutePolicy.flatteningFloor()).status());
        }
    }



    private static SafeRoutePlanner.StepValidation validate(FakeEnvironment environment, BlockPos from,
                                                             SafeRoutePlanner.RouteStep step, TrenchPlan trench,
                                                             SafeRoutePlanner.RoutePolicy policy) {
        SafeRoutePlanner.StepValidation validation =
                SafeRoutePlanner.validateStep(environment, from, step, trench, policy);
        assertNotNull(validation.destination());
        return validation;
    }

    private static TrenchPlan trench(int minX, int maxX, int minZ, int maxZ) {
        return new TrenchPlan(new BlockPos(minX, 1, minZ), new BlockPos(maxX, 1, maxZ),
                1, 1, 1, 0);
    }

    private static final class FakeEnvironment implements SafeRoutePlanner.RouteEnvironment {
        private final Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();

        FakeEnvironment addStand(BlockPos feet) {
            set(feet.below(), SOLID);
            return this;
        }

        FakeEnvironment set(BlockPos position, SafeRoutePlanner.CellKind kind) {
            if (kind == OPEN) cells.remove(position);
            else cells.put(position.immutable(), kind);
            return this;
        }

        @Override
        public SafeRoutePlanner.CellKind cell(BlockPos position) {
            return cells.getOrDefault(position, OPEN);
        }
    }
}
