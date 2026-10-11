package cn.terraleveler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Pure rules for the final scaffold cleanup (0.8.76). In the 0.8.75 test run the cleanup
 * order put two scaffolds 31 blocks up, just outside the selection, first; their pillar
 * column was blocked by a two-block stub of our own, so the job made three 40-second
 * bridge trips toward them and then paused with 29 easy scaffolds still left.
 */
final class CleanupParkingRules {
    /** Times the parked targets of one pass are retried after other scaffolds were removed. */
    static final int MAX_PARKED_RETRIES = 1;
    /** Horizontal (Chebyshev) radius in which a cleanup pillar is raised to the highest scaffold. */
    static final int PILLAR_GROUP_RADIUS = 2;

    private CleanupParkingRules() { }

    /**
     * Moves the parked targets of the remaining part of the queue behind the open ones,
     * keeping both orders. Targets before {@code index} are done and stay where they are.
     */
    static List<BlockPos> parkRemaining(List<BlockPos> targets, int index, Set<BlockPos> parked) {
        int from = Math.max(0, Math.min(index, targets.size()));
        List<BlockPos> result = new ArrayList<>(targets.subList(0, from));
        List<BlockPos> later = new ArrayList<>();
        for (BlockPos target : targets.subList(from, targets.size())) {
            if (parked.contains(target)) later.add(target);
            else result.add(target);
        }
        result.addAll(later);
        return result;
    }

    /** Number of remaining targets that are not parked. */
    static int openRemaining(List<BlockPos> targets, int index, Set<BlockPos> parked) {
        int open = 0;
        for (int i = Math.max(0, index); i < targets.size(); i++) {
            if (!parked.contains(targets.get(i))) open++;
        }
        return open;
    }

    /**
     * Parked targets are tried once more only when some scaffold was removed after they were
     * parked (the removal may have cleared their access); otherwise they are left in place.
     */
    static boolean mayRetryParked(long removalsWhenParked, long removalsNow, int retries) {
        return removalsNow > removalsWhenParked && retries < MAX_PARKED_RETRIES;
    }

    /**
     * Owned scaffolds standing in the columns a cleanup pillar for {@code target} could use
     * (its four horizontal neighbours inside the work area), below the two cells the player
     * needs at the top and above the floor ({ lowestY}). The pillar planner stands on the first solid block of the column, so
     * a low stub of our own there makes the base unreachable. Highest first.
     */
    static List<BlockPos> pillarColumnBlockers(BlockPos target, Collection<BlockPos> owned, int lowestY,
                                               BiPredicate<Integer, Integer> columnInArea) {
        List<BlockPos> blockers = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos column = target.relative(direction);
            if (!columnInArea.test(column.getX(), column.getZ())) continue;
            for (BlockPos pos : owned) {
                if (pos.getX() == column.getX() && pos.getZ() == column.getZ()
                    && pos.getY() >= lowestY && pos.getY() < target.getY() - 1) blockers.add(pos);
            }
        }
        blockers.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        return blockers;
    }

    /**
     * The highest owned scaffold within {@link #PILLAR_GROUP_RADIUS} of {@code candidate}
     * that the predicate accepts, or the candidate itself. A pillar raised to it passes the
     * lower ones at arm's length; 0.8.75 built 16 blocks to Y=80 and then 7 blocks on the
     * same base to Y=71 for the scaffold it had climbed past.
     */
    static BlockPos highestInGroup(BlockPos candidate, Collection<BlockPos> owned, java.util.function.Predicate<BlockPos> eligible) {
        BlockPos best = candidate;
        for (BlockPos pos : owned) {
            if (Math.abs(pos.getX() - candidate.getX()) > PILLAR_GROUP_RADIUS
                || Math.abs(pos.getZ() - candidate.getZ()) > PILLAR_GROUP_RADIUS
                || pos.getY() <= best.getY()
                || !eligible.test(pos)) {
                continue;
            }
            best = pos;
        }
        return best;
    }
}
