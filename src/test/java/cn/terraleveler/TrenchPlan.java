package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture: the rectangular geometry of the trench job removed in 0.9.04, kept as a
 * ready-made {@link WorkArea} (strict interior, scaffold-permitted ring) for route planner tests.
 */
public final class TrenchPlan implements WorkArea {
    private final BlockPos first;
    private final BlockPos second;
    private final int width;
    private final int excavationTopY;
    private final int wallTopY;
    private final int bottomY;

    public TrenchPlan(BlockPos first, BlockPos second, int width, int excavationTopY, int wallTopY, int bottomY) {
        this.first = first;
        this.second = second;
        this.width = width;
        this.excavationTopY = excavationTopY;
        this.wallTopY = wallTopY;
        this.bottomY = bottomY;
    }

    public int minX() { return Math.min(first.getX(), second.getX()); }
    public int maxX() { return Math.max(first.getX(), second.getX()); }
    public int minZ() { return Math.min(first.getZ(), second.getZ()); }
    public int maxZ() { return Math.max(first.getZ(), second.getZ()); }
    public int topY() { return excavationTopY; }
    public int wallTopY() { return wallTopY; }
    public int bottomY() { return bottomY; }
    public int width() { return width; }

    public boolean isBoundaryXZ(int x, int z) {
        if (x < minX() || x > maxX() || z < minZ() || z > maxZ()) return false;
        return x == minX() || x == maxX() || z == minZ() || z == maxZ();
    }

    public boolean isStrictInteriorXZ(int x, int z) {
        return x > minX() && x < maxX() && z > minZ() && z < maxZ();
    }

    @Override
    public boolean permitsScaffoldAt(int x, int z) {
        if (isBoundaryXZ(x, z)) return true;
        if (isStrictInteriorXZ(x, z)) return false;
        return x >= minX() - 1 && x <= maxX() + 1
                && z >= minZ() - 1 && z <= maxZ() + 1;
    }

    public boolean isExcavationCell(BlockPos pos) {
        return pos.getY() >= bottomY && pos.getY() <= excavationTopY && isBoundaryXZ(pos.getX(), pos.getZ());
    }

    public List<BlockPos> excavationTargets() {
        return excavationTargetsFrom(null);
    }

    /**
     * Returns a ring rotated to the starting column nearest the player. Layers
     * alternate forward/reverse traversal so the last cell of one layer and the
     * first cell of the next share the same X/Z. This keeps the full 3-D work
     * sequence contiguous, including degenerate 1-by-N selections.
     */
    public List<BlockPos> excavationTargetsFrom(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        List<BlockPos> ring = columnsFrom(origin);
        int layer = 0;
        for (int y = excavationTopY; y >= bottomY; y--, layer++) {
            appendLayer(result, ring, y, (layer & 1) != 0);
        }
        return result;
    }

    public long perimeterColumns() {
        int sizeX = maxX() - minX() + 1;
        int sizeZ = maxZ() - minZ() + 1;
        if (sizeX == 1) return sizeZ;
        if (sizeZ == 1) return sizeX;
        return 2L * sizeX + 2L * (sizeZ - 2);
    }

    public long estimatedBlocks() { return perimeterColumns() * Math.max(0, excavationTopY - bottomY + 1L); }

    public List<BlockPos> columns() {
        List<BlockPos> result = new ArrayList<>();
        int loX = minX(), hiX = maxX(), loZ = minZ(), hiZ = maxZ();
        for (int x = loX; x <= hiX; x++) result.add(new BlockPos(x, excavationTopY, loZ));
        for (int z = loZ + 1; z <= hiZ; z++) result.add(new BlockPos(hiX, excavationTopY, z));
        if (hiZ != loZ) for (int x = hiX - 1; x >= loX; x--) result.add(new BlockPos(x, excavationTopY, hiZ));
        if (hiX != loX) for (int z = hiZ - 1; z > loZ; z--) result.add(new BlockPos(loX, excavationTopY, z));
        return result;
    }

    public List<BlockPos> columnsFrom(BlockPos origin) {
        List<BlockPos> ring = columns();
        if (origin == null || ring.size() < 2) return ring;
        int nearest = 0;
        long best = Long.MAX_VALUE;
        for (int index = 0; index < ring.size(); index++) {
            BlockPos column = ring.get(index);
            long dx = (long) column.getX() - origin.getX();
            long dz = (long) column.getZ() - origin.getZ();
            long distance = dx * dx + dz * dz;
            if (distance < best) {
                best = distance;
                nearest = index;
            }
        }
        List<BlockPos> rotated = new ArrayList<>(ring.size());
        for (int offset = 0; offset < ring.size(); offset++) {
            rotated.add(ring.get((nearest + offset) % ring.size()));
        }
        return rotated;
    }

    public List<BlockPos> wallPositions() {
        return wallPositionsFrom(null);
    }

    /** Wall layers use the same contiguous alternating order as excavation layers. */
    public List<BlockPos> wallPositionsFrom(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        List<BlockPos> ring = columnsFrom(origin);
        int layer = 0;
        for (int y = bottomY; y <= wallTopY; y++, layer++) {
            appendLayer(result, ring, y, (layer & 1) != 0);
        }
        return result;
    }

    public long estimatedWallBlocks() {
        return perimeterColumns() * Math.max(0, wallTopY - bottomY + 1L);
    }

    private static void appendLayer(List<BlockPos> result, List<BlockPos> ring,
                                    int y, boolean reverse) {
        for (int offset = 0; offset < ring.size(); offset++) {
            int index = reverse ? ring.size() - 1 - offset : offset;
            BlockPos column = ring.get(index);
            result.add(new BlockPos(column.getX(), y, column.getZ()));
        }
    }
}
