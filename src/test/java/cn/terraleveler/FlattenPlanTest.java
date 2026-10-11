package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenPlanTest {
    @Test
    void rectangularLayerUsesContiguousSerpentineRows() {
        assertEquals(List.of(
                new BlockPos(0, 70, 0), new BlockPos(1, 70, 0), new BlockPos(2, 70, 0),
                new BlockPos(2, 70, 1), new BlockPos(1, 70, 1), new BlockPos(0, 70, 1)),
                FlattenPlanner.rectangularLayer(0, 2, 0, 1, 70));
    }

    @Test
    void excavationVisitsNearbyRealBlocksInsteadOfCrossingAirGapsRepeatedly() {
        List<BlockPos> source = List.of(
                new BlockPos(0, 66, 0), new BlockPos(10, 66, 0),
                new BlockPos(0, 66, 1), new BlockPos(10, 66, 1),
                new BlockPos(9, 65, 1), new BlockPos(0, 65, 1));

        assertEquals(List.of(
                new BlockPos(0, 66, 0), new BlockPos(0, 66, 1),
                new BlockPos(10, 66, 1), new BlockPos(10, 66, 0),
                new BlockPos(9, 65, 1), new BlockPos(0, 65, 1)),
                FlattenPlan.localityWithinLayers(source, new BlockPos(0, 66, 0)));
    }

    @Test
    void finishedSurfaceKeepsDirtAndGrassButReplacesOtherBreakableBlocks() {
        assertFalse(FlattenPlanner.needsDirtSurfaceReplacement(true, false,
                false, false, false), "ordinary dirt stays");
        assertFalse(FlattenPlanner.needsDirtSurfaceReplacement(false, true,
                false, false, false), "grass blocks stay");
        assertFalse(FlattenPlanner.needsDirtSurfaceReplacement(false, false,
                true, false, false), "air is handled as fill, not replacement");
        assertFalse(FlattenPlanner.needsDirtSurfaceReplacement(false, false,
                false, true, false), "liquid follows the existing safe fill path");
        assertFalse(FlattenPlanner.needsDirtSurfaceReplacement(false, false,
                false, false, true), "bedrock is not breakable");
        assertTrue(FlattenPlanner.needsDirtSurfaceReplacement(false, false,
                false, false, false), "stone and ores are replaced");
    }

    @Test
    void fillOrderRemainsBottomUpAndStartsNearestToPlayer() {
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                64, 66, List.of(), List.of(
                new BlockPos(0, 62, 0), new BlockPos(1, 62, 0),
                new BlockPos(0, 63, 0), new BlockPos(1, 63, 0),
                new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)), 0);

        List<BlockPos> targets = plan.fillTargetsFrom(new BlockPos(1, 64, 0));

        assertEquals(List.of(62, 62, 63, 63, 64, 64),
                targets.stream().map(BlockPos::getY).toList());
        assertEquals(new BlockPos(1, 62, 0), targets.getFirst());
        assertEquals(new BlockPos(0, 62, 0), targets.get(1));
    }

    @Test
    void rectangularFillSweepsContinuouslyInsteadOfPendulumJumping() {
        List<BlockPos> fill = new java.util.ArrayList<>();
        for (int y = 62; y <= 63; y++) {
            for (int z = 0; z <= 2; z++) {
                for (int x = 0; x <= 4; x++) fill.add(new BlockPos(x, y, z));
            }
        }
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(4, 64, 2),
                64, 64, List.of(), fill, 0);

        List<BlockPos> targets = plan.fillTargetsFrom(new BlockPos(4, 64, 0));

        assertEquals(new BlockPos(4, 62, 0), targets.getFirst(),
                "the surface sweep starts near the player");
        for (int index = 1; index < targets.size(); index++) {
            BlockPos previous = targets.get(index - 1);
            BlockPos current = targets.get(index);
            int distance = Math.abs(current.getX() - previous.getX())
                    + Math.abs(current.getY() - previous.getY())
                    + Math.abs(current.getZ() - previous.getZ());
            assertEquals(1, distance, "full rectangular layers should form one continuous sweep");
        }
    }

    @Test
    void wideFillSweepsAlongShortZAxis() {
        List<BlockPos> fill = new java.util.ArrayList<>();
        for (int z = 0; z <= 1; z++) {
            for (int x = 0; x <= 4; x++) fill.add(new BlockPos(x, 64, z));
        }
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(4, 64, 1),
                64, 64, List.of(), fill, 0);

        assertEquals(List.of(
                new BlockPos(0, 64, 0), new BlockPos(0, 64, 1),
                new BlockPos(1, 64, 1), new BlockPos(1, 64, 0)),
                plan.fillTargetsFrom(new BlockPos(0, 64, 0)).subList(0, 4));
    }

    @Test
    void deepFillSweepsAlongShortXAxis() {
        List<BlockPos> fill = new java.util.ArrayList<>();
        for (int z = 0; z <= 4; z++) {
            for (int x = 0; x <= 1; x++) fill.add(new BlockPos(x, 64, z));
        }
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(1, 64, 4),
                64, 64, List.of(), fill, 0);

        assertEquals(List.of(
                new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1), new BlockPos(0, 64, 1)),
                plan.fillTargetsFrom(new BlockPos(0, 64, 0)).subList(0, 4));
    }

    @Test
    void flattenAreaAllowsInteriorTravelButBoundsScaffolds() {
        FlattenPlan plan = new FlattenPlan(new BlockPos(10, 64, 20), new BlockPos(14, 64, 24),
                64, 64, List.of(), List.of(), 0);

        assertFalse(plan.isStrictInteriorXZ(12, 22));
        assertTrue(plan.permitsScaffoldAt(12, 22));
        assertTrue(plan.permitsScaffoldAt(9, 19));
        assertFalse(plan.permitsScaffoldAt(8, 19));
        assertEquals(25, plan.columns());
    }

    @Test
    void maskedFlattenAreaCountsAndContainsOnlySelectedColumns() {
        Set<BlockPos> columns = Set.of(new BlockPos(10, 0, 20), new BlockPos(11, 0, 20), new BlockPos(11, 0, 21));
        FlattenPlan plan = new FlattenPlan(new BlockPos(10, 64, 20), new BlockPos(11, 64, 21),
                64, 64, List.of(), List.of(), List.of(), Set.of(), List.of(), 0,
                columns, 4, 1, 0, 1);

        assertEquals(3, plan.columns());
        assertTrue(plan.containsColumn(11, 21));
        assertFalse(plan.containsColumn(10, 21));
        assertFalse(plan.permitsScaffoldAt(10, 21));
    }

    @Test
    void islandWaterDetectionLooksThroughNonSolidSurfacePlants() {
        assertTrue(FlattenPlanner.islandWaterColumn(true, false, false));
        assertTrue(FlattenPlanner.islandWaterColumn(false, true, false));
        assertFalse(FlattenPlanner.islandWaterColumn(false, true, true));
        assertFalse(FlattenPlanner.islandWaterColumn(false, false, false));
    }

    @Test
    void primaryExcavationAndDeferredFoliageAreScheduledSeparately() {
        BlockPos log = new BlockPos(0, 66, 0);
        BlockPos leaf = new BlockPos(1, 66, 0);
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                64, 66, List.of(log), List.of(leaf), List.of(), 2);

        assertEquals(List.of(log), plan.excavationTargetsFrom(null));
        assertEquals(List.of(leaf), plan.scheduledDeferredFoliageTargetsFrom(null, Set.of()).targets());
        assertEquals(1, plan.primaryExcavationTargetCount());
        assertEquals(1, plan.deferredFoliageTargetCount());
        assertEquals(2, plan.excavationTargetCount());
    }

    @Test
    void treeTargetsFinishByComponentBeforeOrdinaryTerrain() {
        BlockPos nearTop = new BlockPos(0, 67, 0);
        BlockPos nearBottom = new BlockPos(0, 66, 0);
        BlockPos farTop = new BlockPos(8, 69, 0);
        BlockPos farBottom = new BlockPos(8, 68, 0);
        BlockPos stone = new BlockPos(1, 70, 0);
        FlattenPlan plan = new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0),
                64, 70, List.of(stone),
                List.of(farTop, nearBottom, farBottom, nearTop),
                List.of(), List.of(), 5);

        assertEquals(List.of(nearTop, nearBottom, farTop, farBottom, stone),
                plan.excavationTargetsFrom(new BlockPos(1, 64, 0)));
        assertTrue(plan.isSameTree(nearTop, nearBottom));
        assertFalse(plan.isSameTree(nearTop, farTop));
        assertEquals(4, plan.treeTargetCount());
        assertEquals(2, plan.treeCount());
        assertEquals(5, plan.primaryExcavationTargetCount());
    }
}
