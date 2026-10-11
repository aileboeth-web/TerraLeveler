package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

final class FoliageAccessPlanner {
    private static final int ACCESS_RADIUS = 3;

    private FoliageAccessPlanner() { }

    static Optional<BlockPos> select(BlockPos task, BlockPos player, Collection<BlockPos> deferred,
                                     Predicate<BlockPos> stillLeaf, Predicate<BlockPos> protectedSupport) {
        if (task == null || deferred == null || deferred.isEmpty()) return Optional.empty();
        BlockPos reference = player == null ? task : player;
        return deferred.stream()
                .filter(position -> !position.equals(task))
                .filter(position -> withinAccessRadius(task, position))
                .filter(stillLeaf)
                .filter(position -> !protectedSupport.test(position))
                .min(Comparator.comparingLong((BlockPos position) -> distanceSquared(position, task))
                        .thenComparingLong(position -> distanceSquared(position, reference))
                        .thenComparing(Comparator.<BlockPos>comparingInt(position -> position.getY()).reversed()))
                .map(BlockPos::immutable);
    }

    static boolean withinAccessRadius(BlockPos task, BlockPos foliage) {
        return Math.abs(task.getX() - foliage.getX()) <= ACCESS_RADIUS
                && Math.abs(task.getY() - foliage.getY()) <= ACCESS_RADIUS
                && Math.abs(task.getZ() - foliage.getZ()) <= ACCESS_RADIUS;
    }

    private static long distanceSquared(BlockPos first, BlockPos second) {
        long dx = first.getX() - second.getX();
        long dy = first.getY() - second.getY();
        long dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
