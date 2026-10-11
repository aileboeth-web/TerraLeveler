package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WaterBucketFallPlannerTest {
    @Test
    void selectsAnAdjacentLoadedSolidLandingWithinBucketRange() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-201, 91, -435);
        BlockPos task = new BlockPos(-156, 62, -438);
        BlockPos landing = start.east().below(29);
        environment.solid(landing.below());

        WaterBucketFallPlanner.Result result = WaterBucketFallPlanner.findLanding(
                environment, start, task, WaterBucketFallPlanner.MAX_BUCKET_FALL);

        assertEquals(landing, result.landingFeet());
        assertEquals(29, result.dropDistance());
    }

    @Test
    void rejectsFluidsUnloadedCellsAndFallsBeyondTheBound() {
        BlockPos start = new BlockPos(0, 40, 0);
        BlockPos task = new BlockPos(10, 1, 0);

        FakeEnvironment fluid = new FakeEnvironment();
        fluid.set(start.east().below(4), SafeRoutePlanner.CellKind.FLUID);
        fluid.solid(start.east().below(8).below());
        assertNull(WaterBucketFallPlanner.findLanding(fluid, start, task, 32));

        FakeEnvironment tooDeep = new FakeEnvironment();
        tooDeep.solid(start.east().below(33).below());
        assertNull(WaterBucketFallPlanner.findLanding(tooDeep, start, task, 32));
    }

    @Test
    void rejectsShortDropsThatDoNotNeedAClutch() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 64, 0);
        environment.solid(start.east().below(2).below());

        assertNull(WaterBucketFallPlanner.findLanding(environment, start, start, 32));
    }

    @Test
    void rejectsADeepFloorHiddenBelowAnImmediateShelf() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-144, 63, -403);
        BlockPos column = start.west();
        environment.solid(column.below());
        environment.solid(column.below(7).below());

        assertNull(WaterBucketFallPlanner.findLanding(environment, start, start, 32));
    }

    @Test
    void dedicatedBucketFallKeepsControlAfterEnteringItsOwnWater() {
        assertFalse(ConstructionController.shouldBeginGeneralWaterRecovery(true, true));
        assertTrue(ConstructionController.shouldBeginGeneralWaterRecovery(true, false));
        assertFalse(ConstructionController.shouldBeginGeneralWaterRecovery(false, false));
    }

    @Test
    void findsAWalkableLaunchPointNearTheCurrentPosition() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(0, 40, 0);
        BlockPos task = new BlockPos(8, 14, 0);
        BlockPos launch = start.offset(3, 0, 0);
        BlockPos landing = launch.east().below(26);
        environment.solid(start.below());
        environment.solid(launch.below());
        environment.solid(landing.below());

        List<WaterBucketFallPlanner.Result> results = WaterBucketFallPlanner.findNearbyLandings(
                environment, start, task, 32, 6);

        assertTrue(results.stream().anyMatch(result -> result.origin().equals(launch)
                && result.landingFeet().equals(landing)));
    }

    @Test
    void rejectsAClimbThatOnlyDropsBackToTheCurrentPosition() {
        FakeEnvironment environment = new FakeEnvironment();
        BlockPos start = new BlockPos(-143, 59, -395);
        BlockPos task = new BlockPos(-135, 65, -399);
        BlockPos launch = start.offset(-1, 4, 0);
        environment.solid(start.below());
        environment.solid(launch.below());

        List<WaterBucketFallPlanner.Result> results = WaterBucketFallPlanner.findNearbyLandings(
                environment, start, task, 32, 2);

        assertTrue(results.stream().noneMatch(result -> result.landingFeet().equals(start)));
        assertFalse(WaterBucketFallPlanner.advancesTowardTask(start, start, task));
    }

    @Test
    void rejectsBucketLandingsThatMoveFartherFromTheTask() {
        BlockPos start = new BlockPos(-143, 59, -400);
        BlockPos landing = new BlockPos(-143, 59, -395);
        BlockPos task = new BlockPos(-135, 65, -399);

        assertFalse(WaterBucketFallPlanner.advancesTowardTask(start, landing, task));
        assertTrue(WaterBucketFallPlanner.advancesTowardTask(
                start, new BlockPos(-140, 55, -399), task));
    }

    @Test
    void cleanupBucketEligibilityRequiresTheExactReturnTransactionToBeFinished() {
        assertTrue(ConstructionController.mayUseWaterBucketFall(
                ConstructionController.State.CLEANING_SCAFFOLDS, false, true));
        assertFalse(ConstructionController.mayUseWaterBucketFall(
                ConstructionController.State.CLEANING_SCAFFOLDS, true, true));
        assertFalse(ConstructionController.mayUseWaterBucketFall(
                ConstructionController.State.CLEANING_SCAFFOLDS, false, false));
    }

    private static final class FakeEnvironment implements SafeRoutePlanner.RouteEnvironment {
        private final Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();

        void solid(BlockPos position) {
            set(position, SafeRoutePlanner.CellKind.SOLID);
        }

        void set(BlockPos position, SafeRoutePlanner.CellKind cell) {
            cells.put(position.immutable(), cell);
        }

        @Override
        public SafeRoutePlanner.CellKind cell(BlockPos position) {
            return cells.getOrDefault(position, SafeRoutePlanner.CellKind.OPEN);
        }
    }
}
