package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FluidFailureDeferralRulesTest {
    @Test
    void choosesLastSafeTargetOnCurrentLayer() {
        BlockPos blocked = new BlockPos(0, 10, 0);
        BlockPos near = new BlockPos(1, 10, 0);
        BlockPos far = new BlockPos(2, 10, 0);
        BlockPos lower = new BlockPos(2, 9, 0);

        int index = FluidFailureDeferralRules.replacementIndex(
                List.of(blocked, near, far, lower), 0, Set.of(blocked), pos -> true);

        assertEquals(2, index);
    }

    @Test
    void skipsOtherDeferredFluidAndUnsafeCandidates() {
        BlockPos blocked = new BlockPos(0, 10, 0);
        BlockPos safe = new BlockPos(1, 10, 0);
        BlockPos unsafe = new BlockPos(2, 10, 0);
        BlockPos otherFluid = new BlockPos(3, 10, 0);

        int index = FluidFailureDeferralRules.replacementIndex(
                List.of(blocked, safe, unsafe, otherFluid), 0,
                Set.of(blocked, otherFluid), pos -> !pos.equals(unsafe));

        assertEquals(1, index);
    }

    @Test
    void fallsBackOnlyToTheImmediatelyLowerLayer() {
        BlockPos blocked = new BlockPos(0, 10, 0);
        BlockPos otherFluid = new BlockPos(1, 10, 0);
        BlockPos lowerNear = new BlockPos(2, 9, 0);
        BlockPos lowerFar = new BlockPos(3, 9, 0);
        BlockPos muchLower = new BlockPos(4, 8, 0);

        int index = FluidFailureDeferralRules.replacementIndex(
                List.of(blocked, otherFluid, lowerNear, lowerFar, muchLower), 0,
                Set.of(blocked, otherFluid), pos -> true);

        assertEquals(3, index);
    }

    @Test
    void stopsWhenCurrentAndNextLayerContainNoSafeWork() {
        BlockPos blocked = new BlockPos(0, 10, 0);
        BlockPos otherFluid = new BlockPos(1, 10, 0);
        BlockPos unsafeLower = new BlockPos(2, 9, 0);

        int index = FluidFailureDeferralRules.replacementIndex(
                List.of(blocked, otherFluid, unsafeLower), 0,
                Set.of(blocked, otherFluid), pos -> false);

        assertEquals(-1, index);
    }
}
