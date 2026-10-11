package cn.terraleveler;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandColumnClassifierTest {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final int TARGET_Y = 62;

    @Test
    void dryLandAboveCoveredAquiferIsSubsurfaceWaterAndStillLand() {
        // Log case: player at y=62 on grass at 61, aquifer water at 40..44.
        Column column = new Column().solid(MIN_Y, 61).water(40, 44);

        IslandColumnClassifier.Classification result = classify(column, 61);

        assertEquals(IslandColumnClassifier.Kind.SUBSURFACE_WATER, result.kind());
        assertEquals(61, result.surfaceY());
        assertEquals(44, result.subsurfaceWaterY());
        assertEquals(IslandFlattenAreaDetector.Cell.LAND, result.cell());
    }

    @Test
    void plainDryLandIsSurfaceLand() {
        IslandColumnClassifier.Classification result = classify(new Column().solid(MIN_Y, 63), 63);

        assertEquals(IslandColumnClassifier.Kind.SURFACE_LAND, result.kind());
        assertEquals(IslandFlattenAreaDetector.Cell.LAND, result.cell());
    }

    @Test
    void oneBlockShallowWaterIsAnOpenWaterBoundary() {
        Column column = new Column().solid(MIN_Y, 61).water(62, 62);

        IslandColumnClassifier.Classification result = classify(column, 62);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, result.kind());
        assertEquals(IslandFlattenAreaDetector.Cell.WATER, result.cell());
    }

    @Test
    void deepWaterIsAnOpenWaterBoundary() {
        Column column = new Column().solid(MIN_Y, 30).water(31, 62);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, classify(column, 62).kind());
    }

    @Test
    void openWaterFarBelowTargetIsStillOpenWater() {
        Column column = new Column().solid(MIN_Y, 10).water(11, 15);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, classify(column, 15).kind());
    }

    @Test
    void staleLowHeightmapHintStillSeesWaterNearTarget() {
        Column column = new Column().solid(MIN_Y, 55).water(56, 62);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, classify(column, 20).kind());
    }

    @Test
    void canopyOverWaterDoesNotHideTheWaterSurface() {
        Column column = new Column().solid(MIN_Y, 58).water(59, 62)
                .set(66, IslandColumnClassifier.Sample.OVERLAY)
                .set(67, IslandColumnClassifier.Sample.OVERLAY);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, classify(column, 67).kind());
    }

    @Test
    void plantsAndLeavesAboveGroundDoNotCreateWater() {
        Column column = new Column().solid(MIN_Y, 63)
                .set(64, IslandColumnClassifier.Sample.OVERLAY)
                .set(70, IslandColumnClassifier.Sample.OVERLAY);

        assertEquals(IslandColumnClassifier.Kind.SURFACE_LAND, classify(column, 70).kind());
    }

    @Test
    void frozenWaterSurfaceIsOpenWaterButIceOnGroundIsLand() {
        Column frozen = new Column().solid(MIN_Y, 50).water(51, 61).set(62, IslandColumnClassifier.Sample.ICE);
        Column iceOnGround = new Column().solid(MIN_Y, 62).set(63, IslandColumnClassifier.Sample.ICE);

        assertEquals(IslandColumnClassifier.Kind.OPEN_WATER, classify(frozen, 62).kind());
        assertEquals(IslandFlattenAreaDetector.Cell.LAND, classify(iceOnGround, 63).cell());
    }

    @Test
    void voidPlaceholderColumnIsUnloadedNeverLand() {
        Column placeholder = new Column().fill(IslandColumnClassifier.Sample.VOID_AIR);

        IslandColumnClassifier.Classification result = classify(placeholder, MIN_Y - 1);

        assertEquals(IslandColumnClassifier.Kind.UNLOADED, result.kind());
        assertEquals(IslandFlattenAreaDetector.Cell.UNLOADED, result.cell());
    }

    @Test
    void emptyColumnIsUnloaded() {
        assertEquals(IslandColumnClassifier.Kind.UNLOADED, classify(new Column(), MIN_Y - 1).kind());
    }

    @Test
    void seedOnDrySolidSupportIsLandRegardlessOfColumnWater() {
        assertTrue(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.AIR, IslandColumnClassifier.Sample.SOLID));
        assertTrue(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.OVERLAY, IslandColumnClassifier.Sample.SOLID));
        assertFalse(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.WATER, IslandColumnClassifier.Sample.SOLID));
        assertFalse(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.AIR, IslandColumnClassifier.Sample.AIR));
        assertFalse(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.AIR, IslandColumnClassifier.Sample.WATER));
        assertFalse(IslandColumnClassifier.seedStandsOnLand(
                IslandColumnClassifier.Sample.AIR, IslandColumnClassifier.Sample.ICE));
    }

    @Test
    void islandOverAquiferIsDetectedAndShallowRingBoundsIt() {
        // 5x5 island whose every column has a covered aquifer, ringed by a
        // one-block-deep shallow shelf; outside the shelf is deep ocean.
        IslandFlattenAreaDetector.ColumnReader reader = (x, z) -> {
            Column column;
            if (x >= 0 && x <= 4 && z >= 0 && z <= 4) {
                column = new Column().solid(MIN_Y, 62).water(35, 45);
            } else if (x >= -1 && x <= 5 && z >= -1 && z <= 5) {
                column = new Column().solid(MIN_Y, 61).water(62, 62);
            } else {
                column = new Column().solid(MIN_Y, 30).water(31, 62);
            }
            return classify(column, 62).cell();
        };

        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(2, 2,
                new IslandFlattenAreaDetector.Settings(32, 10000, 0, false, false, 4), reader);

        assertEquals(IslandFlattenAreaDetector.Status.READY, result.status());
        assertEquals(25, result.connectedLandColumns());
    }

    @Test
    void unloadedPlaceholderRingStopsTheScanInsteadOfReachingTheRadius() {
        IslandFlattenAreaDetector.ColumnReader reader = (x, z) -> {
            Column column = Math.abs(x) <= 3 && Math.abs(z) <= 3
                    ? new Column().solid(MIN_Y, 62)
                    : new Column().fill(IslandColumnClassifier.Sample.VOID_AIR);
            return classify(column, 62).cell();
        };

        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(0, 0,
                new IslandFlattenAreaDetector.Settings(16, 10000, 0, false, false, 4), reader);

        assertEquals(IslandFlattenAreaDetector.Status.UNLOADED, result.status());
    }

    private static IslandColumnClassifier.Classification classify(Column column, int surfaceHint) {
        return IslandColumnClassifier.classify(surfaceHint, TARGET_Y, MIN_Y, MAX_Y, column::at);
    }

    private static final class Column {
        private final Map<Integer, IslandColumnClassifier.Sample> samples = new HashMap<>();
        private IslandColumnClassifier.Sample fallback = IslandColumnClassifier.Sample.AIR;

        Column solid(int from, int to) { return range(from, to, IslandColumnClassifier.Sample.SOLID); }
        Column water(int from, int to) { return range(from, to, IslandColumnClassifier.Sample.WATER); }
        Column set(int y, IslandColumnClassifier.Sample sample) { samples.put(y, sample); return this; }
        Column fill(IslandColumnClassifier.Sample sample) { fallback = sample; return this; }

        private Column range(int from, int to, IslandColumnClassifier.Sample sample) {
            for (int y = from; y <= to; y++) samples.put(y, sample);
            return this;
        }

        IslandColumnClassifier.Sample at(int y) {
            return samples.getOrDefault(y, fallback);
        }
    }
}
