package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IslandSurveyCacheTest {
    @Test
    void storesColumnsIncludingNegativeCoordinates() {
        IslandSurveyCache cache = new IslandSurveyCache();
        cache.ensureScope("server|overworld|62");

        cache.put(-3559, -10145, IslandSurveyCache.LAND);
        cache.put(-3560, -10145, IslandSurveyCache.WATER);

        assertEquals(IslandSurveyCache.LAND, cache.get(-3559, -10145));
        assertEquals(IslandSurveyCache.WATER, cache.get(-3560, -10145));
        assertEquals(IslandSurveyCache.UNKNOWN, cache.get(-3561, -10145));
        assertEquals(IslandSurveyCache.UNKNOWN, cache.get(0, 0));
    }

    @Test
    void unknownIsNeverStoredAndDoesNotOverwrite() {
        IslandSurveyCache cache = new IslandSurveyCache();
        cache.ensureScope("s");
        cache.put(5, 5, IslandSurveyCache.WATER);

        cache.put(5, 5, IslandSurveyCache.UNKNOWN);

        assertEquals(IslandSurveyCache.WATER, cache.get(5, 5));
    }

    @Test
    void chunkCaptureUsesLocalIndexAndRecordsTick() {
        IslandSurveyCache cache = new IslandSurveyCache();
        cache.ensureScope("s");
        byte[] kinds = new byte[256];
        kinds[3 * 16 + 2] = IslandSurveyCache.WATER;

        cache.putChunk(-223, -635, kinds, 40L);

        assertEquals(IslandSurveyCache.WATER, cache.get(-223 * 16 + 2, -635 * 16 + 3));
        assertEquals(IslandSurveyCache.UNKNOWN, cache.get(-223 * 16 + 3, -635 * 16 + 2));
        assertEquals(40L, cache.capturedAt(-223, -635));
        assertEquals(Long.MIN_VALUE, cache.capturedAt(-222, -635));
        assertEquals(1, cache.fullyCapturedChunks());
    }

    @Test
    void changingServerDimensionOrTargetYDiscardsObservations() {
        IslandSurveyCache cache = new IslandSurveyCache();
        assertFalse(cache.ensureScope("server|overworld|62"));
        cache.put(1, 1, IslandSurveyCache.LAND);
        cache.putChunk(3, 3, new byte[256], 1L);

        assertFalse(cache.ensureScope("server|overworld|62"));
        assertEquals(IslandSurveyCache.LAND, cache.get(1, 1));

        assertTrue(cache.ensureScope("server|overworld|70"));
        assertEquals(IslandSurveyCache.UNKNOWN, cache.get(1, 1));
        assertEquals(0, cache.fullyCapturedChunks());
    }

    @Test
    void detectorCompletesAcrossUnloadedChunksWithSurveyRecordsButStopsAtUnsurveyedOnes() {
        // Island x,z in 0..40 ringed by water; the client only holds x < 16 live.
        IslandSurveyCache cache = new IslandSurveyCache();
        cache.ensureScope("s");
        for (int x = -2; x <= 42; x++) {
            for (int z = -2; z <= 42; z++) {
                boolean land = x >= 0 && x <= 40 && z >= 0 && z <= 40;
                cache.put(x, z, land ? IslandSurveyCache.LAND : IslandSurveyCache.WATER);
            }
        }
        IslandFlattenAreaDetector.ColumnReader withSurvey = (x, z) -> x < 16
                ? (x >= 0 && z >= 0 && z <= 40 ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER)
                : IslandColumnClassifier.Classification.fromSurvey(cache.get(x, z)).cell();
        IslandFlattenAreaDetector.ColumnReader liveOnly = (x, z) -> x < 16
                ? withSurvey.read(x, z) : IslandFlattenAreaDetector.Cell.UNLOADED;
        IslandFlattenAreaDetector.Settings settings = new IslandFlattenAreaDetector.Settings(64, 10000, 0, false, false, 4);

        IslandFlattenAreaDetector.Result surveyed = IslandFlattenAreaDetector.detect(5, 5, settings, withSurvey);
        IslandFlattenAreaDetector.Result live = IslandFlattenAreaDetector.detect(5, 5, settings, liveOnly);

        assertEquals(IslandFlattenAreaDetector.Status.READY, surveyed.status());
        assertEquals(41 * 41, surveyed.columns().size());
        assertEquals(IslandFlattenAreaDetector.Status.UNLOADED, live.status());
    }

    @Test
    void surveyRecordsMapToDetectorCellsAndNeverToLiveDetail() {
        IslandColumnClassifier.Classification land =
                IslandColumnClassifier.Classification.fromSurvey(IslandSurveyCache.LAND);
        IslandColumnClassifier.Classification water =
                IslandColumnClassifier.Classification.fromSurvey(IslandSurveyCache.WATER);
        IslandColumnClassifier.Classification unknown =
                IslandColumnClassifier.Classification.fromSurvey(IslandSurveyCache.UNKNOWN);

        assertEquals(IslandFlattenAreaDetector.Cell.LAND, land.cell());
        assertEquals(IslandFlattenAreaDetector.Cell.WATER, water.cell());
        assertEquals(IslandFlattenAreaDetector.Cell.UNLOADED, unknown.cell());
        assertTrue(land.cached());
        assertFalse(unknown.cached());
        assertEquals(IslandSurveyCache.LAND, new IslandColumnClassifier.Classification(
                IslandColumnClassifier.Kind.SUBSURFACE_WATER, 61, 56).surveyKind());
        assertEquals(IslandSurveyCache.UNKNOWN, new IslandColumnClassifier.Classification(
                IslandColumnClassifier.Kind.UNLOADED, 62, Integer.MIN_VALUE).surveyKind());
    }
}
