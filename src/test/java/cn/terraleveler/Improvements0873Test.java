package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Rules added in 0.8.73: cluster pillar cleanup, resumable island scans, gap summary, survey persistence. */
class Improvements0873Test {
    private static final IslandFlattenAreaDetector.Settings SETTINGS =
            new IslandFlattenAreaDetector.Settings(64, 100000, 0, false, false, 4);

    /** 41x41 island around 0,0 in water; columns with x > 12 or z < -12 are unloaded. */
    private static IslandFlattenAreaDetector.ColumnReader islandWithTwoUnloadedSides() {
        return (x, z) -> {
            if (x > 12 || z < -12) return IslandFlattenAreaDetector.Cell.UNLOADED;
            return Math.abs(x) <= 20 && Math.abs(z) <= 20 ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER;
        };
    }

    @Test
    void unloadedColumnsOnSeveralSidesAreReportedTogether() {
        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(0, 0, SETTINGS, islandWithTwoUnloadedSides());
        assertEquals(IslandFlattenAreaDetector.Status.UNLOADED, result.status());
        assertNotNull(result.problemPos());
        // Gaps on the east (x = 13) and on the north (z = -13) are both known now.
        assertTrue(result.gaps().stream().anyMatch(gap -> gap.getX() == 13));
        assertTrue(result.gaps().stream().anyMatch(gap -> gap.getZ() == -13));
        List<IslandFlattenAreaDetector.GapCluster> clusters = IslandFlattenAreaDetector.gapClusters(result.gaps(), 0, 0);
        assertEquals(1, clusters.size(), "the two sides meet at the north-east corner and form one cluster");
        assertTrue(clusters.getFirst().distance() >= 12 && clusters.getFirst().distance() <= 14);
    }

    @Test
    void separateGapsBecomeSeparateClustersNearestFirst() {
        List<BlockPos> gaps = List.of(
                new BlockPos(100, 0, 0), new BlockPos(101, 0, 0),     // east, 100 blocks
                new BlockPos(0, 0, -40), new BlockPos(1, 0, -40),     // north, 40 blocks
                new BlockPos(-200, 0, 200));                         // south-west, far
        List<IslandFlattenAreaDetector.GapCluster> clusters = IslandFlattenAreaDetector.gapClusters(gaps, 0, 0);
        assertEquals(3, clusters.size());
        assertEquals("北", clusters.get(0).direction());
        assertEquals(40, clusters.get(0).distance());
        assertEquals("东", clusters.get(1).direction());
        assertEquals("西南", clusters.get(2).direction());
        String summary = FlattenPlanner.gapSummary(clusters);
        assertTrue(summary.startsWith("岛屿边界还有 3 处未勘测（共 3 个区块）"), summary);
        assertTrue(summary.contains("北约 40 格（X=0，Z=-40）"), summary);
        assertFalse(summary.contains("62"), "no invented Y in the message: " + summary);
    }

    @Test
    void compassDirectionsFollowMinecraftAxes() {
        assertEquals("北", IslandFlattenAreaDetector.compassDirection(0, -10));
        assertEquals("南", IslandFlattenAreaDetector.compassDirection(0, 10));
        assertEquals("东", IslandFlattenAreaDetector.compassDirection(10, 0));
        assertEquals("西", IslandFlattenAreaDetector.compassDirection(-10, 0));
        assertEquals("东北", IslandFlattenAreaDetector.compassDirection(10, -10));
        assertEquals("附近", IslandFlattenAreaDetector.compassDirection(0, 0));
    }

