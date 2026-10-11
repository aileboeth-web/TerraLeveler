package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTopologySimulatorTest {
    @Test
    void rejectsObservedRouteThatPlacesScaffoldInAnchorHeadCell() {
        BlockPos anchor = new BlockPos(-220, 71, -342);
        BlockPos firstStep = new BlockPos(-221, 72, -342);
        BlockPos scaffoldStep = new BlockPos(-220, 73, -342);
        BlockPos workPose = new BlockPos(-220, 74, -341);
        BlockPos task = new BlockPos(-219, 76, -339);
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = floors(anchor, firstStep);
        cells.put(new BlockPos(-219, 72, -342), SafeRoutePlanner.CellKind.SOLID);
        cells.put(new BlockPos(-219, 73, -341), SafeRoutePlanner.CellKind.SOLID);
        PathExecutionPlan plan = plan(task, List.of(
                step(anchor, firstStep, SafeRoutePlanner.StepType.STEP_UP),
                step(firstStep, scaffoldStep, SafeRoutePlanner.StepType.SCAFFOLD_UP),
                step(scaffoldStep, workPose, SafeRoutePlanner.StepType.SCAFFOLD_UP)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(cells), openArea(), anchor, List.of(), plan, returnPolicy(), true);

        assertEquals(RouteTopologySimulator.Status.PLACEMENT_OCCUPIES_RETURN_NODE, result.status());
        assertEquals(anchor.above(), result.conflict());
    }

    @Test
    void rejectsScaffoldThatWouldOccupyTheOriginalAnchor() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos middle = new BlockPos(1, 64, 0);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, middle, SafeRoutePlanner.StepType.WALK),
                step(middle, anchor.above(), SafeRoutePlanner.StepType.SCAFFOLD_UP)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor, middle)), openArea(), anchor, List.of(), plan, returnPolicy());

        assertEquals(RouteTopologySimulator.Status.PLACEMENT_OCCUPIES_RETURN_NODE, result.status());
        assertEquals(anchor, result.conflict());
    }

    @Test
    void rejectsScaffoldThatWouldOccupyAnExistingReturnWaypoint() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos returnWaypoint = new BlockPos(1, 64, 0);
        BlockPos origin = new BlockPos(2, 64, 0);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, returnWaypoint, SafeRoutePlanner.StepType.WALK),
                step(returnWaypoint, origin, SafeRoutePlanner.StepType.WALK),
                step(origin, returnWaypoint.above(), SafeRoutePlanner.StepType.SCAFFOLD_UP)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor, returnWaypoint, origin)), openArea(), anchor, List.of(), plan,
                returnPolicy());

        assertEquals(RouteTopologySimulator.Status.PLACEMENT_OCCUPIES_RETURN_NODE, result.status());
        assertEquals(returnWaypoint, result.conflict());
    }

    @Test
    void reportsAllPlacementsThatOccupyReturnNodesInOneCandidate() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos middle = new BlockPos(1, 64, 0);
        BlockPos firstHigh = anchor.above();
        BlockPos secondHigh = middle.above();
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, middle, SafeRoutePlanner.StepType.WALK),
                step(middle, firstHigh, SafeRoutePlanner.StepType.SCAFFOLD_UP),
                step(firstHigh, secondHigh, SafeRoutePlanner.StepType.BRIDGE)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor, middle)), openArea(), anchor, List.of(), plan, returnPolicy());

        assertEquals(RouteTopologySimulator.Status.PLACEMENT_OCCUPIES_RETURN_NODE, result.status());
        assertEquals(Set.of(anchor, middle), result.conflicts());
    }

    @Test
    void acceptsBridgeWhenItsSupportIsOnlyTheReturnFloor() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos bridge = new BlockPos(1, 64, 0);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, bridge, SafeRoutePlanner.StepType.BRIDGE)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor)), openArea(), anchor, List.of(), plan, returnPolicy());

        assertTrue(result.valid(), result::toString);
    }

    @Test
    void acceptsScaffoldDirectlyBelowItsReturnFeetCell() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(1, 65, 0);
        BlockPos wallSupport = new BlockPos(2, 64, 0);
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = floors(anchor);
        cells.put(wallSupport, SafeRoutePlanner.CellKind.SOLID);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, destination, SafeRoutePlanner.StepType.SCAFFOLD_UP)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(cells), openArea(), anchor, List.of(), plan, returnPolicy());

        assertTrue(result.valid());
    }

    @Test
    void acceptsReversibleRouteThatRevisitsAColumnAtAnotherHeight() {
        BlockPos anchor = new BlockPos(0, 62, 0);
        BlockPos repeatedLow = new BlockPos(1, 62, 0);
        BlockPos lowEast = new BlockPos(2, 62, 0);
        BlockPos firstRise = new BlockPos(3, 63, 0);
        BlockPos secondRise = new BlockPos(3, 64, 1);
        BlockPos thirdRise = new BlockPos(2, 65, 1);
        BlockPos highSouth = new BlockPos(1, 65, 1);
        BlockPos repeatedHigh = new BlockPos(1, 65, 0);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, repeatedLow, SafeRoutePlanner.StepType.WALK),
                step(repeatedLow, lowEast, SafeRoutePlanner.StepType.WALK),
                step(lowEast, firstRise, SafeRoutePlanner.StepType.STEP_UP),
                step(firstRise, secondRise, SafeRoutePlanner.StepType.STEP_UP),
                step(secondRise, thirdRise, SafeRoutePlanner.StepType.STEP_UP),
                step(thirdRise, highSouth, SafeRoutePlanner.StepType.WALK),
                step(highSouth, repeatedHigh, SafeRoutePlanner.StepType.WALK)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor, repeatedLow, lowEast, firstRise, secondRise,
                        thirdRise, highSouth, repeatedHigh)),
                openArea(), anchor, List.of(), plan, returnPolicy());

        assertTrue(result.valid(), result::toString);
    }

    @Test
    void identifiesScaffoldThatBlocksAReverseDropCorridor() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos highEast = new BlockPos(1, 65, 0);
        BlockPos highSouth = new BlockPos(1, 65, 1);
        BlockPos upperSouth = new BlockPos(0, 66, 1);
        BlockPos destination = new BlockPos(0, 67, 0);
        BlockPos blockingPlacement = destination.below();
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = floors(
                anchor, highEast, highSouth, upperSouth);
        cells.put(blockingPlacement.relative(net.minecraft.core.Direction.WEST),
                SafeRoutePlanner.CellKind.SOLID);
        PathExecutionPlan plan = plan(new BlockPos(9, 70, 0), List.of(
                step(anchor, highEast, SafeRoutePlanner.StepType.STEP_UP),
                step(highEast, highSouth, SafeRoutePlanner.StepType.WALK),
                step(highSouth, upperSouth, SafeRoutePlanner.StepType.STEP_UP),
                step(upperSouth, destination, SafeRoutePlanner.StepType.SCAFFOLD_UP)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(cells), openArea(), anchor, List.of(), plan, returnPolicy());

        assertEquals(RouteTopologySimulator.Status.PLACEMENT_CHANGES_RETURN_PATH, result.status());
        assertEquals(blockingPlacement, result.conflict());
        assertEquals(SafeRoutePlanner.StepStatus.DROP_PATH_CHANGED, result.stepStatus());
    }

    @Test
    void simulatesTaskRemovalAndReturnsBelowTheRemovedAnchorFloor() {
        BlockPos anchor = new BlockPos(-266, 66, -260);
        BlockPos task = anchor.below();
        BlockPos bridge = new BlockPos(-266, 66, -261);
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = floors(anchor);
        cells.put(anchor.below().below(), SafeRoutePlanner.CellKind.SOLID);
        PathExecutionPlan plan = plan(task, List.of(
                step(anchor, bridge, SafeRoutePlanner.StepType.BRIDGE)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(cells), openArea(), anchor, List.of(), plan, returnPolicy(), true);

        assertTrue(result.valid());
    }

    @Test
    void rejectsRemovedAnchorFloorWhenThereIsNoSafeLowerLanding() {
        BlockPos anchor = new BlockPos(-266, 66, -260);
        BlockPos task = anchor.below();
        BlockPos bridge = new BlockPos(-266, 66, -261);
        PathExecutionPlan plan = plan(task, List.of(
                step(anchor, bridge, SafeRoutePlanner.StepType.BRIDGE)));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validate(
                environment(floors(anchor)), openArea(), anchor, List.of(), plan,
                returnPolicy(), true);

        assertEquals(RouteTopologySimulator.Status.RETURN_INVALID, result.status());
        assertEquals(SafeRoutePlanner.StepStatus.DESTINATION_MISSING_FLOOR, result.stepStatus());
    }

    @Test
    void acceptsDirectRemovalThatPreservesAccumulatedReturnRoute() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos first = new BlockPos(1, 64, 0);
        BlockPos endpoint = new BlockPos(2, 64, 0);
        BlockPos task = new BlockPos(3, 65, 0);
        List<SafeRoutePlanner.RouteStep> committed = List.of(
                step(anchor, first, SafeRoutePlanner.StepType.WALK),
                step(first, endpoint, SafeRoutePlanner.StepType.WALK));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validateRemoval(
                environment(floors(anchor, first, endpoint, task)),
                openArea(), anchor, committed, task, returnPolicy());

        assertTrue(result.valid(), result::toString);
    }

    @Test
    void rejectsDirectRemovalOfAccumulatedReturnSupport() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos first = new BlockPos(1, 64, 0);
        BlockPos endpoint = new BlockPos(2, 64, 0);
        List<SafeRoutePlanner.RouteStep> committed = List.of(
                step(anchor, first, SafeRoutePlanner.StepType.WALK),
                step(first, endpoint, SafeRoutePlanner.StepType.WALK));

        RouteTopologySimulator.Result result = RouteTopologySimulator.validateRemoval(
                environment(floors(anchor, first, endpoint)),
                openArea(), anchor, committed, first.below(), returnPolicy());

        assertEquals(RouteTopologySimulator.Status.RETURN_INVALID, result.status());
        assertEquals(first, result.conflict());
        assertEquals(SafeRoutePlanner.StepStatus.DESTINATION_MISSING_FLOOR, result.stepStatus());
    }

    private static PathExecutionPlan plan(BlockPos task, List<SafeRoutePlanner.RouteStep> steps) {
        return new PathExecutionPlan(new NavigationGoal(task, List.of(task)), steps,
                SafeRoutePlanner.RoutePolicy.withBoundaryBridges(), false, false);
    }

    private static SafeRoutePlanner.RouteStep step(BlockPos origin, BlockPos destination,
                                                    SafeRoutePlanner.StepType type) {
        return new SafeRoutePlanner.RouteStep(origin, destination, type, null);
    }

    private static SafeRoutePlanner.RoutePolicy returnPolicy() {
        return new SafeRoutePlanner.RoutePolicy(false, SafeRoutePlanner.InteriorPolicy.ALLOW,
                Integer.MIN_VALUE, 1);
    }

    private static SafeRoutePlanner.RouteEnvironment environment(Map<BlockPos, SafeRoutePlanner.CellKind> cells) {
        return position -> cells.getOrDefault(position, SafeRoutePlanner.CellKind.OPEN);
    }

    private static Map<BlockPos, SafeRoutePlanner.CellKind> floors(BlockPos... feet) {
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();
        for (BlockPos position : feet) cells.put(position.below(), SafeRoutePlanner.CellKind.SOLID);
        return cells;
    }

    private static WorkArea openArea() {
        return new WorkArea() {
            @Override public int minX() { return -100; }
            @Override public int maxX() { return 100; }
            @Override public int minZ() { return -100; }
            @Override public int maxZ() { return 100; }
            @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
            @Override public boolean permitsScaffoldAt(int x, int z) { return true; }
        };
    }
}
