package cn.terraleveler;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerPhysicalStateTest {
    @Test
    void adjacentBlockSupportsAPlayerWhoseCentreCellIsAlreadyOverAir() {
        AABB player = new AABB(-400.753, 9.0, -448.547, -400.153, 10.8, -447.947);
        AABB adjacentFullBlock = new AABB(-401.0, 8.0, -448.0, -400.0, 9.0, -447.0);

        assertTrue(PlayerPhysicalState.collisionSupports(player, adjacentFullBlock));
    }

    @Test
    void merelyTouchingAnEdgeIsNotCountedAsSupport() {
        AABB player = new AABB(0.0, 1.0, 0.2, 0.6, 2.8, 0.8);
        AABB block = new AABB(-1.0, 0.0, 0.0, 0.0, 1.0, 1.0);

        assertFalse(PlayerPhysicalState.collisionSupports(player, block));
    }

    @Test
    void partialHeightCollisionCanBeRealSupport() {
        AABB player = new AABB(0.2, 0.5, 0.2, 0.8, 2.3, 0.8);
        AABB slab = new AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);

        assertTrue(PlayerPhysicalState.collisionSupports(player, slab));
    }

    @Test
    void collisionBelowTheFeetIsNotCurrentSupport() {
        AABB player = new AABB(0.2, 1.0, 0.2, 0.8, 2.8, 0.8);
        AABB lowBlock = new AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0);

        assertFalse(PlayerPhysicalState.collisionSupports(player, lowBlock));
    }

    @Test
    void fullBlockSupportMapsToAnIntegerRouteCell() {
        var surface = new PlayerPhysicalState.SupportSurface(new net.minecraft.core.BlockPos(2, 8, 3), 9.0);

        assertTrue(PlayerPhysicalState.integerRouteFeet(surface, 9.0)
                .filter(new net.minecraft.core.BlockPos(2, 9, 3)::equals).isPresent());
    }

    @Test
    void slabSupportIsStableButDoesNotPretendToBeAnIntegerRouteCell() {
        var surface = new PlayerPhysicalState.SupportSurface(new net.minecraft.core.BlockPos(2, 8, 3), 8.5);

        assertTrue(PlayerPhysicalState.integerRouteFeet(surface, 8.5).isEmpty());
    }

    @Test
    void chestAndFarmlandTopsMapToTheRouteCellAbove() {
        var block = new net.minecraft.core.BlockPos(-227, 86, -500);
        var feet = new net.minecraft.core.BlockPos(-227, 87, -500);

        // 0.9.20 main-instance run: a dungeon chest (top 86.875) paused the job as a "slab".
        assertTrue(PlayerPhysicalState.integerRouteFeet(new PlayerPhysicalState.SupportSurface(block, 86.875), 86.875)
                .filter(feet::equals).isPresent());
        assertTrue(PlayerPhysicalState.integerRouteFeet(new PlayerPhysicalState.SupportSurface(block, 86.9375), 86.9375)
                .filter(feet::equals).isPresent());
        // Enchanting tables (0.75) and slabs stay off the integer grid.
        assertTrue(PlayerPhysicalState.integerRouteFeet(new PlayerPhysicalState.SupportSurface(block, 86.75), 86.75).isEmpty());
    }
}
