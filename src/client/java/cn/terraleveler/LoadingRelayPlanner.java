package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.Set;

/** Chooses a loaded, safe intermediate stand position toward an unloaded task. */
public final class LoadingRelayPlanner {
    public interface Environment {
        boolean loaded(BlockPos position);
        boolean safeStand(BlockPos feet);
    }

    private LoadingRelayPlanner() { }

    public static Optional<BlockPos> choose(Environment environment, BlockPos origin, BlockPos target,
                                            WorkArea trench, int maximumDistance) {
        return choose(environment, origin, target, trench, maximumDistance, Set.of());
    }

    public static Optional<BlockPos> choose(Environment environment, BlockPos origin, BlockPos target,
                                            WorkArea trench, int maximumDistance,
                                            Set<BlockPos> excluded) {
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0 || maximumDistance < 1) return Optional.empty();
        int limit = Math.min(maximumDistance, Math.max(1, (int) Math.ceil(length) - 1));
        double originDistanceSquared = dx * dx + dz * dz;
        for (int distance = limit; distance >= 1; distance--) {
            int x = (int) Math.round(origin.getX() + dx / length * distance);
            int z = (int) Math.round(origin.getZ() + dz / length * distance);
            if (x == origin.getX() && z == origin.getZ()) continue;
            if (x == target.getX() && z == target.getZ()) continue;
            double remainingX = target.getX() - x;
            double remainingZ = target.getZ() - z;
            if (remainingX * remainingX + remainingZ * remainingZ >= originDistanceSquared) continue;
            if (trench.isStrictInteriorXZ(x, z)) continue;
            // Prefer the player's current elevation, then expand vertically.
            // Scanning +5 downwards made an isolated high ledge permanently
            // shadow a same-level relay which the route planner could reach.
            BlockPos level = new BlockPos(x, origin.getY(), z);
            if (usable(environment, level, excluded)) return Optional.of(level.immutable());
            for (int offset = 1; offset <= 10; offset++) {
                if (offset <= 5) {
                    BlockPos above = new BlockPos(x, origin.getY() + offset, z);
                    if (usable(environment, above, excluded)) return Optional.of(above.immutable());
                }
                BlockPos below = new BlockPos(x, origin.getY() - offset, z);
                if (usable(environment, below, excluded)) return Optional.of(below.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean usable(Environment environment, BlockPos candidate, Set<BlockPos> excluded) {
        return !excluded.contains(candidate)
                && environment.loaded(candidate) && environment.safeStand(candidate);
    }
}
