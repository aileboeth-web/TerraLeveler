package cn.terraleveler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Pure rules for removing job-owned scaffolds during excavation once nothing near them
 * is left to dig (0.8.72). In the 0.8.71 test run 123 scaffolds were left hanging in the
 * air after the mountain around them was removed, and the final cleanup spent 14 minutes
 * and about 200 extra scaffold placements reaching them again.
 */
final class ObsoleteScaffoldRules {
    /** Radius around a scaffold in which remaining excavation keeps it in place. */
    static final double KEEP_RADIUS = 6.0;

    private ObsoleteScaffoldRules() { }

    /**
     * True when no remaining excavation target lies at or above the
     * scaffold within {@link #KEEP_RADIUS}: anything still to be dug that the scaffold
     * could help reach keeps it. Lower targets are reached from the ground.
     */
    static boolean isObsolete(BlockPos scaffold, Collection<BlockPos> nearbyTargets, Predicate<BlockPos> stillToDig) {
        for (BlockPos target : nearbyTargets) {
            if (target.getY() < scaffold.getY()) continue;
            if (stillToDig.test(target)) return false;
        }
        return true;
    }

    /**
     * The scaffold to remove next: at or above the player's feet (never the floor the
     * player may need to walk or climb down on), top of its tracked column, then the
     * highest and nearest. {@code eligible} carries the world checks (reach, support,
     * route use, obsolescence).
     */
    static Optional<BlockPos> select(Collection<BlockPos> tracked, BlockPos feet, Predicate<BlockPos> eligible) {
        return tracked.stream()
                .filter(pos -> pos.getY() >= feet.getY())
                .filter(pos -> isTopOfTrackedColumn(tracked, pos))
                .filter(eligible)
                .min(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()
                        .thenComparingInt(pos -> Math.abs(pos.getX() - feet.getX()) + Math.abs(pos.getZ() - feet.getZ())))
                .map(BlockPos::immutable);
    }

    /** A next target this far away (horizontally) means the player is leaving the area it worked in. */
    static final int SWEEP_TRIGGER_DISTANCE = 12;
    /** Obsolete scaffolds within this horizontal distance are swept before leaving. */
    static final int SWEEP_RADIUS = 16;
    /** Higher than this above the feet they cannot be reached from the ground nearby anyway. */
    static final int SWEEP_MAX_RISE = 8;
    /** Route searches per departure; the rest is tried at the next departure. */
    static final int SWEEP_SEARCHES_PER_DEPARTURE = 4;

    static boolean leavingArea(BlockPos feet, BlockPos nextTarget) {
        return Math.max(Math.abs(nextTarget.getX() - feet.getX()), Math.abs(nextTarget.getZ() - feet.getZ())) > SWEEP_TRIGGER_DISTANCE;
    }

    /** Sweep candidates near the feet, nearest first, highest first among equals (0.8.94). */
    static List<BlockPos> sweepCandidates(Collection<BlockPos> tracked, BlockPos feet, Predicate<BlockPos> eligible) {
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : tracked) {
            if (Math.max(Math.abs(pos.getX() - feet.getX()), Math.abs(pos.getZ() - feet.getZ())) > SWEEP_RADIUS) continue;
            if (pos.getY() < feet.getY() || pos.getY() > feet.getY() + SWEEP_MAX_RISE) continue;
            near.add(pos);
        }
        return near.stream()
                .filter(pos -> isTopOfTrackedColumn(near, pos))
                .sorted(Comparator.comparingInt((BlockPos pos) -> Math.abs(pos.getX() - feet.getX()) + Math.abs(pos.getZ() - feet.getZ()))
                        .thenComparing(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()))
                .filter(eligible)
                .map(BlockPos::immutable)
                .toList();
    }

    static boolean isTopOfTrackedColumn(Collection<BlockPos> tracked, BlockPos candidate) {
        for (BlockPos pos : tracked) {
            if (pos.getX() == candidate.getX() && pos.getZ() == candidate.getZ() && pos.getY() > candidate.getY()) return false;
        }
        return true;
    }
}
