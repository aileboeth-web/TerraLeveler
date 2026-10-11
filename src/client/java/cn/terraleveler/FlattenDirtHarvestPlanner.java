package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Plans a bounded soil cut above the flatten target when dirt runs out. */
final class FlattenDirtHarvestPlanner {
    enum CellKind {
        AIR,
        DIRT_SOURCE,
        SOIL_OVERBURDEN,
        BLOCKED
    }

    record Plan(List<BlockPos> targets, int expectedDirt) {
        Plan {
            targets = List.copyOf(targets);
        }
    }

    private record Column(List<BlockPos> targets, int dirtSources, long distanceSquared) { }

    private FlattenDirtHarvestPlanner() { }

    /**
     * Selects nearby columns that can be opened from above without touching a
     * non-soil block. Every returned target is strictly above targetGroundY.
     */
    static Plan plan(WorkArea area, int targetGroundY, int topY, BlockPos origin,
                     int desiredDirt, Function<BlockPos, CellKind> cells) {
        if (topY <= targetGroundY || desiredDirt <= 0) return new Plan(List.of(), 0);

        List<Column> columns = new ArrayList<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (!area.containsColumn(x, z)) continue;
                List<BlockPos> accessibleSoil = new ArrayList<>();
                int sources = 0;
                int deepestSourceIndex = -1;
                for (int y = topY; y > targetGroundY; y--) {
                    BlockPos position = new BlockPos(x, y, z);
                    CellKind kind = cells.apply(position);
                    if (kind == CellKind.AIR) continue;
                    if (kind == CellKind.BLOCKED) {
                        // A protected, fluid, unloaded, or non-soil cap means
                        // this column is not a safe top-down material source.
                        // Once dirt has already been reached, a rock floor
                        // below it is simply the safe end of this harvest.
                        if (sources == 0) {
                            accessibleSoil.clear();
                            deepestSourceIndex = -1;
                        }
                        break;
                    }
                    accessibleSoil.add(position);
                    if (kind == CellKind.DIRT_SOURCE) {
                        sources++;
                        deepestSourceIndex = accessibleSoil.size() - 1;
                    }
                }
                if (sources == 0) continue;
                List<BlockPos> targets = List.copyOf(
                        accessibleSoil.subList(0, deepestSourceIndex + 1));
                long dx = origin == null ? 0L : (long) x - origin.getX();
                long dz = origin == null ? 0L : (long) z - origin.getZ();
                columns.add(new Column(targets, sources, dx * dx + dz * dz));
            }
        }

        columns.sort(Comparator.comparingLong(Column::distanceSquared)
                .thenComparing(Comparator.comparingInt(Column::dirtSources).reversed()));
        List<BlockPos> targets = new ArrayList<>();
        int expected = 0;
        for (Column column : columns) {
            targets.addAll(column.targets());
            expected += column.dirtSources();
            if (expected >= desiredDirt) break;
        }
        return new Plan(targets, expected);
    }
}
