package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** Bubble columns above magma blocks are water to fill, not blocks to dig (0.8.79 test run). */
class Improvements0880Test {
    @Test
    void bubbleColumnIsPureWater() {
        BlockState bubbles = Blocks.BUBBLE_COLUMN.defaultBlockState();
        assertTrue(FluidBlocks.isPureFluid(bubbles));
        assertTrue(FluidBlocks.isPureFluid(Blocks.WATER.defaultBlockState()));
        assertFalse(FluidBlocks.isPureFluid(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertFalse(FluidBlocks.isPureFluid(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void drainPlannerSeesBubbleColumnAsWaterNotAFallThroughCell() {
        // Fluid tags are not bound in unit tests, so feed the real block properties to the rule directly.
        BlockState bubbles = Blocks.BUBBLE_COLUMN.defaultBlockState();
        assertTrue(FallingBlock.isFree(bubbles), "bubble columns are replaceable, which used to mean FALL_THROUGH");
        assertEquals(FluidCellKind.WATER, ConstructionController.classifyFluidCell(
                false, bubbles.isAir(), FallingBlock.isFree(bubbles), FluidBlocks.isPureFluid(bubbles),
                !bubbles.getFluidState().isEmpty(), true, false));
        assertEquals(FluidCellKind.SOLID,
                ConstructionController.classifyFluidCell(Blocks.MAGMA_BLOCK.defaultBlockState()));
    }
}
