package cn.terraleveler;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Scaffold budget bookkeeping (0.8.82 island test run): a restarted job took over 143
 * scaffolds from the stuck run before it, started over its 128 budget and spent its time
 * re-scanning the same unreachable scaffolds several times a second, which made the game stutter.
 */
final class ScaffoldBudgetRules {
    /** After a fruitless relief scan, wait this long before scanning again (30 s). */
    static final int RELIEF_SCAN_COOLDOWN_TICKS = 600;
    /** A changed position this far away makes a new scan worthwhile. */
    static final int RELIEF_SCAN_MOVE_DISTANCE = 8;
    /** Spacing between the batches of a relief scan. */
    static final int RELIEF_SCAN_STEP_TICKS = 4;
    /**
     * A relief scan only tries the nearest scaffolds. In the 0.8.84 test every scan walked
     * through all ~330 owned scaffolds, 4 every 4 ticks, so each one stood still for 15-25 s;
     * 158 scans took 54 of 120 minutes, and only 5 of 36 hits lay beyond the nearest 16.
     */
    static final int RELIEF_SCAN_MAX_CANDIDATES = 16;

    private ScaffoldBudgetRules() { }

    /**
     * Scaffolds inherited from an earlier run do not use up the budget for new placements.
     * The allowance is fixed when the job takes them over: since 0.8.85 taking one down no
     * longer shrinks it, otherwise removing it (or retiring it in a rolling swap) frees nothing.
     */
    static int withInheritedAllowance(int configuredBudget, int inherited) {
        if (configuredBudget <= 0) return configuredBudget;
        long budget = (long) configuredBudget + Math.max(0, inherited);
        return budget > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) budget;
    }

    /**
     * An own scaffold at least this far above the lowest flatten level already worked on is
     * parked: two terrace bands up, it no longer carries any work (0.8.93).
     */
    static final int PARKED_RISE = 12;

    /** Own, non-inherited scaffolds parked above the work (inherited ones have their own allowance). */
    static int parkedScaffolds(Collection<BlockPos> owned, Set<BlockPos> inherited, int lowestWorkY) {
        int parked = 0;
        for (BlockPos pos : owned) {
            if (pos.getY() >= lowestWorkY + PARKED_RISE && !inherited.contains(pos)) parked++;
        }
        return parked;
    }

    /** The nearest candidates (already sorted by distance) a relief scan tries. */
    static <T> List<T> nearestReliefCandidates(List<T> sortedCandidates) {
        return sortedCandidates.size() <= RELIEF_SCAN_MAX_CANDIDATES
                ? sortedCandidates
                : List.copyOf(sortedCandidates.subList(0, RELIEF_SCAN_MAX_CANDIDATES));
    }

    /**
     * A new scaffold placed by a rolling swap does not end the cooldown (0.8.84: the count
     * changed every few steps, so a fresh full scan started again at once); a scaffold that
     * went away, a move of 8 blocks or 30 s does.
     */
    static boolean reliefScanCoolingDown(long now, long exhaustedTick, int activeScaffolds, int exhaustedCount,
                                         BlockPos start, BlockPos exhaustedStart) {
        if (exhaustedTick == Long.MIN_VALUE || exhaustedStart == null || start == null) return false;
        if (now - exhaustedTick >= RELIEF_SCAN_COOLDOWN_TICKS || now < exhaustedTick) return false;
        if (activeScaffolds < exhaustedCount) return false;
        int moved = Math.abs(start.getX() - exhaustedStart.getX()) + Math.abs(start.getY() - exhaustedStart.getY())
                + Math.abs(start.getZ() - exhaustedStart.getZ());
        return moved < RELIEF_SCAN_MOVE_DISTANCE;
    }
}
