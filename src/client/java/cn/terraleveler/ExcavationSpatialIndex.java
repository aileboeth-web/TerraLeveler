package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable-by-rebuild spatial lookup for planned excavation targets. */
final class ExcavationSpatialIndex {
    private static final int CELL_SIZE = 4;

    private final Map<Cell, List<BlockPos>> cells = new HashMap<>();
    private int targetCount;

    void rebuild(List<BlockPos> targets) {
        cells.clear();
        targetCount = 0;
        if (targets == null) return;
        for (BlockPos target : targets) {
            if (target == null) continue;
            BlockPos immutable = target.immutable();
            cells.computeIfAbsent(cell(immutable), ignored -> new ArrayList<>()).add(immutable);
            targetCount++;
        }
    }

    List<BlockPos> within(Vec3 center, double radius) {
        if (center == null || radius < 0.0 || cells.isEmpty()) return List.of();
        int minX = cellCoordinate(center.x - radius);
        int minY = cellCoordinate(center.y - radius);
        int minZ = cellCoordinate(center.z - radius);
        int maxX = cellCoordinate(center.x + radius);
        int maxY = cellCoordinate(center.y + radius);
        int maxZ = cellCoordinate(center.z + radius);
        double radiusSquared = radius * radius;
        List<BlockPos> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    List<BlockPos> bucket = cells.get(new Cell(x, y, z));
                    if (bucket == null) continue;
                    for (BlockPos target : bucket) {
                        if (center.distanceToSqr(Vec3.atCenterOf(target)) <= radiusSquared) {
                            result.add(target);
                        }
                    }
                }
            }
        }
        return result;
    }

    int size() {
        return targetCount;
    }

    private static Cell cell(BlockPos pos) {
        return new Cell(Math.floorDiv(pos.getX(), CELL_SIZE),
                Math.floorDiv(pos.getY(), CELL_SIZE),
                Math.floorDiv(pos.getZ(), CELL_SIZE));
    }

    private static int cellCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL_SIZE);
    }

    private record Cell(int x, int y, int z) { }
}
