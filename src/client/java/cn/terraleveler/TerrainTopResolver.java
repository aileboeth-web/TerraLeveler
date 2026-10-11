package cn.terraleveler;

/** Converts a heightmap reading into the Y of the top terrain block. */
final class TerrainTopResolver {
    private TerrainTopResolver() { }

    /** Heightmap#getHeight returns the first air block above the terrain, hence the -1. */
    static int surfaceBlockY(int heightmapFirstAirY) {
        return heightmapFirstAirY - 1;
    }
}
