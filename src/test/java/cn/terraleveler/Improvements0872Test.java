package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Rules added in 0.8.72 after the 0.8.71 test run (stone-filled inventory, fill stalls, scaffolds, routes). */
class Improvements0872Test {
    // --- fill phase: extraction followed by an immediate store ---

    @Test
    void extractedStackIsNotStoredAgainWhileTheJobStillNeedsTheMaterial() {
        // 0.8.71: loose 32, one stack extracted -> 96 > 64, stored at once, stalled every cycle.
        int threshold = ConstructionController.overflowStoreThreshold(64, 1128);
        assertTrue(96 <= threshold);
        assertEquals(128, threshold);
        // Without remaining demand the plain allowance applies.
        assertEquals(64, ConstructionController.overflowStoreThreshold(64, 0));
    }

    @Test
    void overflowStoreNeverEmptiesTheStackInHand() {
        int[] counts = new int[36];
        counts[8] = 31;   // selected hotbar slot, the stack used for placing
        counts[14] = 64;
        // Surplus 31 above the allowance: only the selected stack would fit.
        assertEquals(8, ConstructionController.materialDepositSource(64, counts));
        assertEquals(-1, ConstructionController.materialDepositSource(64, counts, 8));
        // With a larger surplus a main-inventory stack is chosen as before.
        counts[20] = 64;
        assertEquals(14, ConstructionController.materialDepositSource(64, counts, 8));
    }

    @Test
    void boxPutBackByAnotherModIsRecognised() {
        int[] stored = new int[36];
        java.util.Arrays.fill(stored, -1);
        stored[11] = 1695;   // the store box, back in its own slot with 31 more dirt
        stored[12] = 1728;   // another full dirt box
        assertEquals(11, ConstructionController.autoReturnedStoreBoxSlot(11, 1664, 31, stored));
        // Elsewhere only an exact match counts; another box is never guessed.
        stored[11] = -1;
        stored[20] = 1695;
        assertEquals(20, ConstructionController.autoReturnedStoreBoxSlot(11, 1664, 31, stored));
        stored[20] = 1700;
        assertEquals(-1, ConstructionController.autoReturnedStoreBoxSlot(11, 1664, 31, stored));
        assertEquals(-1, ConstructionController.autoReturnedStoreBoxSlot(11, 1664, 0, stored));
    }

    @Test
    void displacedDirtIsDroppedOnlyAboveItsAllowance() {
        // 07:55 in the 0.8.71 run: 7 dirt displaced while 64 stayed loose -> surplus.
        assertTrue(ConstructionController.mayDropCarriedMaterialExcess(false, 64, 64));
        assertFalse(ConstructionController.mayDropCarriedMaterialExcess(false, 40, 64));
        assertFalse(ConstructionController.mayDropCarriedMaterialExcess(true, 640, 64));
    }

    // --- junk discard ---

    @Test
    void thrownSlotRefilledByAPickupStillCountsAsThrown() {
        assertTrue(DirectedItemDiscardTransaction.sourceCleared(true, false, 0, 64));
        // A new partial stack of the same item picked up into the freed slot.
        assertTrue(DirectedItemDiscardTransaction.sourceCleared(false, true, 12, 64));
        assertTrue(DirectedItemDiscardTransaction.sourceCleared(false, false, 64, 64));
        // Unchanged: not thrown yet.
        assertFalse(DirectedItemDiscardTransaction.sourceCleared(false, true, 64, 64));
    }

    // --- scaffolds ---

    @Test
    void scaffoldWithNothingLeftToDigAroundIsObsolete() {
        BlockPos scaffold = new BlockPos(0, 80, 0);
        List<BlockPos> nearby = List.of(new BlockPos(2, 82, 0), new BlockPos(1, 70, 0));
        // The block above-right is already gone; the one far below is reached from the ground.
        assertTrue(ObsoleteScaffoldRules.isObsolete(scaffold, nearby, pos -> pos.getY() < 75));
        // Something still to dig at the scaffold's height keeps it.
        assertFalse(ObsoleteScaffoldRules.isObsolete(scaffold, nearby, pos -> true));
        assertTrue(ObsoleteScaffoldRules.isObsolete(scaffold, List.of(), pos -> true));
    }

