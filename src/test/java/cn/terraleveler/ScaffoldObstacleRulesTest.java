package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ScaffoldObstacleRulesTest {
    private static final TrenchPlan TRENCH = new TrenchPlan(
            new BlockPos(-415, 62, -211), new BlockPos(-362, 62, -165),
            1, 62, 62, -64);

    @Test
    void dismantlesTheRecordedBlockingTowerFromTheTop() {
        BlockPos target = new BlockPos(-393, 58, -211);
        BlockPos start = new BlockPos(-398, 59, -212);
        List<BlockPos> tracked = List.of(
                new BlockPos(-397, 59, -211),
                new BlockPos(-396, 60, -211),
                new BlockPos(-397, 61, -211));

        assertEquals(new BlockPos(-397, 61, -211),
                ScaffoldObstacleRules.selectReachable(tracked, target, start, TRENCH,
                        ignored -> true, ignored -> true).orElseThrow());
    }

    @Test
    void neverSelectsUntrackedChangedUnsafeOrDistantBlocks() {
        BlockPos target = new BlockPos(-393, 58, -211);
        BlockPos start = new BlockPos(-398, 59, -212);
        BlockPos changedByPlayer = new BlockPos(-397, 61, -211);
        BlockPos supportingPlayer = new BlockPos(-398, 58, -212);
        BlockPos distantScaffold = new BlockPos(-380, 61, -211);
        Set<BlockPos> expectedScaffolds = Set.of(supportingPlayer, distantScaffold);

        assertTrue(ScaffoldObstacleRules.selectReachable(
                List.of(changedByPlayer, supportingPlayer, distantScaffold),
                target, start, TRENCH,
                expectedScaffolds::contains,
                pos -> !pos.equals(supportingPlayer)).isEmpty());
    }

    @Test
    void longBridgeCanBeDismantledFromThePlayerEndBeforeItIsNearTheTask() {
        BlockPos target = new BlockPos(-393, 58, -211);
        BlockPos start = new BlockPos(-403, 59, -211);
        BlockPos reachableBridge = new BlockPos(-399, 60, -211);
        BlockPos unrelatedSideBridge = new BlockPos(-403, 60, -207);

        assertEquals(reachableBridge, ScaffoldObstacleRules.selectReachable(
                List.of(reachableBridge, unrelatedSideBridge), target, start, TRENCH,
                ignored -> true, ignored -> true).orElseThrow());
    }

    @Test
    void budgetReliefOrdersNearbyOwnedColumnTopsAndRejectsPlayerSupport() {
        BlockPos start = new BlockPos(-403, 59, -212);
        BlockPos lowerTower = new BlockPos(-402, 59, -211);
        BlockPos upperTower = new BlockPos(-402, 61, -211);
        BlockPos playerSupport = new BlockPos(-403, 58, -212);
        BlockPos fartherBridge = new BlockPos(-397, 60, -211);
        BlockPos changed = new BlockPos(-401, 60, -211);
        Set<BlockPos> expected = Set.of(lowerTower, upperTower, playerSupport, fartherBridge);

        assertEquals(List.of(upperTower, fartherBridge),
                ScaffoldObstacleRules.budgetReliefCandidates(
                        List.of(lowerTower, upperTower, playerSupport, fartherBridge, changed),
                        start, TRENCH, expected::contains, playerSupport::equals));
    }
}
