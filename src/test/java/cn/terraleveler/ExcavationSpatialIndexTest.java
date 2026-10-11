package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExcavationSpatialIndexTest {
    @Test
    void queryUsesThreeDimensionalReachInsteadOfQueueOrderOrLayer() {
        ExcavationSpatialIndex index = new ExcavationSpatialIndex();
        BlockPos below = new BlockPos(-2, 63, -2);
        BlockPos sameLayer = new BlockPos(1, 65, 0);
        BlockPos above = new BlockPos(0, 67, 1);
        BlockPos outside = new BlockPos(5, 65, 0);
        index.rebuild(List.of(outside, above, below, sameLayer));

        List<BlockPos> nearby = index.within(new Vec3(0.5, 65.5, 0.5), 4.5);

        assertEquals(3, nearby.size());
        assertTrue(nearby.contains(below));
        assertTrue(nearby.contains(sameLayer));
        assertTrue(nearby.contains(above));
    }

    @Test
    void rebuildDropsTargetsFromThePreviousPlanAndHandlesNegativeCells() {
        ExcavationSpatialIndex index = new ExcavationSpatialIndex();
        index.rebuild(List.of(new BlockPos(-5, 70, -5), new BlockPos(40, 70, 40)));
        assertEquals(2, index.size());

        BlockPos replacement = new BlockPos(-1, 70, -1);
        index.rebuild(List.of(replacement));

        assertEquals(1, index.size());
        assertEquals(List.of(replacement), index.within(new Vec3(-0.5, 70.5, -0.5), 0.1));
    }
}