    @Test
    void scaffoldsAreRetiredTopDownAndNeverBelowThePlayersFeet() {
        BlockPos feet = new BlockPos(0, 70, 0);
        Set<BlockPos> tracked = new HashSet<>(List.of(
                new BlockPos(1, 69, 0),   // floor level: may be the way down
                new BlockPos(2, 70, 0),
                new BlockPos(2, 71, 0),   // top of the column at x=2
                new BlockPos(0, 72, 3)));
        Optional<BlockPos> first = ObsoleteScaffoldRules.select(tracked, feet, pos -> true);
        assertEquals(new BlockPos(0, 72, 3), first.orElseThrow());
        tracked.remove(new BlockPos(0, 72, 3));
        assertEquals(new BlockPos(2, 71, 0), ObsoleteScaffoldRules.select(tracked, feet, pos -> true).orElseThrow());
        tracked.remove(new BlockPos(2, 71, 0));
        assertEquals(new BlockPos(2, 70, 0), ObsoleteScaffoldRules.select(tracked, feet, pos -> true).orElseThrow());
        tracked.remove(new BlockPos(2, 70, 0));
        assertTrue(ObsoleteScaffoldRules.select(tracked, feet, pos -> true).isEmpty());
    }

    @Test
    void refusedScaffoldPlacementIsPlannedAroundUpToTheLimit() {
        assertTrue(ConstructionController.mayReplanAfterRejectedScaffold(true, 1));
        assertTrue(ConstructionController.mayReplanAfterRejectedScaffold(true, ConstructionController.MAX_REJECTED_SCAFFOLD_PLACEMENTS - 1));
        assertFalse(ConstructionController.mayReplanAfterRejectedScaffold(true, ConstructionController.MAX_REJECTED_SCAFFOLD_PLACEMENTS));
        // A block that did appear is not planned around (the old checks handle it).
        assertFalse(ConstructionController.mayReplanAfterRejectedScaffold(false, 1));
    }

    // --- routes ---

    private static final WorkArea WIDE_AREA = new WorkArea() {
        @Override public int minX() { return -8; }
        @Override public int maxX() { return 8; }
        @Override public int minZ() { return -8; }
        @Override public int maxZ() { return 16; }
        @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
        @Override public boolean permitsScaffoldAt(int x, int z) { return true; }
    };

    /** Floor at y=0 everywhere nearby, a 3-high wall at z=5 with one gap at x=40. */
    private static SafeRoutePlanner.RouteEnvironment wallWithFarGap() {
        return position -> {
            if (position.getY() == 0 && Math.abs(position.getX()) <= 60 && position.getZ() >= -2 && position.getZ() <= 12) {
                return SafeRoutePlanner.CellKind.SOLID;
            }
            if (position.getZ() == 5 && position.getY() >= 1 && position.getY() <= 3 && position.getX() != 40) {
                return SafeRoutePlanner.CellKind.SOLID;
            }
            return SafeRoutePlanner.CellKind.OPEN;
        };
    }

    @Test
    void detourBeyondTheNormalSearchBoxIsFoundByTheExpandedRetry() {
        BlockPos start = new BlockPos(0, 1, 0);
        List<BlockPos> goals = List.of(new BlockPos(0, 1, 10));
        SafeRoutePlanner.PlanResult normal = SafeRoutePlanner.findRoute(
                wallWithFarGap(), start, goals, WIDE_AREA, SafeRoutePlanner.RoutePolicy.flatteningFloor());
        assertEquals(SafeRoutePlanner.PlanStatus.NO_ROUTE, normal.status());
        assertTrue(SafeRoutePlanner.expandableFailure(normal.status()));

        SafeRoutePlanner.PlanResult expanded = SafeRoutePlanner.findRouteExpanded(
                wallWithFarGap(), start, goals, WIDE_AREA, SafeRoutePlanner.RoutePolicy.flatteningFloor(), Set.of());
        assertEquals(SafeRoutePlanner.PlanStatus.FOUND, expanded.status());
        assertTrue(expanded.steps().stream().anyMatch(step -> step.position().getX() == 40));
    }

