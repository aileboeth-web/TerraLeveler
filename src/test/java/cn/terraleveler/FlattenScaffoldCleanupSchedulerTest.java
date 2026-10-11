package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlattenScaffoldCleanupSchedulerTest {
    @Test
    void higherComponentIsCompletedBeforeNearerLowerComponent() {
        BlockPos nearLow = new BlockPos(-300, 70, -370);
        BlockPos nearHigh = new BlockPos(-300, 71, -370);
        BlockPos farHigh = new BlockPos(-300, 82, -330);
        BlockPos farLower = new BlockPos(-299, 81, -329);

        FlattenScaffoldCleanupScheduler.Result result = FlattenScaffoldCleanupScheduler.schedule(
                List.of(nearLow, farLower, farHigh, nearHigh), nearLow);

        assertEquals(List.of(farHigh, farLower, nearHigh, nearLow), result.targets());
        assertEquals(2, result.clusterCount());
    }

    @Test
    void disconnectedStructuresInsideTheOldRadiusAreDifferentClusters() {
        BlockPos first = new BlockPos(-200, 80, -380);
        BlockPos second = new BlockPos(-198, 80, -380);

        FlattenScaffoldCleanupScheduler.Result result = FlattenScaffoldCleanupScheduler.schedule(
                List.of(first, second), first);

        assertEquals(2, result.clusterCount());
        assertFalse(result.sameCluster(first, second));
    }

    @Test
    void oneZoneIsStrictlyTopDownAndEqualHeightUsesLocality() {
        BlockPos origin = new BlockPos(-300, 63, -370);
        BlockPos highFar = new BlockPos(-300, 80, -367);
        BlockPos highNear = new BlockPos(-300, 80, -369);
        BlockPos lower = new BlockPos(-300, 79, -368);

        assertEquals(List.of(highNear, highFar, lower),
                FlattenScaffoldCleanupScheduler.schedule(
                        List.of(lower, highFar, highNear), origin).targets());
    }

    @Test
    void duplicatesAreRemovedAndClusterIdsAreDeterministic() {
        BlockPos outside = new BlockPos(-303, 75, -374);
        BlockPos inside = new BlockPos(-302, 74, -373);

        FlattenScaffoldCleanupScheduler.Result result = FlattenScaffoldCleanupScheduler.schedule(
                List.of(inside, outside, outside), inside);

        assertEquals(List.of(outside, inside), result.targets());
        assertEquals(1, result.clusterCount());
        assertTrue(result.sameCluster(inside, outside));
    }

    @Test
    void diagonalStairAndTransitiveTouchingStayInOneComponent() {
        BlockPos first = new BlockPos(-197, 79, -341);
        BlockPos diagonal = new BlockPos(-196, 80, -342);
        BlockPos next = new BlockPos(-195, 81, -343);
        BlockPos distant = new BlockPos(-193, 81, -343);

        FlattenScaffoldCleanupScheduler.Result result = FlattenScaffoldCleanupScheduler.schedule(
                List.of(first, diagonal, next, distant), first);

        assertTrue(result.sameCluster(first, next));
        assertFalse(result.sameCluster(first, distant));
        assertTrue(FlattenScaffoldCleanupScheduler.directlyTouches(first, diagonal));
        assertFalse(FlattenScaffoldCleanupScheduler.directlyTouches(next, distant));
    }

    @Test
    void crossClusterDeferralMovesTheWholeSelectedClusterTogether() {
        BlockPos firstHigh = new BlockPos(-197, 88, -410);
        BlockPos firstLow = new BlockPos(-197, 87, -410);
        BlockPos secondHigh = new BlockPos(-171, 84, -412);
        BlockPos secondNext = new BlockPos(-170, 84, -412);
        List<BlockPos> targets = List.of(firstHigh, firstLow, secondHigh, secondNext);
        Map<BlockPos, Integer> clusters = Map.of(
                firstHigh, 0, firstLow, 0, secondHigh, 1, secondNext, 1);

        assertEquals(List.of(secondHigh, secondNext, firstHigh, firstLow),
                FlattenScaffoldCleanupScheduler.prioritizeCluster(
                        targets, 0, clusters, secondHigh));
    }

    @Test
    void onlyTheFinalLowerScaffoldMayUseOneWayNonlethalDescent() {
        BlockPos start = new BlockPos(-271, 67, -431);
        BlockPos lowerTask = new BlockPos(-261, 63, -430);

        assertTrue(ConstructionController.mayUseLastScaffoldDescent(
                true, 1, false, lowerTask, start));
        assertFalse(ConstructionController.mayUseLastScaffoldDescent(
                true, 2, false, lowerTask, start));
        assertFalse(ConstructionController.mayUseLastScaffoldDescent(
                true, 1, true, lowerTask, start));
        assertFalse(ConstructionController.mayUseLastScaffoldDescent(
                true, 1, false, start, lowerTask));
    }
}
