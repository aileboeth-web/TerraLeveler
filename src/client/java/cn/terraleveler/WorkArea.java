package cn.terraleveler;

/** Geometry required by safe movement, independent of the job performed in it. */
public interface WorkArea {
    int minX();
    int maxX();
    int minZ();
    int maxZ();

    default boolean containsColumn(int x, int z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /** Areas that ordinary routes may not cross except for their terminal work pose. */
    boolean isStrictInteriorXZ(int x, int z);

    /** Cells where temporary route scaffolds may be owned by TerraLeveler. */
    boolean permitsScaffoldAt(int x, int z);
}
