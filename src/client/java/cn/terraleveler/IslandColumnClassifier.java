package cn.terraleveler;

/**
 * World-independent island column classification.
 *
 * <p>A column is classified from its first significant block seen from above,
 * not from "any water somewhere in a height window". Covered aquifers and cave
 * water are reported as {@link Kind#SUBSURFACE_WATER} and behave as land for
 * island connectivity; only water that is the column's exposed surface (shallow
 * or deep) is an open-water boundary. Client placeholder data is never guessed.
 */
final class IslandColumnClassifier {
    enum Kind { SURFACE_LAND, OPEN_WATER, SUBSURFACE_WATER, UNLOADED }

    /** Reduced block sample; the Minecraft adapter lives in {@link FlattenPlanner}. */
    enum Sample {
        /** Ordinary air. */
        AIR,
        /** Leaves, lily pads and collision-free plants/snow that do not form a surface. */
        OVERLAY,
        /** Any block whose fluid state is water, including waterlogged blocks. */
        WATER,
        /** Ice that may be a frozen water surface. */
        ICE,
        /** A block with collision that forms ground. */
        SOLID,
        /** Client placeholder block for missing terrain data. */
        VOID_AIR
    }

    @FunctionalInterface
    interface SampleReader {
        Sample at(int y);
    }

    /** {@code cached}: taken from {@link IslandSurveyCache} because the chunk is no longer loaded. */
    record Classification(Kind kind, int surfaceY, int subsurfaceWaterY, boolean cached) {
        Classification(Kind kind, int surfaceY, int subsurfaceWaterY) {
            this(kind, surfaceY, subsurfaceWaterY, false);
        }

        byte surveyKind() {
            return switch (kind) {
                case SURFACE_LAND, SUBSURFACE_WATER -> IslandSurveyCache.LAND;
                case OPEN_WATER -> IslandSurveyCache.WATER;
                case UNLOADED -> IslandSurveyCache.UNKNOWN;
            };
        }

        static Classification fromSurvey(byte kind) {
            return switch (kind) {
                case IslandSurveyCache.LAND -> new Classification(Kind.SURFACE_LAND, NONE, NONE, true);
                case IslandSurveyCache.WATER -> new Classification(Kind.OPEN_WATER, NONE, NONE, true);
                default -> new Classification(Kind.UNLOADED, NONE, NONE, false);
            };
        }

        IslandFlattenAreaDetector.Cell cell() {
            return switch (kind) {
                case SURFACE_LAND, SUBSURFACE_WATER -> IslandFlattenAreaDetector.Cell.LAND;
                case OPEN_WATER -> IslandFlattenAreaDetector.Cell.WATER;
                case UNLOADED -> IslandFlattenAreaDetector.Cell.UNLOADED;
            };
        }
    }

    /** Covered water is probed this far below the target Y for diagnostics. */
    static final int SUBSURFACE_PROBE_BELOW_TARGET = 32;
    /** The downward scan always starts at least this far above the target Y. */
    static final int SCAN_START_ABOVE_TARGET = 16;

    private static final int NONE = Integer.MIN_VALUE;

    private IslandColumnClassifier() { }

    /**
     * @param surfaceHint client WORLD_SURFACE top block, used only to start higher when needed
     */
    static Classification classify(int surfaceHint, int targetY, int minY, int maxY, SampleReader reader) {
        int top = Math.min(maxY - 1, Math.max(surfaceHint, targetY + SCAN_START_ABOVE_TARGET));
        for (int y = top; y >= minY; y--) {
            Sample sample = reader.at(y);
            switch (sample) {
                case AIR, OVERLAY -> { }
                case VOID_AIR -> {
                    return new Classification(Kind.UNLOADED, y, NONE);
                }
                case WATER -> {
                    return new Classification(Kind.OPEN_WATER, y, NONE);
                }
                case ICE -> {
                    if (y - 1 >= minY && reader.at(y - 1) == Sample.WATER) {
                        return new Classification(Kind.OPEN_WATER, y, NONE);
                    }
                    return land(y, targetY, minY, reader);
                }
                case SOLID -> {
                    return land(y, targetY, minY, reader);
                }
            }
        }
        // Loaded columns always have terrain above the world bottom; an empty
        // column means the client has no usable data for it.
        return new Classification(Kind.UNLOADED, minY, NONE);
    }

    /**
     * The player's own support decides the seed: a player standing dry on a
     * solid block is on land, whatever water lies below or above that column.
     */
    static boolean seedStandsOnLand(Sample feet, Sample support) {
        return feet != Sample.WATER && feet != Sample.VOID_AIR && support == Sample.SOLID;
    }

    private static Classification land(int surfaceY, int targetY, int minY, SampleReader reader) {
        int bottom = Math.max(minY, targetY - SUBSURFACE_PROBE_BELOW_TARGET);
        for (int y = surfaceY - 1; y >= bottom; y--) {
            Sample sample = reader.at(y);
            if (sample == Sample.VOID_AIR) return new Classification(Kind.UNLOADED, y, NONE);
            if (sample == Sample.WATER) return new Classification(Kind.SUBSURFACE_WATER, surfaceY, y);
        }
        return new Classification(Kind.SURFACE_LAND, surfaceY, NONE);
    }

    static String describe(Classification classification) {
        if (classification.cached()) return classification.kind() + " cached=true";
        return classification.kind()
                + " surfaceY=" + classification.surfaceY()
                + (classification.subsurfaceWaterY() == NONE ? "" : " subsurfaceWaterY=" + classification.subsurfaceWaterY());
    }
}
