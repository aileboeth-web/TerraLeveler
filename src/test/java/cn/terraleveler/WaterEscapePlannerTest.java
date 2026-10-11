package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WaterEscapePlannerTest {
    @Test
    void choosesNearestLoadedDryBankThatActuallyTouchesWater() {
        Set<BlockPos> safe = Set.of(new BlockPos(2, 10, 0), new BlockPos(5, 10, 0));
        Set<BlockPos> water = Set.of(new BlockPos(0, 10, 0), new BlockPos(1, 10, 0),
                new BlockPos(4, 10, 0));
        var result = WaterEscapePlanner.nearestDryExit(environment(safe, water, Set.of()),
                new BlockPos(0, 10, 0), 8);
        assertEquals(new BlockPos(2, 10, 0), result.orElseThrow());
    }

    @Test
    void rejectsDryCellsThatDoNotBorderWaterOrAreUnloaded() {
        BlockPos sealedRoom = new BlockPos(-4, 10, 0);
        BlockPos unloadedBank = new BlockPos(3, 10, 0);
        Set<BlockPos> safe = Set.of(sealedRoom, unloadedBank);
        Set<BlockPos> water = Set.of(new BlockPos(0, 10, 0), new BlockPos(1, 10, 0),
                new BlockPos(2, 10, 0));
        var result = WaterEscapePlanner.nearestDryExit(environment(safe, water, Set.of(unloadedBank)),
                new BlockPos(0, 10, 0), 8);
        assertTrue(result.isEmpty());
    }

    @Test
    void ignoresACloserBankBesideADisconnectedPool() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos connectedBank = new BlockPos(5, 10, 0);
        BlockPos disconnectedBank = new BlockPos(1, 10, 2);
        Set<BlockPos> safe = Set.of(connectedBank, disconnectedBank);
        Set<BlockPos> water = Set.of(
                origin,
                new BlockPos(1, 10, 0),
                new BlockPos(2, 10, 0),
                new BlockPos(3, 10, 0),
                new BlockPos(4, 10, 0),
                new BlockPos(1, 10, 3));

        var result = WaterEscapePlanner.nearestDryExit(environment(safe, water, Set.of()), origin, 8);

        assertEquals(connectedBank, result.orElseThrow());
    }

    @Test
    void connectedSearchAndBankSelectionRespectTheStrictRadius() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos outsideBank = new BlockPos(4, 10, 0);
        Set<BlockPos> water = Set.of(origin, new BlockPos(1, 10, 0),
                new BlockPos(2, 10, 0), new BlockPos(3, 10, 0));

        var result = WaterEscapePlanner.nearestDryExit(
                environment(Set.of(outsideBank), water, Set.of()), origin, 2);

        assertTrue(result.isEmpty());
    }

    @Test
    void thirtyTwoBlockRecoveryFindsBankBeyondTheOldSixteenBlockLimit() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos bank = new BlockPos(21, 10, 0);
        Set<BlockPos> water = new HashSet<>();
        for (int x = 0; x < bank.getX(); x++) {
            water.add(new BlockPos(x, 10, 0));
        }
        WaterEscapePlanner.Environment environment = environment(Set.of(bank), water, Set.of());

        assertTrue(WaterEscapePlanner.nearestDryExit(environment, origin, 16).isEmpty());
        assertEquals(bank, WaterEscapePlanner.nearestDryExit(environment, origin, 32).orElseThrow());
    }

    @Test
    void escapePlanExposesTheShortestConnectedWaterWaypoints() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos bank = new BlockPos(3, 10, 0);
        Set<BlockPos> water = Set.of(origin, new BlockPos(1, 10, 0), new BlockPos(2, 10, 0));

        var result = WaterEscapePlanner.nearestDryEscape(
                environment(Set.of(bank), water, Set.of()), origin, 8).orElseThrow();

        assertEquals(bank, result.bank());
        assertEquals(List.of(origin, new BlockPos(1, 10, 0), new BlockPos(2, 10, 0)),
                result.waterWaypoints());
    }

    @Test
    void shorterSwimmableRouteBeatsAGeometricallyCloserBankBehindAWaterDetour() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos detourBank = new BlockPos(3, 10, 0);
        BlockPos directBank = new BlockPos(0, 10, -4);
        Set<BlockPos> water = Set.of(
                origin,
                new BlockPos(0, 10, 1), new BlockPos(0, 10, 2),
                new BlockPos(1, 10, 2), new BlockPos(2, 10, 2),
                new BlockPos(2, 10, 1), new BlockPos(2, 10, 0),
                new BlockPos(0, 10, -1), new BlockPos(0, 10, -2),
                new BlockPos(0, 10, -3));

        var result = WaterEscapePlanner.nearestDryEscape(
                environment(Set.of(detourBank, directBank), water, Set.of()), origin, 8)
                .orElseThrow();

        assertEquals(directBank, result.bank());
        assertEquals(List.of(origin, new BlockPos(0, 10, -1),
                new BlockPos(0, 10, -2), new BlockPos(0, 10, -3)),
                result.waterWaypoints());
    }

    @Test
    void neverSelectsABankBelowTheOriginWhenNavigationOnlyEscapesUpward() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos lowerBank = new BlockPos(1, 9, 0);
        Set<BlockPos> water = Set.of(origin, origin.below());

        assertTrue(WaterEscapePlanner.nearestDryExit(
                environment(Set.of(lowerBank), water, Set.of()), origin, 8).isEmpty());
    }

    @Test
    void elevatedBankRequiresClearSpaceAboveTheExitWater() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos lowerWater = new BlockPos(1, 9, 0);
        BlockPos elevatedBank = new BlockPos(2, 10, 0);
        BlockPos blockedExit = lowerWater.above();
        Set<BlockPos> water = Set.of(origin, origin.below(), lowerWater);

        assertTrue(WaterEscapePlanner.nearestDryExit(
                environment(Set.of(elevatedBank), water, Set.of(), Set.of(blockedExit)),
                origin, 8).isEmpty());
    }

    @Test
    void waterloggedSolidCannotBecomeAWaterBfsEdge() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos waterloggedBarrier = new BlockPos(1, 10, 0);
        BlockPos waterBeyond = new BlockPos(2, 10, 0);
        BlockPos unreachableBank = new BlockPos(3, 10, 0);
        Set<BlockPos> water = Set.of(origin, waterloggedBarrier, waterBeyond);

        assertTrue(WaterEscapePlanner.nearestDryEscape(
                environment(Set.of(unreachableBank), water, Set.of(), Set.of(waterloggedBarrier)),
                origin, 8).isEmpty());
    }

    private static WaterEscapePlanner.Environment environment(Set<BlockPos> safe, Set<BlockPos> water,
                                                               Set<BlockPos> unloaded) {
        return environment(safe, water, unloaded, Set.of());
    }

    private static WaterEscapePlanner.Environment environment(Set<BlockPos> safe, Set<BlockPos> water,
                                                               Set<BlockPos> unloaded,
                                                               Set<BlockPos> blocked) {
        Set<BlockPos> safeCopy = new HashSet<>(safe);
        Set<BlockPos> waterCopy = new HashSet<>(water);
        return new WaterEscapePlanner.Environment() {
            @Override public boolean loaded(BlockPos position) { return !unloaded.contains(position); }
            @Override public boolean safeDryStand(BlockPos feet) { return safeCopy.contains(feet); }
            @Override public boolean water(BlockPos position) { return waterCopy.contains(position); }
            @Override public boolean passable(BlockPos position) { return !blocked.contains(position); }
        };
    }
}
