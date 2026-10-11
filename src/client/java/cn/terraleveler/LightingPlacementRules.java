package cn.terraleveler;

import net.minecraft.core.BlockPos;

final class LightingPlacementRules {
    private LightingPlacementRules() { }

    static boolean needsTorch(boolean targetAir, boolean torchSurvives, boolean safeSupport,
                              int blockLight, int maximumBlockLight) {
        return targetAir && torchSurvives && safeSupport && blockLight <= maximumBlockLight;
    }

    static BlockPos targetAboveScannedSupport(BlockPos support) {
        return support.above().immutable();
    }
}