    @Test
    void navigationPlannerRetriesOnlySearchRoomFailures() {
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos goal = new BlockPos(0, 1, 10);
        NavigationRequest request = new NavigationRequest(start, new NavigationGoal(goal, List.of(goal)), WIDE_AREA,
                SafeRoutePlanner.RoutePolicy.flatteningFloor(), null);
        NavigationPlan plan = NavigationPlanner.plan(wallWithFarGap(), request);
        assertEquals(SafeRoutePlanner.PlanStatus.FOUND, plan.selected().status());

        assertFalse(SafeRoutePlanner.expandableFailure(SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY));
        assertFalse(SafeRoutePlanner.expandableFailure(SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL));
        assertTrue(SafeRoutePlanner.expandableFailure(SafeRoutePlanner.PlanStatus.SEARCH_LIMIT));
    }

    // --- drained fluid cells ---

    @Test
    void drainFillIsDugNextAndNotTwice() {
        BlockPos a = new BlockPos(0, 70, 0);
        BlockPos b = new BlockPos(1, 70, 0);
        BlockPos c = new BlockPos(2, 70, 0);
        BlockPos passed = new BlockPos(5, 88, 0);   // already passed in the queue
        BlockPos later = new BlockPos(6, 87, 0);    // still ahead in the queue
        List<BlockPos> targets = List.of(passed, a, b, later, c);
        List<BlockPos> result = ConstructionController.requeueAtIndex(targets, 2, List.of(later, passed));
        assertEquals(List.of(passed, a, passed, later, b, c), result);
    }

    // --- trees per tile ---

    @Test
    void treesAreCutWhenTheirTileIsReachedInsteadOfAllFirst() {
        // Two tiles along x: tile 0 (x 0..15) and tile 4 (x 64..79), a tree in each.
        List<BlockPos> ground = new ArrayList<>();
        for (int x = 0; x < 4; x++) ground.add(new BlockPos(x, 64, 0));
        for (int x = 64; x < 68; x++) ground.add(new BlockPos(x, 64, 0));
        List<BlockPos> trees = new ArrayList<>();
        for (int y = 65; y < 69; y++) trees.add(new BlockPos(2, y, 2));     // near the start
        for (int y = 65; y < 69; y++) trees.add(new BlockPos(66, y, 2));    // far tile

        List<BlockPos> order = FlattenExcavationScheduler.scheduleWithTrees(ground, trees, new BlockPos(0, 65, 0), Set.of()).targets();
        assertEquals(ground.size() + trees.size(), order.size());
        int firstFarTree = order.indexOf(new BlockPos(66, 68, 2));
        int lastNearGround = order.indexOf(new BlockPos(3, 64, 0));
        int firstFarGround = order.stream().filter(pos -> pos.getX() >= 64 && pos.getY() == 64).map(order::indexOf).min(Integer::compare).orElseThrow();
        // The near tile (its tree first, then its ground) is finished before the far tree.
        assertTrue(order.indexOf(new BlockPos(2, 68, 2)) < order.indexOf(new BlockPos(0, 64, 0)));
        assertTrue(lastNearGround < firstFarTree);
        // In the far tile the tree still comes before its ground.
        assertTrue(firstFarTree < firstFarGround);
        // Each tree is cut top-down.
        assertTrue(order.indexOf(new BlockPos(66, 68, 2)) < order.indexOf(new BlockPos(66, 65, 2)));
    }

    @Test
    void excludedScaffoldsAreCountedAndSkippedInTheCombinedSchedule() {
        BlockPos scaffold = new BlockPos(1, 64, 0);
        var result = FlattenExcavationScheduler.scheduleWithTrees(
                List.of(new BlockPos(0, 64, 0), scaffold), List.of(), BlockPos.ZERO, Set.of(scaffold));
        assertEquals(List.of(new BlockPos(0, 64, 0)), result.targets());
        assertEquals(1, result.excludedScaffolds());
    }
}
