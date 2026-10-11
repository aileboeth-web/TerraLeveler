package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Selects bounded same-layer work that may proceed around an unsafe fluid column. */
final class FluidFailureDeferralRules {
    private FluidFailureDeferralRules() { }

    static int replacementIndex(List<BlockPos> targets, int currentIndex,
                                Set<BlockPos> deferredFluidTargets,
                                Predicate<BlockPos> safelyProcessable) {
        if (currentIndex < 0 || currentIndex >= targets.size()) return -1;
        int layerY = targets.get(currentIndex).getY();
        for (int index = targets.size() - 1; index > currentIndex; index--) {
            BlockPos candidate = targets.get(index);
            if (candidate.getY() != layerY) continue;
            if (deferredFluidTargets.contains(candidate)) continue;
            if (safelyProcessable.test(candidate)) return index;
        }

        int nextLayerY = Integer.MIN_VALUE;
        int nextLayerReplacement = -1;
        for (int index = currentIndex + 1; index < targets.size(); index++) {
            BlockPos candidate = targets.get(index);
            if (candidate.getY() >= layerY) continue;
            if (nextLayerY == Integer.MIN_VALUE) nextLayerY = candidate.getY();
            if (candidate.getY() < nextLayerY) break;
            if (deferredFluidTargets.contains(candidate)) continue;
            if (safelyProcessable.test(candidate)) nextLayerReplacement = index;
        }
        return nextLayerReplacement;
    }
}
