package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DestructiveSupportDropPlannerTest {
    private static final BlockPos SUPPORT = new BlockPos(-265, 75, -299);

    @Test
    void rejectsSolidLandingThatWouldCauseFallDamage() {
        var result = DestructiveSupportDropPlanner.findLanding(
                environment(Map.of(SUPPORT.below(20),
                        DestructiveSupportDropPlanner.CellKind.SOLID), -64), SUPPORT);

        assertFalse(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.FALL_DAMAGE, result.status());
        assertEquals(SUPPORT.below(19), result.landing());
        assertEquals(20, result.dropDistance());
    }

    @Test
    void allowsLoadedSolidLandingWithinThreeBlocks() {
        var result = DestructiveSupportDropPlanner.findLanding(
                environment(Map.of(SUPPORT.below(3),
                        DestructiveSupportDropPlanner.CellKind.SOLID), -64), SUPPORT);

        assertTrue(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.SAFE_SOLID, result.status());
        assertEquals(3, result.dropDistance());
    }

    @Test
    void fullHealthDropStillLeavesSixHealth() {
        int maximumDrop = DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(20.0F, 0.0F);
        // 16 blocks: worst-case damage ceil(16.x - 3) = 14, leaving 6 health.
        assertEquals(16, maximumDrop);

        var safe = DestructiveSupportDropPlanner.findLanding(
                environment(Map.of(SUPPORT.below(16),
                        DestructiveSupportDropPlanner.CellKind.SOLID), -64), SUPPORT, maximumDrop);
        var lethal = DestructiveSupportDropPlanner.findLanding(
                environment(Map.of(SUPPORT.below(17),
                        DestructiveSupportDropPlanner.CellKind.SOLID), -64), SUPPORT, maximumDrop);

        assertTrue(safe.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.SAFE_SOLID, safe.status());
        assertFalse(lethal.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.FALL_DAMAGE, lethal.status());
    }

    @Test
    void absorptionContributesToTheConservativeFallBudget() {
        assertEquals(20, DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(20.0F, 4.0F));
        assertEquals(3, DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(0.5F, 0.0F));
        // Low health leaves (almost) only the damage-free three blocks.
        assertEquals(4, DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(8.0F, 0.0F));
        assertEquals(3, DestructiveSupportDropPlanner.maximumSurvivableSolidDrop(7.0F, 0.0F));
    }

    @Test
    void rejectsAHazardousBlockInTheFallColumn() {
        Map<BlockPos, DestructiveSupportDropPlanner.CellKind> cells = new HashMap<>();
        cells.put(SUPPORT.below(2), DestructiveSupportDropPlanner.CellKind.HAZARDOUS_BLOCK);
        cells.put(SUPPORT.below(3), DestructiveSupportDropPlanner.CellKind.SOLID);

        var result = DestructiveSupportDropPlanner.findLanding(environment(cells, -64), SUPPORT);

        assertFalse(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.HAZARDOUS_BLOCK, result.status());
        assertEquals(SUPPORT.below(2), result.conflict());
    }

    @Test
    void allowsWaterBeforeTheFirstSolidLanding() {
        Map<BlockPos, DestructiveSupportDropPlanner.CellKind> cells = new HashMap<>();
        cells.put(SUPPORT.below(12), DestructiveSupportDropPlanner.CellKind.WATER);
        cells.put(SUPPORT.below(20), DestructiveSupportDropPlanner.CellKind.SOLID);

        var result = DestructiveSupportDropPlanner.findLanding(environment(cells, -64), SUPPORT);

        assertTrue(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.SAFE_WATER, result.status());
        assertEquals(SUPPORT.below(12), result.landing());
    }

    @Test
    void rejectsHazardousFluidBeforeADeepFloor() {
        Map<BlockPos, DestructiveSupportDropPlanner.CellKind> cells = new HashMap<>();
        cells.put(SUPPORT.below(8), DestructiveSupportDropPlanner.CellKind.HAZARDOUS_FLUID);
        cells.put(SUPPORT.below(20), DestructiveSupportDropPlanner.CellKind.SOLID);

        var result = DestructiveSupportDropPlanner.findLanding(environment(cells, -64), SUPPORT);

        assertFalse(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.HAZARDOUS_FLUID, result.status());
        assertEquals(SUPPORT.below(8), result.conflict());
    }

    @Test
    void rejectsAnUnloadedDropColumn() {
        var result = DestructiveSupportDropPlanner.findLanding(
                environment(Map.of(SUPPORT.below(6),
                        DestructiveSupportDropPlanner.CellKind.UNLOADED), -64), SUPPORT);

        assertFalse(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.UNLOADED, result.status());
    }

    @Test
    void rejectsAVoidColumn() {
        var result = DestructiveSupportDropPlanner.findLanding(environment(Map.of(), -64), SUPPORT);

        assertFalse(result.allowed());
        assertEquals(DestructiveSupportDropPlanner.Status.VOID, result.status());
    }

    private static DestructiveSupportDropPlanner.Environment environment(
            Map<BlockPos, DestructiveSupportDropPlanner.CellKind> cells, int minimumY) {
        return new DestructiveSupportDropPlanner.Environment() {
            @Override public DestructiveSupportDropPlanner.CellKind cell(BlockPos position) {
                return cells.getOrDefault(position, DestructiveSupportDropPlanner.CellKind.OPEN);
            }

            @Override public int minimumY() {
                return minimumY;
            }
        };
    }
}
