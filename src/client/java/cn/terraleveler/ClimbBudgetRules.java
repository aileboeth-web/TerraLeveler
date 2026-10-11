package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Scaffold budget rules for climbing back up to unexcavated high ground (0.8.80 island test:
 * from a pit floor at 126/128 the player built a 19-scaffold stair 40 blocks up a cliff,
 * arrived at 143/128 and could neither move on nor come back down).
 */
final class ClimbBudgetRules {
    /** A route that rises at least this far above its start counts as a climb. */
    static final int MIN_CLIMB_RISE = 6;
    /** Scaffolds that must stay free after a climb so the player can still move on or return. */
    static final int CLIMB_RESERVE = 16;
    /** Owned scaffolds within this distance are pillar-cleaned before a climb is allowed. */
    static final int RELIEF_RADIUS = 48;
    /** Feet this far above the finished ground while standing on an own scaffold means stranded. */
    static final int STRANDED_HEIGHT = 6;
    /** One-time extra allowance for a stranded player, on top of the normal temporary debt. */
    static final int EMERGENCY_SCAFFOLD_DEBT = 16;
    /**
     * A target this far above the player is reached with a temporary pillar before a scaffold
     * stair is built (0.8.85 test: a 49-scaffold stair toward an old scaffold bridge 51 blocks up
     * lost its route at Y=87 and left the player 24 blocks above the ground).
     */
    static final int PILLAR_FIRST_RISE = 12;
    /** Pillar tries per high target before a stair; fetching material from a box uses one. */
    static final int PILLAR_FIRST_ATTEMPTS = 3;
    /**
     * A target whose shortest pillar needs at least this many blocks hangs over open air: no walk
     * can reach it, so the pillar is tried before any route search (0.8.89).
     */
    static final int FLOATING_TARGET_MIN_PILLAR = 8;
    /** Ground positions within this distance are walking-down goals for a stranded player. */
    static final int DESCENT_RADIUS = 24;
    /** Ground that is not finished yet may still stand this much above the target ground. */
    static final int DESCENT_GROUND_TOLERANCE = 2;
    /** Ground below the target level (a dip not filled yet) also counts; 0.8.89 found none with only the levels above. */
    static final int DESCENT_GROUND_BELOW = 3;
    /** Open cells counted below a bridge block; deeper is treated as this deep. */
    static final int MAX_MEASURED_DROP = 64;
    /** Search limit of a walk-down that rebuilds missing steps of the stair. */
    static final int DESCENT_BRIDGE_VISITS = 20000;

    private ClimbBudgetRules() { }

    static int routeRise(int startY, List<SafeRoutePlanner.RouteStep> steps) {
        int top = startY;
        for (SafeRoutePlanner.RouteStep step : steps) {
            top = Math.max(top, step.position().getY());
        }
        return top - startY;
    }

    static boolean needsReliefBeforeClimb(int rise, int activeScaffolds, int plannedScaffolds, int budget) {
        return rise >= MIN_CLIMB_RISE && plannedScaffolds > 0 && budget > 0
                && activeScaffolds + plannedScaffolds > budget - CLIMB_RESERVE;
    }

    /** Feet stand one block above the finished ground level. */
    static boolean isStranded(int feetY, int targetGroundY, boolean standingOnOwnScaffold) {
        return standingOnOwnScaffold && feetY - (targetGroundY + 1) >= STRANDED_HEIGHT;
    }

    /** Only a route that needs new scaffolds toward a target high above is replaced by a pillar. */
    static boolean preferPillarAccess(int targetRise, int plannedScaffolds) {
        return targetRise >= PILLAR_FIRST_RISE && plannedScaffolds > 0;
    }

    /** Walking down drops at most 3 blocks (no fall damage); with {@code rebuild}, missing steps are placed again. */
    static SafeRoutePlanner.RoutePolicy descentPolicy(boolean rebuild) {
        return new SafeRoutePlanner.RoutePolicy(rebuild, SafeRoutePlanner.InteriorPolicy.ALLOW, Integer.MIN_VALUE, 3);
    }

    static boolean floatingTarget(boolean pillarReady, int shortestPillarBlocks) {
        return pillarReady && shortestPillarBlocks >= FLOATING_TARGET_MIN_PILLAR;
    }

