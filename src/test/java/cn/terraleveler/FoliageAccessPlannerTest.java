package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FoliageAccessPlannerTest {
    @Test
    void currentFoliageTargetIsNotPromotedAgain() {
        BlockPos task = new BlockPos(0, 70, 0);
        BlockPos blocker = new BlockPos(1, 70, 0);

        assertEquals(Optional.of(blocker), FoliageAccessPlanner.select(
                task, BlockPos.ZERO, List.of(task, blocker), ignored -> true, ignored -> false));
    }

    @Test
    void selectsOnlyNearbyLiveUnprotectedFoliage() {
        BlockPos task = new BlockPos(10, 70, 10);
        BlockPos nearest = new BlockPos(9, 70, 10);
        BlockPos protectedLeaf = new BlockPos(10, 70, 9);
        BlockPos distant = new BlockPos(20, 70, 10);

        assertEquals(nearest, FoliageAccessPlanner.select(task, new BlockPos(0, 64, 0),
                List.of(distant, protectedLeaf, nearest), position -> true,
                Set.of(protectedLeaf)::contains).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoNearbyLeafStillExists() {
        assertTrue(FoliageAccessPlanner.select(new BlockPos(0, 64, 0), null,
                List.of(new BlockPos(1, 64, 0)), position -> false, position -> false).isEmpty());
    }
}
