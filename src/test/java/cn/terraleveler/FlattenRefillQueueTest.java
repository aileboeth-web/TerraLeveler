package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenRefillQueueTest {
    @Test
    void surfaceOnlyModeNeverRefillsUndergroundEscapeTunnels() {
        assertFalse(FlattenRefillQueue.shouldRestoreEscapeBlock(new BlockPos(3, 61, 4), 62));
        assertTrue(FlattenRefillQueue.shouldRestoreEscapeBlock(new BlockPos(3, 62, 4), 62));
        assertFalse(FlattenRefillQueue.shouldRestoreEscapeBlock(new BlockPos(3, 63, 4), 62));
    }

    @Test
    void escapeRefillIsInsertedBeforeEveryHigherLayer() {
        BlockPos current = new BlockPos(-87, 72, -82);
        BlockPos refill = new BlockPos(-90, 72, -82);
        BlockPos dependent = new BlockPos(-90, 73, -82);
        List<BlockPos> targets = new ArrayList<>(List.of(current, dependent,
                new BlockPos(-91, 73, -82)));

        assertTrue(FlattenRefillQueue.insertBeforeHigherLayers(targets, 0, refill));
        assertEquals(List.of(current, refill, dependent, new BlockPos(-91, 73, -82)), targets);
        assertFalse(FlattenRefillQueue.insertBeforeHigherLayers(targets, 0, refill));
    }

    @Test
    void pendingSupportIsPromotedImmediatelyBeforeItsDependentTarget() {
        BlockPos target = new BlockPos(-90, 73, -82);
        BlockPos support = target.below();
        BlockPos other = new BlockPos(-91, 73, -82);
        List<BlockPos> targets = new ArrayList<>(List.of(target, other, support));

        assertTrue(FlattenRefillQueue.promotePendingSupport(targets, 0, support));
        assertEquals(List.of(support, target, other), targets);
    }

    @Test
    void nearestCurrentlySupportedSurfaceCellIsPromoted() {
        BlockPos unsupported = new BlockPos(20, 64, 0);
        BlockPos supportedFar = new BlockPos(10, 64, 0);
        BlockPos supportedNear = new BlockPos(2, 64, 0);
        List<BlockPos> targets = new ArrayList<>(List.of(
                unsupported, supportedFar, supportedNear));

        int originalIndex = FlattenRefillQueue.promoteNearestSupported(targets, 0,
                new BlockPos(0, 65, 0), pos -> !pos.equals(unsupported));

        assertEquals(2, originalIndex);
        assertEquals(List.of(supportedNear, unsupported, supportedFar), targets);
        assertEquals(-1, FlattenRefillQueue.promoteNearestSupported(targets, 0,
                new BlockPos(0, 65, 0), pos -> false));
    }

    @Test
    void alreadySupportedCurrentSurfaceCellStaysLockedUntilCompletion() {
        BlockPos current = new BlockPos(20, 64, 0);
        BlockPos closerAlternative = new BlockPos(1, 64, 0);
        List<BlockPos> targets = new ArrayList<>(List.of(current, closerAlternative));

        int selected = FlattenRefillQueue.promoteNearestSupported(targets, 0,
                new BlockPos(0, 65, 0), pos -> true);

        assertEquals(0, selected);
        assertEquals(List.of(current, closerAlternative), targets);
    }
}
