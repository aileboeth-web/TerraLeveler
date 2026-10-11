package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlattenExcavationSchedulerTest {
    @Test
    void finishesNearbyTileBeforeChasingAHigherBlockAcrossTheSelection() {
        List<BlockPos> source = List.of(
                new BlockPos(1, 80, 1),
                new BlockPos(2, 79, 1),
                new BlockPos(160, 81, 1),
                new BlockPos(161, 80, 1));

        var result = FlattenExcavationScheduler.schedule(
                source, new BlockPos(0, 70, 0), Set.of());

        assertEquals(List.of(source.get(0), source.get(1), source.get(2), source.get(3)),
                result.targets());
        assertEquals(2, result.tileCount());
    }

    @Test
    void eachTileAndColumnRemainStrictlyTopDown() {
        List<BlockPos> source = List.of(
                new BlockPos(3, 65, 3), new BlockPos(4, 67, 4),
                new BlockPos(3, 67, 3), new BlockPos(4, 66, 4),
                new BlockPos(3, 66, 3), new BlockPos(4, 65, 4));

        List<BlockPos> targets = FlattenExcavationScheduler.schedule(
                source, new BlockPos(3, 64, 3), Set.of()).targets();

        assertEquals(List.of(67, 67, 66, 66, 65, 65),
                targets.stream().map(BlockPos::getY).toList());
        for (int index = 0; index < targets.size(); index++) {
            BlockPos current = targets.get(index);
            for (int later = index + 1; later < targets.size(); later++) {
                BlockPos next = targets.get(later);
                if (current.getX() == next.getX() && current.getZ() == next.getZ()) {
                    assertTrue(current.getY() > next.getY());
                }
            }
        }
    }

    @Test
    void confirmedOwnedScaffoldsAreNotOrdinaryExcavationTargets() {
        BlockPos scaffold = new BlockPos(1, 70, 1);
        BlockPos terrain = new BlockPos(2, 70, 1);

        var result = FlattenExcavationScheduler.schedule(
                List.of(scaffold, terrain), terrain, Set.of(scaffold));

        assertEquals(List.of(terrain), result.targets());
        assertEquals(1, result.excludedScaffolds());
    }

    @Test
    void negativeCoordinatesUseFloorDivChunkTiles() {
        var result = FlattenExcavationScheduler.schedule(List.of(
                        new BlockPos(-1, 70, 0),
                        new BlockPos(-16, 70, 0),
                        new BlockPos(-17, 70, 0)),
                new BlockPos(-1, 70, 0), Set.of());

        assertEquals(2, result.tileCount());
        assertEquals(List.of(new BlockPos(-1, 70, 0), new BlockPos(-16, 70, 0),
                new BlockPos(-17, 70, 0)), result.targets());
    }

    @Test
    void schedulingIsDeterministicUniqueAndKeepsEveryTerrainTarget() {
        List<BlockPos> source = List.of(
                new BlockPos(17, 70, 0), new BlockPos(0, 70, 0),
                new BlockPos(17, 70, 0), new BlockPos(-1, 69, 0),
                new BlockPos(1, 70, 1));
        BlockPos excluded = new BlockPos(-1, 69, 0);

        var first = FlattenExcavationScheduler.schedule(source, BlockPos.ZERO, Set.of(excluded));
        var second = FlattenExcavationScheduler.schedule(source, BlockPos.ZERO, Set.of(excluded));

        assertEquals(first, second);
        assertEquals(3, first.targets().size());
        assertEquals(3, new HashSet<>(first.targets()).size());
        assertFalse(first.targets().contains(excluded));
        assertTrue(first.targets().containsAll(List.of(
                new BlockPos(17, 70, 0), new BlockPos(0, 70, 0), new BlockPos(1, 70, 1))));
    }
}
