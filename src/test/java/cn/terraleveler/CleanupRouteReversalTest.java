package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanupRouteReversalTest {
    @Test
    void reversesExactOutboundGeometryAndTurnsPlacedSupportsIntoOrdinaryGround() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(1, 64, 0);
        BlockPos c = new BlockPos(2, 65, 0);
        BlockPos d = new BlockPos(3, 65, 0);
        List<SafeRoutePlanner.RouteStep> outbound = List.of(
                new SafeRoutePlanner.RouteStep(a, b, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(b, c, SafeRoutePlanner.StepType.SCAFFOLD_UP, null),
                new SafeRoutePlanner.RouteStep(c, d, SafeRoutePlanner.StepType.WALK, null));

        List<SafeRoutePlanner.RouteStep> reversed = CleanupRouteReversal.reverse(outbound, d, a);

        assertEquals(List.of(c, b, a), reversed.stream().map(SafeRoutePlanner.RouteStep::position).toList());
        assertEquals(List.of(SafeRoutePlanner.StepType.WALK, SafeRoutePlanner.StepType.STEP_DOWN,
                        SafeRoutePlanner.StepType.WALK),
                reversed.stream().map(SafeRoutePlanner.RouteStep::type).toList());
        assertEquals(d, reversed.getFirst().origin());
    }

    @Test
    void rejectsDiscontinuousOrUnrelatedRecordedRoute() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(1, 64, 0);
        BlockPos c = new BlockPos(2, 64, 0);
        List<SafeRoutePlanner.RouteStep> broken = List.of(
                new SafeRoutePlanner.RouteStep(a, b, SafeRoutePlanner.StepType.WALK, null),
                new SafeRoutePlanner.RouteStep(c, a, SafeRoutePlanner.StepType.WALK, null));

        assertTrue(CleanupRouteReversal.reverse(broken, a, a).isEmpty());
        assertTrue(CleanupRouteReversal.reverse(List.of(
                new SafeRoutePlanner.RouteStep(a, b, SafeRoutePlanner.StepType.WALK, null)), c, a).isEmpty());
    }

    @Test
    void rejectsADeepDropThatCannotBeWalkedInExactReverse() {
        BlockPos high = new BlockPos(-273, 67, -183);
        BlockPos low = new BlockPos(-274, 65, -183);
        List<SafeRoutePlanner.RouteStep> outbound = List.of(
                new SafeRoutePlanner.RouteStep(
                        high, low, SafeRoutePlanner.StepType.STEP_DOWN, null));

        assertTrue(CleanupRouteReversal.reverse(outbound, low, high).isEmpty());
    }

    @Test
    void resumesExactReturnFromAConfirmedIntermediateNode() {
        BlockPos anchor = new BlockPos(-266, 66, -260);
        BlockPos bridge = new BlockPos(-266, 66, -261);
        BlockPos end = new BlockPos(-266, 66, -262);
        List<SafeRoutePlanner.RouteStep> outbound = List.of(
                new SafeRoutePlanner.RouteStep(
                        anchor, bridge, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(
                        bridge, end, SafeRoutePlanner.StepType.WALK, null));

        List<SafeRoutePlanner.RouteStep> reversed = CleanupRouteReversal.reverse(
                outbound, bridge, anchor);

        assertEquals(1, reversed.size());
        assertEquals(anchor, reversed.getFirst().position());
        assertEquals(SafeRoutePlanner.StepType.WALK, reversed.getFirst().type());
    }
}
