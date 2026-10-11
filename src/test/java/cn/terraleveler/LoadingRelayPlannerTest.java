package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoadingRelayPlannerTest {
    @Test
    void choosesFarthestSafeLoadedPointTowardTarget() {
        BlockPos origin = new BlockPos(-10, 70, 0);
        BlockPos target = new BlockPos(100, 70, 0);
        BlockPos expected = new BlockPos(30, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(90, 70, -5), new BlockPos(100, 70, 5),
                1, 70, 80, -64);
        var result = LoadingRelayPlanner.choose(environment(Set.of(expected)), origin, target, trench, 40);
        assertEquals(expected, result.orElseThrow());
    }

    @Test
    void neverSelectsStrictInteriorAsAChunkLoadingShortcut() {
        BlockPos origin = new BlockPos(-10, 70, 5);
        BlockPos target = new BlockPos(10, 70, 5);
        BlockPos interior = new BlockPos(5, 70, 5);
        TrenchPlan trench = new TrenchPlan(new BlockPos(0, 70, 0), new BlockPos(10, 70, 10),
                1, 70, 80, -64);
        assertTrue(LoadingRelayPlanner.choose(environment(Set.of(interior)), origin, target, trench, 15).isEmpty());
    }

    @Test
    void relayMakesBoundedForwardProgressAndNeverSelectsTheRemoteTarget() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(100, 70, 0);
        BlockPos expected = new BlockPos(40, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(90, 70, -5), new BlockPos(100, 70, 5),
                1, 70, 80, -64);

        var relay = LoadingRelayPlanner.choose(environment(Set.of(expected, target)),
                origin, target, trench, 40);

        assertEquals(expected, relay.orElseThrow());
        assertTrue(relay.get().distSqr(origin) <= 40L * 40L);
        assertTrue(relay.get().distSqr(target) < origin.distSqr(target));
    }

    @Test
    void relayRefusesANonPositiveDistanceBudget() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(100, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(90, 70, -5), new BlockPos(100, 70, 5),
                1, 70, 80, -64);

        for (int invalidBudget : new int[] {0, -1}) {
            assertTrue(LoadingRelayPlanner.choose(environment(Set.of(new BlockPos(1, 70, 0))),
                    origin, target, trench, invalidBudget).isEmpty());
        }
    }

    @Test
    void sameLevelRelayIsPreferredOverAnIsolatedHigherLedge() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(100, 70, 0);
        BlockPos reachableLevel = new BlockPos(40, 70, 0);
        BlockPos highLedge = new BlockPos(40, 75, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(90, 70, -5), new BlockPos(100, 70, 5),
                1, 70, 80, -64);

        var relay = LoadingRelayPlanner.choose(environment(Set.of(highLedge, reachableLevel)),
                origin, target, trench, 40);

        assertEquals(reachableLevel, relay.orElseThrow());
    }

    @Test
    void excludedUnreachableRelayDoesNotShadowTheNextCandidate() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(100, 70, 0);
        BlockPos unreachable = new BlockPos(40, 70, 0);
        BlockPos fallback = new BlockPos(39, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(90, 70, -5), new BlockPos(100, 70, 5),
                1, 70, 80, -64);

        var relay = LoadingRelayPlanner.choose(environment(Set.of(unreachable, fallback)),
                origin, target, trench, 40, Set.of(unreachable));

        assertEquals(fallback, relay.orElseThrow());
    }

    @Test
    void twoBlockDistantTargetCanUseTheSingleIntermediateBlock() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(2, 70, 0);
        BlockPos intermediate = new BlockPos(1, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(10, 70, -5), new BlockPos(20, 70, 5),
                1, 70, 80, -64);

        var relay = LoadingRelayPlanner.choose(environment(Set.of(intermediate)),
                origin, target, trench, 40);

        assertEquals(intermediate, relay.orElseThrow());
    }

    @Test
    void adjacentAndDiagonalAdjacentTargetsHaveNoInventedRelay() {
        BlockPos origin = new BlockPos(0, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(10, 70, -5), new BlockPos(20, 70, 5),
                1, 70, 80, -64);

        assertTrue(LoadingRelayPlanner.choose(environment(Set.of(new BlockPos(1, 70, 0))),
                origin, new BlockPos(1, 70, 0), trench, 40).isEmpty());
        assertTrue(LoadingRelayPlanner.choose(environment(Set.of(new BlockPos(1, 70, 1))),
                origin, new BlockPos(1, 70, 1), trench, 40).isEmpty());
    }

    @Test
    void diagonalRemoteTargetStillChoosesAStrictlyCloserIntermediate() {
        BlockPos origin = new BlockPos(0, 70, 0);
        BlockPos target = new BlockPos(2, 70, 1);
        BlockPos intermediate = new BlockPos(1, 70, 0);
        TrenchPlan trench = new TrenchPlan(new BlockPos(10, 70, -5), new BlockPos(20, 70, 5),
                1, 70, 80, -64);

        var relay = LoadingRelayPlanner.choose(environment(Set.of(intermediate)),
                origin, target, trench, 40);

        assertEquals(intermediate, relay.orElseThrow());
    }

    private static LoadingRelayPlanner.Environment environment(Set<BlockPos> safe) {
        return new LoadingRelayPlanner.Environment() {
            @Override public boolean loaded(BlockPos position) { return safe.contains(position); }
            @Override public boolean safeStand(BlockPos feet) { return safe.contains(feet); }
        };
    }
}
