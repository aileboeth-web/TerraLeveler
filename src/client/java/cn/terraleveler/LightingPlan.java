package cn.terraleveler;

import net.minecraft.core.BlockPos;

public record LightingPlan(BlockPos first, BlockPos second) implements WorkArea {
    public LightingPlan {
        first = first.immutable();
        second = second.immutable();
    }

    @Override public int minX() { return Math.min(first.getX(), second.getX()); }
    @Override public int maxX() { return Math.max(first.getX(), second.getX()); }
    public int minY() { return Math.min(first.getY(), second.getY()); }
    public int maxY() { return Math.max(first.getY(), second.getY()); }
    @Override public int minZ() { return Math.min(first.getZ(), second.getZ()); }
    @Override public int maxZ() { return Math.max(first.getZ(), second.getZ()); }

    public long volume() {
        return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
    }

    @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
    @Override public boolean permitsScaffoldAt(int x, int z) { return false; }
}
