package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class TreeExcavationSchedulerTest {
    @Test
    void finishesNearestTreeBeforeStartingAHigherDistantTree() {
        BlockPos nearTop = new BlockPos(0, 67, 0);
        BlockPos nearBottom = new BlockPos(0, 66, 0);
        BlockPos farTop = new BlockPos(8, 70, 0);
        BlockPos farBottom = new BlockPos(8, 69, 0);

        TreeExcavationScheduler.Result result = TreeExcavationScheduler.schedule(
                List.of(farTop, nearBottom, farBottom, nearTop),
                new BlockPos(1, 64, 0), Set.of());

        assertEquals(List.of(nearTop, nearBottom, farTop, farBottom), result.targets());
        assertEquals(2, result.treeCount());
    }

    @Test
    void diagonalBranchesBelongToTheSameTree() {
        BlockPos trunk = new BlockPos(0, 65, 0);
        BlockPos upperTrunk = new BlockPos(0, 66, 0);
        BlockPos diagonalBranch = new BlockPos(1, 67, 1);
        BlockPos otherTree = new BlockPos(6, 65, 0);

        Map<BlockPos, Integer> components = TreeExcavationScheduler.componentIds(
                List.of(trunk, upperTrunk, diagonalBranch, otherTree));

        assertEquals(components.get(trunk), components.get(upperTrunk));
        assertEquals(components.get(trunk), components.get(diagonalBranch));
        assertNotEquals(components.get(trunk), components.get(otherTree));
    }

    @Test
    void eachTreeRemainsTopDownInternally() {
        List<BlockPos> targets = TreeExcavationScheduler.schedule(List.of(
                        new BlockPos(0, 65, 0), new BlockPos(0, 67, 0),
                        new BlockPos(0, 66, 0), new BlockPos(1, 67, 0)),
                BlockPos.ZERO, Set.of()).targets();

        assertEquals(List.of(67, 67, 66, 65),
                targets.stream().map(BlockPos::getY).toList());
    }
}