    @Test
    void scanSpreadOverManySmallStepsGivesTheSameResult() {
        IslandFlattenAreaDetector.ColumnReader island = (x, z) ->
                Math.abs(x) <= 25 && Math.abs(z) <= 18 && !(x > 10 && x < 14 && z > -3 && z < 3)
                        ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER;
        IslandFlattenAreaDetector.Settings settings = new IslandFlattenAreaDetector.Settings(64, 100000, 2, true, true, 4);
        IslandFlattenAreaDetector.Result oneShot = IslandFlattenAreaDetector.detect(0, 0, settings, island);

        IslandFlattenAreaDetector.Job job = new IslandFlattenAreaDetector.Job(0, 0, settings, island);
        int steps = 0;
        // An already passed deadline: every call does one small batch of work and stops.
        while (!job.step(Long.MIN_VALUE)) steps++;
        IslandFlattenAreaDetector.Result sliced = job.result();

        assertTrue(steps > 10, "the scan must really have been split, steps=" + steps);
        assertEquals(oneShot.status(), sliced.status());
        assertEquals(oneShot.columns(), sliced.columns());
        assertEquals(oneShot.shoreExcludedColumns(), sliced.shoreExcludedColumns());
        assertEquals(oneShot.includedInteriorWaterColumns(), sliced.includedInteriorWaterColumns());
        assertEquals(IslandFlattenAreaDetector.Status.READY, sliced.status());
        assertEquals(15, sliced.includedInteriorWaterColumns(), "the 3x5 inner pond is included when enabled");
    }

    @Test
    void surveyRoundTripsThroughItsFileFormat() throws Exception {
        IslandSurveyCache original = new IslandSurveyCache();
        original.ensureScope("server|overworld|62");
        byte[] kinds = new byte[256];
        kinds[0] = IslandSurveyCache.LAND;
        kinds[255] = IslandSurveyCache.WATER;
        original.putChunk(-230, -635, kinds, 1234L);
        original.put(5, 5, IslandSurveyCache.WATER);
        assertTrue(original.dirty());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.writeTo(buffer);

        IslandSurveyCache loaded = new IslandSurveyCache();
        assertTrue(loaded.readFrom(new ByteArrayInputStream(buffer.toByteArray()), "server|overworld|62"));
        assertEquals(IslandSurveyCache.LAND, loaded.get(-230 * 16, -635 * 16));
        assertEquals(IslandSurveyCache.WATER, loaded.get(-230 * 16 + 15, -635 * 16 + 15));
        assertEquals(IslandSurveyCache.WATER, loaded.get(5, 5));
        assertFalse(loaded.dirty());
        // Recorded in an earlier session: counts as captured long ago, so it gets refreshed.
        assertEquals(0L, loaded.capturedAt(-230, -635));
        assertEquals(Long.MIN_VALUE, loaded.capturedAt(0, 0), "a partially observed chunk was never fully captured");
    }

    @Test
    void surveyOfAnotherScopeIsNeverLoaded() throws Exception {
        IslandSurveyCache original = new IslandSurveyCache();
        original.ensureScope("server-a|overworld|62");
        original.put(1, 1, IslandSurveyCache.LAND);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        original.writeTo(buffer);
        IslandSurveyCache other = new IslandSurveyCache();
        assertFalse(other.readFrom(new ByteArrayInputStream(buffer.toByteArray()), "server-a|overworld|63"));
        assertEquals(IslandSurveyCache.UNKNOWN, other.get(1, 1));
        assertFalse(IslandSurveyStore.fileName("a").equals(IslandSurveyStore.fileName("b")));
    }

    @Test
    void highScaffoldsAreReachedByAPillarFirst() {
        BlockPos floor = new BlockPos(0, 63, 0);
        assertTrue(ConstructionController.mayUseClusterPillar(floor, new BlockPos(1, 98, 0)));
        assertTrue(ConstructionController.mayUseClusterPillar(floor, new BlockPos(1, 63 + ConstructionController.CLUSTER_PILLAR_MIN_RISE, 0)));
        assertFalse(ConstructionController.mayUseClusterPillar(floor, new BlockPos(1, 66, 0)));
        assertFalse(ConstructionController.mayUseClusterPillar(null, new BlockPos(1, 98, 0)));
    }
}
