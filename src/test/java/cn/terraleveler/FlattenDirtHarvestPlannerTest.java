package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenDirtHarvestPlannerTest {
    private static FlattenPlan area(int maxX) {
        return new FlattenPlan(new BlockPos(0, 64, 0), new BlockPos(maxX, 64, 0),
                64, 68, List.of(), List.of(), 0);
    }

    @Test
    void opensGrassCapThenHarvestsDirtButNeverTouchesTargetLayer() {
        Map<BlockPos, FlattenDirtHarvestPlanner.CellKind> cells = new HashMap<>();
        cells.put(new BlockPos(0, 67, 0), FlattenDirtHarvestPlanner.CellKind.SOIL_OVERBURDEN);
        cells.put(new BlockPos(0, 66, 0), FlattenDirtHarvestPlanner.CellKind.DIRT_SOURCE);
        cells.put(new BlockPos(0, 65, 0), FlattenDirtHarvestPlanner.CellKind.BLOCKED);
        cells.put(new BlockPos(0, 64, 0), FlattenDirtHarvestPlanner.CellKind.DIRT_SOURCE);

        FlattenDirtHarvestPlanner.Plan result = FlattenDirtHarvestPlanner.plan(
                area(0), 64, 68, new BlockPos(0, 65, 0), 1,
                pos -> cells.getOrDefault(pos, FlattenDirtHarvestPlanner.CellKind.AIR));

        assertEquals(List.of(new BlockPos(0, 67, 0), new BlockPos(0, 66, 0)), result.targets());
        assertEquals(1, result.expectedDirt());
        assertTrue(result.targets().stream().allMatch(pos -> pos.getY() > 64));
    }

    @Test
    void protectedOrNonSoilCapRejectsTheWholeColumn() {
        Map<BlockPos, FlattenDirtHarvestPlanner.CellKind> cells = Map.of(
                new BlockPos(0, 67, 0), FlattenDirtHarvestPlanner.CellKind.BLOCKED,
                new BlockPos(0, 66, 0), FlattenDirtHarvestPlanner.CellKind.DIRT_SOURCE);

        FlattenDirtHarvestPlanner.Plan result = FlattenDirtHarvestPlanner.plan(
                area(0), 64, 68, new BlockPos(0, 65, 0), 1,
                pos -> cells.getOrDefault(pos, FlattenDirtHarvestPlanner.CellKind.AIR));

        assertTrue(result.targets().isEmpty());
        assertEquals(0, result.expectedDirt());
    }

    @Test
    void choosesNearbyColumnsUntilTheBatchGoalIsCovered() {
        FlattenDirtHarvestPlanner.Plan result = FlattenDirtHarvestPlanner.plan(
                area(2), 64, 66, new BlockPos(2, 65, 0), 1,
                pos -> pos.getY() == 65
                        ? FlattenDirtHarvestPlanner.CellKind.DIRT_SOURCE
                        : FlattenDirtHarvestPlanner.CellKind.AIR);

        assertEquals(List.of(new BlockPos(2, 65, 0)), result.targets());
        assertEquals(1, result.expectedDirt());
    }

}