    /** The deepest open drop below any block a route places (bridge or stair step). */
    static int maximumBridgeDrop(List<SafeRoutePlanner.RouteStep> steps, SafeRoutePlanner.RouteEnvironment environment) {
        int worst = 0;
        for (SafeRoutePlanner.RouteStep step : steps) {
            if (step.type() != SafeRoutePlanner.StepType.BRIDGE && step.type() != SafeRoutePlanner.StepType.SCAFFOLD_UP) continue;
            BlockPos below = step.position().below().below();
            int drop = 0;
            while (drop < MAX_MEASURED_DROP && environment.cell(below) == SafeRoutePlanner.CellKind.OPEN) {
                drop++;
                below = below.below();
            }
            worst = Math.max(worst, drop);
        }
        return worst;
    }

    /** A rolling bridge (no headroom: the block behind is taken away) over a drop the player would not survive. */
    static boolean unsafeRollingBridge(int plannedScaffolds, int headroom, int worstDrop, int survivableDrop) {
        return plannedScaffolds > Math.max(0, headroom) && worstDrop > survivableDrop;
    }

    /**
     * A FOUND route whose last stand is on natural ground (not an owned scaffold and not a block the
     * route itself places): when it is done the player stands on real terrain again.
     */
    static boolean completeToNaturalGround(boolean found, List<SafeRoutePlanner.RouteStep> steps,
                                           SafeRoutePlanner.RouteEnvironment environment, Predicate<BlockPos> ownedScaffold) {
        if (!found || steps.isEmpty()) return false;
        SafeRoutePlanner.RouteStep last = steps.getLast();
        if (last.type() == SafeRoutePlanner.StepType.BRIDGE || last.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP) return false;
        BlockPos floor = last.position().below();
        if (ownedScaffold.test(floor) || environment.cell(floor) != SafeRoutePlanner.CellKind.SOLID) return false;
        for (SafeRoutePlanner.RouteStep step : steps) {
            if ((step.type() == SafeRoutePlanner.StepType.BRIDGE || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP)
                    && step.position().below().equals(floor)) return false;
        }
        return true;
    }

    /** Open air this deep below an own scaffold means the player is in the sky, not on terrain or a terrace bridge. */
    static final int SKY_DROP = 16;
    /** A target this far away horizontally is worth landing for first. */
    static final int SKY_BUCKET_FAR = 16;

    /** Open cells straight below a block, up to {@link #MAX_MEASURED_DROP}. */
    static int openDropBelow(BlockPos block, SafeRoutePlanner.RouteEnvironment environment) {
        int drop = 0;
        BlockPos below = block.below();
        while (drop < MAX_MEASURED_DROP && environment.cell(below) == SafeRoutePlanner.CellKind.OPEN) {
            drop++;
            below = below.below();
        }
        return drop;
    }

    /** In the sky with the target below or far away: land with the water bucket before any route search. */
    static boolean skyBucketFirst(BlockPos start, BlockPos target, int dropBelowSupport) {
        if (dropBelowSupport < SKY_DROP) return false;
        boolean below = target.getY() <= start.getY() - STRANDED_HEIGHT;
        boolean far = Math.max(Math.abs(target.getX() - start.getX()), Math.abs(target.getZ() - start.getZ())) > SKY_BUCKET_FAR;
        return below || far;
    }

    /** Standable feet positions on (or just above) the finished ground around a stranded player, nearest first. */
    static List<BlockPos> descentGoals(BlockPos start, int targetGroundY, Predicate<BlockPos> standable) {
        int groundFeet = targetGroundY + 1;
        List<BlockPos> goals = new ArrayList<>();
        for (int dx = -DESCENT_RADIUS; dx <= DESCENT_RADIUS; dx++) {
            for (int dz = -DESCENT_RADIUS; dz <= DESCENT_RADIUS; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > DESCENT_RADIUS) continue;
                for (int y = groundFeet - DESCENT_GROUND_BELOW; y <= groundFeet + DESCENT_GROUND_TOLERANCE && y < start.getY(); y++) {
                    BlockPos feet = new BlockPos(start.getX() + dx, y, start.getZ() + dz);
                    if (standable.test(feet)) {
                        goals.add(feet);
                        break;
                    }
                }
            }
        }
        goals.sort(Comparator.comparingInt((BlockPos feet) -> Math.abs(feet.getX() - start.getX()) + Math.abs(feet.getZ() - start.getZ()))
                .thenComparingInt(BlockPos::getY));
        return goals;
    }
}
