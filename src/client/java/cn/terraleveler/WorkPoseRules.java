package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Shared geometry for route goals and the action preconditions they serve. */
final class WorkPoseRules {
    static final double EYE_HEIGHT = 1.62;
    // Vanilla survival block interaction range is 4.5 blocks.  Using the old
    // sqrt(17) centre-distance rejected a legitimate pose two blocks above a
    // water plug's lower support (eye-to-centre distance about 4.24), leaving
    // bounded containment with no candidate stand before route planning.
    static final double SAFE_REACH = 4.5;
    static final double SAFE_REACH_SQUARED = SAFE_REACH * SAFE_REACH;

    /** Fill lookahead (0.9.10): how many upcoming surface cells a stand position is judged against. */
    static final int FILL_LOOKAHEAD_TARGETS = 12;
    /** Conservative reach for the lookahead, half a block inside the real interaction range. */
    static final double FILL_LOOKAHEAD_REACH_SQUARED = 4.0 * 4.0;
    /** Only bother choosing when a stand position reaches at least this many upcoming cells. */
    static final int FILL_LOOKAHEAD_MIN_USEFUL = 3;

    private WorkPoseRules() { }

    /**
     * Upcoming surface cells within reach of a stand position, or -1 when the position
     * stands in one of them (it would have to move again to place that cell).
     */
    static int coveredTargets(BlockPos feet, java.util.List<BlockPos> targets, double reachSquared) {
        double eyeX = feet.getX() + 0.5;
        double eyeY = feet.getY() + EYE_HEIGHT;
        double eyeZ = feet.getZ() + 0.5;
        int count = 0;
        for (BlockPos target : targets) {
            if (target.equals(feet) || target.equals(feet.above())) return -1;
            double dx = target.getX() + 0.5 - eyeX;
            double dy = target.getY() + 0.5 - eyeY;
            double dz = target.getZ() + 0.5 - eyeZ;
            if (dx * dx + dy * dy + dz * dz <= reachSquared) count++;
        }
        return count;
    }

    /**
     * Which stand positions to offer the route planner while filling: those reaching nearly
     * as many upcoming cells as the best one (best - 1 or more). Null means "all of them",
     * when no position reaches enough cells to make the choice worth it.
     */
    static java.util.List<Integer> lookaheadChoice(int[] covered, int minimumUseful) {
        int best = -1;
        for (int value : covered) best = Math.max(best, value);
        if (best < minimumUseful) return null;
        java.util.List<Integer> chosen = new java.util.ArrayList<>();
        for (int index = 0; index < covered.length; index++) {
            if (covered[index] >= best - 1) chosen.add(index);
        }
        return chosen;
    }

    static boolean breakAllowed(BlockPos feet, BlockPos target) {
        return !feet.below().equals(target) && canReach(feet, target);
    }

    static boolean placementAllowed(BlockPos feet, BlockPos target, BlockPos support) {
        return !bodyAt(feet).intersects(blockBox(target)) && canReach(feet, support);
    }

    static boolean fluidDeckPose(BlockPos feet, int waterLayerY) {
        // SafeRoutePlanner.canOccupy has already proved that the feet/head
        // cells are dry and that the pose has a stable floor.  A dry bank at
        // the same Y as the local water layer is therefore a valid staging
        // pose. Requiring one extra block of height stranded recovery on the
        // upper bank after a short reflow, even though the adjacent same-level
        // stone shelf was dry and reachable. Still reject every pose below the
        // interface so containment never routes into a submerged cavity.
        return feet.getY() >= waterLayerY;
    }

    static int maximumScanFeetY(BlockPos taskTarget, BlockPos interactionTarget, boolean placement) {
        int normalMaximum = taskTarget.getY() + 2;
        return placement ? Math.max(normalMaximum, interactionTarget.getY() + 3) : normalMaximum;
    }

    static boolean canReach(BlockPos feet, BlockPos target) {
        double dx = target.getX() + .5 - (feet.getX() + .5);
        double dy = target.getY() + .5 - (feet.getY() + EYE_HEIGHT);
        double dz = target.getZ() + .5 - (feet.getZ() + .5);
        return dx * dx + dy * dy + dz * dz <= SAFE_REACH_SQUARED;
    }

    private static AABB bodyAt(BlockPos feet) {
        return new AABB(feet.getX() + .2, feet.getY(), feet.getZ() + .2,
                feet.getX() + .8, feet.getY() + 1.8, feet.getZ() + .8);
    }

    private static AABB blockBox(BlockPos target) {
        return new AABB(target.getX(), target.getY(), target.getZ(),
                target.getX() + 1.0, target.getY() + 1.0, target.getZ() + 1.0);
    }
}
