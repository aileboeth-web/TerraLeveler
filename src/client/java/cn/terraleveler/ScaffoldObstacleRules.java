package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Pure selection rules for dismantling a job-owned scaffold that blocks a later route. */
final class ScaffoldObstacleRules {
    private static final int HORIZONTAL_RADIUS = 5;

    private ScaffoldObstacleRules() { }

    static Optional<BlockPos> selectReachable(Collection<BlockPos> tracked,
                                              BlockPos taskTarget,
                                              BlockPos routeStart,
                                              WorkArea trench,
                                              Predicate<BlockPos> isExpectedScaffold,
                                              Predicate<BlockPos> isDirectlyBreakable) {
        int minimumY = Math.min(taskTarget.getY(), routeStart.getY()) - 1;
        int maximumY = Math.max(taskTarget.getY() + 3, routeStart.getY() + 2);
        return tracked.stream()
                .filter(pos -> pos.getY() >= minimumY && pos.getY() <= maximumY)
                .filter(pos -> horizontalDistance(pos, routeStart) <= HORIZONTAL_RADIUS)
                .filter(pos -> liesNearTaskCorridor(pos, routeStart, taskTarget))
                .filter(pos -> SafeRoutePlanner.isScaffoldLane(trench, pos.getX(), pos.getZ()))
                .filter(isExpectedScaffold)
                .filter(isDirectlyBreakable)
                // A scaffold staircase must be dismantled from its top first so
                // lower supports remain available until the upper obstruction is gone.
                .min(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()
                        .thenComparingInt((BlockPos pos) -> horizontalDistance(pos, taskTarget))
                        .thenComparingInt((BlockPos pos) -> horizontalDistance(pos, routeStart)))
                .map(BlockPos::immutable);
    }

    static List<BlockPos> budgetReliefCandidates(Collection<BlockPos> tracked,
                                                  BlockPos routeStart,
                                                  WorkArea workArea,
                                                  Predicate<BlockPos> isExpectedScaffold,
                                                  Predicate<BlockPos> isPlayerSupport) {
        return tracked.stream()
                .filter(pos -> SafeRoutePlanner.isScaffoldLane(workArea, pos.getX(), pos.getZ()))
                .filter(isExpectedScaffold)
                .filter(pos -> !isPlayerSupport.test(pos))
                .filter(pos -> isTopOfTrackedColumn(tracked, pos))
                .sorted(Comparator.comparingInt((BlockPos pos) -> horizontalDistance(pos, routeStart))
                        .thenComparing(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed())
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .map(BlockPos::immutable)
                .toList();
    }

    private static boolean isTopOfTrackedColumn(Collection<BlockPos> tracked, BlockPos candidate) {
        return tracked.stream().noneMatch(pos -> pos.getX() == candidate.getX()
                && pos.getZ() == candidate.getZ() && pos.getY() > candidate.getY());
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
    }

    private static boolean liesNearTaskCorridor(BlockPos pos, BlockPos start, BlockPos target) {
        int direct = horizontalDistance(start, target);
        int throughCandidate = horizontalDistance(start, pos) + horizontalDistance(pos, target);
        return throughCandidate <= direct + 2;
    }
}
