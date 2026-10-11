package cn.terraleveler;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandFlattenAreaDetectorTest {
    @Test
    void preservesInteriorPondUnlessTheDedicatedSwitchIsEnabled() {
        Set<Long> land = rectangle(0, 6, 0, 6);
        land.remove(key(3, 3));

        IslandFlattenAreaDetector.Result preserved = detect(land, false, false, 0, 4);
        IslandFlattenAreaDetector.Result filled = detect(land, true, false, 0, 4);

        assertTrue(preserved.ready());
        assertFalse(preserved.columns().contains(column(3, 3)));
        assertTrue(filled.columns().contains(column(3, 3)));
        assertEquals(1, filled.includedInteriorWaterColumns());
    }

    @Test
    void shoreInsetRemovesOnlyColumnsTouchingExternalWater() {
        Set<Long> land = rectangle(0, 4, 0, 4);
        IslandFlattenAreaDetector.Result result = detect(land, false, false, 1, 4);

        assertEquals(9, result.columns().size());
        assertTrue(result.columns().contains(column(2, 2)));
        assertFalse(result.columns().contains(column(0, 2)));
        assertEquals(16, result.shoreExcludedColumns());
    }

    @Test
    void cutsAOneBlockLandBridgeBetweenWaterBanks() {
        Set<Long> land = rectangle(0, 4, 0, 4);
        land.add(key(5, 2));
        land.addAll(rectangle(6, 10, 0, 4));

        IslandFlattenAreaDetector.Result result = detect(land, false, true, 0, 2);

        assertTrue(result.ready());
        assertTrue(result.columns().contains(column(2, 2)));
        assertFalse(result.columns().contains(column(8, 2)));
        assertTrue(result.narrowConnectionsCut() > 0);
    }

    @Test
    void refusesOpenLandThatReachesTheMaximumRadius() {
        Set<Long> land = rectangle(-12, 12, -12, 12);
        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(0, 0,
                settings(false, false, 0, 4, 8), reader(land));

        assertEquals(IslandFlattenAreaDetector.Status.OPEN_BOUNDARY, result.status());
    }

    @Test
    void unloadedFailureReportsTheLandPathAndTheNarrowestCrossing() {
        Set<Long> land = rectangle(0, 4, 0, 4);
        land.addAll(rectangle(5, 15, 1, 3));
        IslandFlattenAreaDetector.ColumnReader reader = (x, z) -> x >= 16
                ? IslandFlattenAreaDetector.Cell.UNLOADED
                : land.contains(key(x, z)) ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER;

        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(2, 2,
                settings(false, false, 0, 4, 32), reader);

        assertEquals(IslandFlattenAreaDetector.Status.UNLOADED, result.status());
        assertEquals(column(16, 2), result.problemPos());
        assertTrue(result.diagnostics().contains("seed=2,2 problem=16,2 offset=14,0 pathLength=14 manhattan=14"),
                result.diagnostics());
        assertTrue(result.diagnostics().contains("runs=[2,2->15,2 n=14 LAND]"), result.diagnostics());
        assertTrue(result.diagnostics().contains("narrowest=[")
                && result.diagnostics().contains("width=3 ends=WATER/WATER"), result.diagnostics());
    }

    @Test
    void crossingsMeasureLandWidthPerpendicularToThePath() {
        Set<Long> land = rectangle(0, 10, -2, 2);
        IslandFlattenAreaDetector.ColumnReader reader = reader(land);
        java.util.List<Long> path = java.util.List.of(key(3, 0), key(4, 0), key(5, 0));

        java.util.List<IslandFlattenAreaDetector.Crossing> crossings = IslandFlattenAreaDetector.crossings(path, reader);

        assertEquals(2, crossings.size());
        assertEquals(5, crossings.get(0).width());
        assertEquals(IslandFlattenAreaDetector.Cell.WATER, crossings.get(0).firstEnd());
        assertEquals(IslandFlattenAreaDetector.Cell.WATER, crossings.get(0).secondEnd());
    }

    @Test
    void landPathFollowsParentsBackToTheSeed() {
        java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        parent.put(key(1, 0), key(0, 0));
        parent.put(key(1, 1), key(1, 0));

        assertEquals(java.util.List.of(key(0, 0), key(1, 0), key(1, 1)),
                IslandFlattenAreaDetector.landPath(key(1, 1), parent));
    }

    @Test
    void readyResultCarriesNoFailureDiagnostics() {
        IslandFlattenAreaDetector.Result result = detect(rectangle(0, 4, 0, 4), false, false, 0, 4);

        assertTrue(result.ready());
        assertEquals("", result.diagnostics());
    }

    private static IslandFlattenAreaDetector.Result detect(Set<Long> land, boolean fillWater,
                                                            boolean cutNeck, int inset, int neckWidth) {
        return IslandFlattenAreaDetector.detect(2, 2,
                settings(fillWater, cutNeck, inset, neckWidth, 32), reader(land));
    }

    private static IslandFlattenAreaDetector.Settings settings(boolean fillWater, boolean cutNeck,
                                                                int inset, int neckWidth, int radius) {
        return new IslandFlattenAreaDetector.Settings(radius, 10000, inset, fillWater, cutNeck, neckWidth);
    }

    private static IslandFlattenAreaDetector.ColumnReader reader(Set<Long> land) {
        return (x, z) -> land.contains(key(x, z))
                ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER;
    }

    private static Set<Long> rectangle(int minX, int maxX, int minZ, int maxZ) {
        Set<Long> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) result.add(key(x, z));
        return result;
    }

    private static net.minecraft.core.BlockPos column(int x, int z) {
        return new net.minecraft.core.BlockPos(x, 0, z);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
