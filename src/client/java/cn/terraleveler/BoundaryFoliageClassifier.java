package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Separates foliage connected to an in-selection log from canopy entering across the selection boundary. */
final class BoundaryFoliageClassifier {
    record Result(Set<BlockPos> interior, Set<BlockPos> boundary) {
        Result {
            interior = Set.copyOf(interior);
            boundary = Set.copyOf(boundary);
        }
    }

    private BoundaryFoliageClassifier() { }

    static Result classify(List<BlockPos> logs, List<BlockPos> foliage) {
        Set<BlockPos> leafTargets = immutableSet(foliage);
        if (leafTargets.isEmpty()) return new Result(Set.of(), Set.of());
        Set<BlockPos> traversable = new LinkedHashSet<>(leafTargets);
        Set<BlockPos> logTargets = immutableSet(logs);
        traversable.addAll(logTargets);

        Set<BlockPos> visited = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos log : logTargets) {
            if (visited.add(log)) queue.addLast(log);
        }
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbour = current.offset(dx, dy, dz);
                        if (traversable.contains(neighbour) && visited.add(neighbour)) {
                            queue.addLast(neighbour.immutable());
                        }
                    }
                }
            }
        }

        Set<BlockPos> interior = new LinkedHashSet<>(leafTargets);
        interior.retainAll(visited);
        Set<BlockPos> boundary = new LinkedHashSet<>(leafTargets);
        boundary.removeAll(interior);
        return new Result(interior, boundary);
    }

    static List<BlockPos> cleanupTargets(List<BlockPos> currentFoliage,
                                         Set<BlockPos> initialInterior,
                                         boolean cleanBoundary) {
        if (currentFoliage == null || currentFoliage.isEmpty()) return List.of();
        if (!cleanBoundary) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos target : currentFoliage) {
            if (target != null) result.add(target.immutable());
        }
        return List.copyOf(result);
    }

    private static Set<BlockPos> immutableSet(List<BlockPos> positions) {
        Set<BlockPos> result = new LinkedHashSet<>();
        if (positions == null) return result;
        for (BlockPos position : positions) {
            if (position != null) result.add(position.immutable());
        }
        return result;
    }
}
