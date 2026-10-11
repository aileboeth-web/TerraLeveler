package cn.terraleveler;

import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

final class FlattenFluidExecutionRules {
    private FlattenFluidExecutionRules() { }

    static int selectNextPlacement(int currentIndex,
                                   int placementCount,
                                   IntPredicate supportReady,
                                   IntPredicate directlyReachable,
                                   IntToDoubleFunction distanceSquared) {
        int best = -1;
        boolean bestDirect = false;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = Math.max(0, currentIndex); index < Math.max(0, placementCount); index++) {
            if (!supportReady.test(index)) continue;
            boolean direct = directlyReachable.test(index);
            double distance = distanceSquared.applyAsDouble(index);
            if (best < 0
                    || direct && !bestDirect
                    || direct == bestDirect && distance < bestDistance
                    || direct == bestDirect && distance == bestDistance && index < best) {
                best = index;
                bestDirect = direct;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Source-first selection (0.8.75): fluid source blocks before flowing cells, then
     * directly reachable ones, then the highest, then the nearest. Filling the sources
     * lets the flowing water drain away by itself; 0.8.74 filled a 156-cell waterfall
     * from the bottom while its source at Y=69 kept refilling it. Skipped placements
     * (dry, or found unreachable this round) are never chosen; -1 when none is left.
     */
    static int selectNextPlacement(int currentIndex,
                                   int placementCount,
                                   IntPredicate supportReady,
                                   IntPredicate skipped,
                                   IntPredicate source,
                                   IntPredicate directlyReachable,
                                   java.util.function.IntUnaryOperator height,
                                   IntToDoubleFunction distanceSquared) {
        int best = -1;
        boolean bestSource = false;
        boolean bestDirect = false;
        int bestHeight = Integer.MIN_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = Math.max(0, currentIndex); index < Math.max(0, placementCount); index++) {
            if (skipped.test(index) || !supportReady.test(index)) continue;
            boolean isSource = source.test(index);
            boolean direct = directlyReachable.test(index);
            int y = height.applyAsInt(index);
            double distance = distanceSquared.applyAsDouble(index);
            boolean better;
            if (best < 0) {
                better = true;
            } else if (isSource != bestSource) {
                better = isSource;
            } else if (direct != bestDirect) {
                better = direct;
            } else if (y != bestHeight) {
                better = y > bestHeight;
            } else {
                better = distance < bestDistance;
            }
            if (better) {
                best = index;
                bestSource = isSource;
                bestDirect = direct;
                bestHeight = y;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Progress-based wait (0.8.78): once nothing feeds the remaining cells, keep waiting while
     * cells keep drying, until none dried for {@code maximumStall} ticks or {@code maximumTicks}
     * passed. A stream cut off at its spring takes several seconds to drain from end to end.
     */
    static boolean shouldKeepWaitingForRecession(boolean anyFeederRemaining, int wetCells, int waitedTicks,
                                                 int stalledTicks, int maximumTicks, int maximumStall) {
        return !anyFeederRemaining && wetCells > 0 && waitedTicks < maximumTicks && stalledTicks < maximumStall;
    }

    /** True while flowing cells remain but every source among them is gone: give them time to drain. */
    static boolean shouldWaitForRecession(boolean anySourceRemaining, boolean anyFlowingRemaining,
                                          int waitedTicks, int maximumTicks) {
        return !anySourceRemaining && anyFlowingRemaining && waitedTicks < maximumTicks;
    }
}
