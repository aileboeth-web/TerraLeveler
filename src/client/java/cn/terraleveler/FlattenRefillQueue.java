package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Maintains bottom-up dependencies when flatten escape temporarily removes natural support. */
final class FlattenRefillQueue {
    private FlattenRefillQueue() { }

    static boolean shouldRestoreEscapeBlock(BlockPos position, int targetGroundY) {
        Objects.requireNonNull(position, "position");
        return position.getY() == targetGroundY;
    }

    static boolean insertBeforeHigherLayers(List<BlockPos> targets, int currentIndex, BlockPos refill) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(refill, "refill");
        if (targets.contains(refill)) return false;

        int insertion = Math.min(Math.max(currentIndex + 1, 0), targets.size());
        while (insertion < targets.size() && targets.get(insertion).getY() <= refill.getY()) {
            insertion++;
        }
        targets.add(insertion, refill.immutable());
        return true;
    }

    static boolean promotePendingSupport(List<BlockPos> targets, int currentIndex, BlockPos support) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(support, "support");
        if (currentIndex < 0 || currentIndex >= targets.size()) return false;

        for (int index = currentIndex + 1; index < targets.size(); index++) {
            if (!targets.get(index).equals(support)) continue;
            BlockPos pending = targets.remove(index);
            targets.add(currentIndex, pending);
            return true;
        }
        return false;
    }

    /** Moves the nearest currently placeable surface cell to the active index. */
    static int promoteNearestSupported(List<BlockPos> targets, int currentIndex, BlockPos origin,
                                       Predicate<BlockPos> supported) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(supported, "supported");
        if (currentIndex < 0 || currentIndex >= targets.size()) return -1;

        // Keep a placeable active target stable until the controller finishes it.
        // Re-selecting the nearest target every tick can make two adjacent cells
        // endlessly swap as the player moves between them.
        if (supported.test(targets.get(currentIndex))) return currentIndex;

        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = currentIndex + 1; index < targets.size(); index++) {
            BlockPos candidate = targets.get(index);
            if (!supported.test(candidate)) continue;
            long dx = (long) candidate.getX() - origin.getX();
            long dz = (long) candidate.getZ() - origin.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestIndex = index;
                bestDistance = distance;
            }
        }
        if (bestIndex < 0) return -1;
        if (bestIndex != currentIndex) {
            BlockPos selected = targets.remove(bestIndex);
            targets.add(currentIndex, selected);
        }
        return bestIndex;
    }

    /** Horizontal radius checked for cells placeable without moving; reach is 4.5 blocks. */
    static final int PLACEABLE_HERE_RADIUS = 5;

    /**
     * When the active cell cannot be placed from where the player stands (usually the player
     * stands in it), moves the nearest later cell that can be to the active index, so every
     * cell within reach is placed before walking (0.9.11). Returns the promoted cell's old
     * index, or -1 when none is placeable here and the player has to move.
     */
    static int promoteNearestPlaceableHere(List<BlockPos> targets, int currentIndex, BlockPos origin,
                                           Predicate<BlockPos> placeableHere) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(placeableHere, "placeableHere");
        if (currentIndex < 0 || currentIndex >= targets.size()) return -1;

        long radiusSquared = (long) PLACEABLE_HERE_RADIUS * PLACEABLE_HERE_RADIUS;
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = currentIndex + 1; index < targets.size(); index++) {
            BlockPos candidate = targets.get(index);
            long dx = (long) candidate.getX() - origin.getX();
            long dz = (long) candidate.getZ() - origin.getZ();
            long distance = dx * dx + dz * dz;
            if (distance > radiusSquared || distance >= bestDistance) continue;
            if (!placeableHere.test(candidate)) continue;
            bestIndex = index;
            bestDistance = distance;
        }
        if (bestIndex < 0) return -1;
        BlockPos selected = targets.remove(bestIndex);
        targets.add(currentIndex, selected);
        return bestIndex;
    }
}
