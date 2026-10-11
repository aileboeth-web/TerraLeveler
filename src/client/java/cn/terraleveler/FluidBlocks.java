package cn.terraleveler;

import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cells that are nothing but fluid: plain water/lava, and bubble columns (water above
 * soul sand or magma), which carry a water source but are not a {@link LiquidBlock}.
 * Placing a block replaces them; breaking them does nothing.
 */
final class FluidBlocks {
    private FluidBlocks() { }

    static boolean isPureFluid(BlockState state) {
        return state != null && (state.getBlock() instanceof LiquidBlock || state.getBlock() instanceof BubbleColumnBlock);
    }
}
