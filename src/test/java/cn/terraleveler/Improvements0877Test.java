package cn.terraleveler;

import static cn.terraleveler.FluidCellKind.AIR;
import static cn.terraleveler.FluidCellKind.SOLID;
import static cn.terraleveler.FluidCellKind.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Water fed from outside the selection (0.8.76 test run, stream from -196,84,-496). */
class Improvements0877Test {
    private static final FlattenFluidDrainPlanner.Limits LIMITS = new FlattenFluidDrainPlanner.Limits(10, 40, 2);

    @Test
    void streamFromOutsideIsCutAtTheSealRingJustOutsideTheSelection() {
        FlattenPlan area = new FlattenPlan(new BlockPos(0, 10, 0), new BlockPos(4, 10, 4), 10, 14, List.of(), List.of(), 0);
        World world = new World();
        for (int x = -1; x <= 5; x++) {
            for (int z = -4; z <= 5; z++) world.set(new BlockPos(x, 10, z), SOLID);
        }
        BlockPos outside = new BlockPos(2, 11, -2);
        BlockPos ring = new BlockPos(2, 11, -1);
        BlockPos inside = new BlockPos(2, 11, 1);
        world.set(new BlockPos(2, 11, -3), WATER).set(outside, WATER).set(ring, WATER)
                .set(new BlockPos(2, 11, 0), WATER).set(inside, WATER);

        var result = FlattenFluidDrainPlanner.plan(area, inside, world, LIMITS);

        assertTrue(result.ready());
        assertTrue(result.transaction().fluidCells().contains(ring));
        assertFalse(result.transaction().fluidCells().contains(outside));
        assertTrue(result.transaction().placements().stream().anyMatch(placement -> placement.target().equals(ring)));
    }

    @Test
    void theInflowCellIsFilledBeforeTheWaterInsideAndTheWaitHappensOnlyAfterIt() {
        // index 0: inside cell within reach, 1: seal-ring inflow cell (a feeder) out of reach.
        int selected = FlattenFluidExecutionRules.selectNextPlacement(0, 2,
                index -> true, index -> false, index -> index == 1,
                index -> index == 0, index -> 11, index -> index);
        assertEquals(1, selected);
        assertFalse(FlattenFluidExecutionRules.shouldWaitForRecession(true, true, 0, ConstructionController.FLUID_RECEDE_WAIT_TICKS));
        assertTrue(FlattenFluidExecutionRules.shouldWaitForRecession(false, true, 0, ConstructionController.FLUID_RECEDE_WAIT_TICKS));
    }

    private static final class World implements FlattenFluidDrainPlanner.Snapshot {
        private final Map<BlockPos, FluidCellKind> cells = new HashMap<>();

        World set(BlockPos position, FluidCellKind kind) {
            if (kind == AIR) cells.remove(position);
            else cells.put(position.immutable(), kind);
            return this;
        }

        @Override
        public FluidCellKind cellAt(BlockPos position) {
            return cells.getOrDefault(position, AIR);
        }
    }
}
