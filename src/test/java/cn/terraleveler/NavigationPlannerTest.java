package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.terraleveler.SafeRoutePlanner.CellKind.OPEN;
import static cn.terraleveler.SafeRoutePlanner.CellKind.SOLID;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.FOUND;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NavigationPlannerTest {
    private static final WorkArea OPEN_AREA = new WorkArea() {
        @Override public int minX() { return -16; }
        @Override public int maxX() { return 16; }
        @Override public int minZ() { return -16; }
        @Override public int maxZ() { return 16; }
        @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
        @Override public boolean permitsScaffoldAt(int x, int z) { return true; }
    };

    @Test
    void naturalRouteIsSelectedWithoutCalculatingAnUnneededFallback() {
        FakeEnvironment environment = new FakeEnvironment()
                .addStand(new BlockPos(0, 1, 0))
                .addStand(new BlockPos(1, 1, 0))
                .addStand(new BlockPos(2, 1, 0));
        NavigationRequest request = request(new BlockPos(0, 1, 0), new BlockPos(2, 1, 0));

        NavigationPlan plan = NavigationPlanner.plan(environment, request);

        assertEquals(FOUND, plan.primary().status());
        assertEquals(NavigationPlan.Selection.PRIMARY, plan.selection());
        assertFalse(plan.selectedFallback());
        assertTrue(plan.fallbackResult().isEmpty());
        assertFalse(plan.selectedPolicy().allowBridge());
    }

    @Test
    void bridgeFallbackIsAPlannerDecisionButNotYetAnExecutionAdmission() {
        FakeEnvironment environment = new FakeEnvironment()
                .addStand(new BlockPos(0, 1, 0));
        NavigationRequest request = request(new BlockPos(0, 1, 0), new BlockPos(2, 1, 0));

        NavigationPlan plan = NavigationPlanner.plan(environment, request);

        assertEquals(NO_SAFE_GOAL, plan.primary().status());
        assertEquals(FOUND, plan.fallbackResult().orElseThrow().status());
        assertEquals(NavigationPlan.Selection.FALLBACK, plan.selection());
        assertTrue(plan.selectedFallback());
        assertTrue(plan.selectedPolicy().allowBridge());
        assertTrue(plan.selected().steps().stream().anyMatch(step ->
                step.type() == SafeRoutePlanner.StepType.BRIDGE));
    }

    @Test
    void unsafeRealStartNeverFallsBackToAnInventedNearbyOrigin() {
        FakeEnvironment environment = new FakeEnvironment()
                .addStand(new BlockPos(1, 1, 0));
        NavigationRequest request = request(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0));

        NavigationPlan plan = NavigationPlanner.plan(environment, request);

        assertEquals(START_REQUIRES_RECOVERY, plan.primary().status());
        assertEquals(NavigationPlan.Selection.PRIMARY, plan.selection());
        assertNull(plan.fallbackAttempt());
    }

    @Test
    void executionAdmissionCarriesTaskIdentityAndPartialContinuationExplicitly() {
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos task = new BlockPos(10, 1, 0);
        SafeRoutePlanner.RouteStep step = new SafeRoutePlanner.RouteStep(
                start, new BlockPos(1, 1, 0), SafeRoutePlanner.StepType.WALK, null);
        NavigationGoal goal = new NavigationGoal(task, List.of(task));
        SafeRoutePlanner.PlanResult partial = new SafeRoutePlanner.PlanResult(
                SafeRoutePlanner.PlanStatus.PARTIAL, List.of(step));

        PathExecutionPlan execution = PathExecutionPlan.admit(
                goal, partial, SafeRoutePlanner.RoutePolicy.natural(), true);

        assertEquals(task, execution.goal().taskKey());
        assertEquals(List.of(step), execution.steps());
        assertTrue(execution.requiresTaskValidation());
        assertTrue(execution.requiresContinuation());
        assertThrows(IllegalArgumentException.class, () -> PathExecutionPlan.admit(
                goal, new SafeRoutePlanner.PlanResult(NO_SAFE_GOAL, List.of()),
                SafeRoutePlanner.RoutePolicy.natural(), false));
    }

    private static NavigationRequest request(BlockPos start, BlockPos goal) {
        return new NavigationRequest(start, new NavigationGoal(goal, List.of(goal)), OPEN_AREA,
                SafeRoutePlanner.RoutePolicy.natural(),
                SafeRoutePlanner.RoutePolicy.withBoundaryBridges());
    }

    private static final class FakeEnvironment implements SafeRoutePlanner.RouteEnvironment {
        private final Set<BlockPos> solid = new HashSet<>();

        private FakeEnvironment addStand(BlockPos feet) {
            solid.add(feet.below().immutable());
            return this;
        }

        @Override
        public SafeRoutePlanner.CellKind cell(BlockPos position) {
            return solid.contains(position) ? SOLID : OPEN;
        }
    }
}
