package cn.terraleveler;

import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks that hurt, trap or swallow the real player although their collision
 * shape alone looks walkable (fire, berry bushes, cobwebs, powder snow) or
 * standable (magma, campfires, cactus, dripstone). Routes and deliberate drops
 * must never put the player's body into, or feet onto, one of these.
 */
final class HazardousBlocks {
    private HazardousBlocks() { }

    static boolean isHazard(BlockState state) {
        return state != null && isHazard(state.getBlock());
    }

    /**
     * Natural leaves whose log connection is gone (distance 7) disappear on a random tick.
     * After the trunk is cut they look like solid ground but drop the player moments later.
     */
    static boolean isDecayingLeaves(BlockState state) {
        return state != null && state.getBlock() instanceof LeavesBlock
                && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)
                && state.hasProperty(LeavesBlock.DISTANCE)
                && isDecayingLeaves(false, state.getValue(LeavesBlock.DISTANCE));
    }

    static boolean isDecayingLeaves(boolean persistent, int distance) {
        return !persistent && distance >= LeavesBlock.DECAY_DISTANCE;
    }

    static boolean isHazard(Block block) {
        return block instanceof BaseFireBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof WebBlock
                || block instanceof PowderSnowBlock
                || block instanceof WitherRoseBlock
                || block instanceof MagmaBlock
                || block instanceof CampfireBlock
                || block instanceof CactusBlock
                || block instanceof PointedDripstoneBlock;
    }
}
