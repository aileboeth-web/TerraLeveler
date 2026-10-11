package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.terraleveler.FluidCellKind.AIR;
import static cn.terraleveler.FluidCellKind.LAVA;
import static cn.terraleveler.FluidCellKind.SOLID;
import static cn.terraleveler.FluidCellKind.UNLOADED;
import static cn.terraleveler.FluidCellKind.WATER;
import static cn.terraleveler.FlattenFluidDrainPlanner.Status.EXTERNAL_INLET;
import static cn.terraleveler.FlattenFluidDrainPlanner.Status.READY;
import static cn.terraleveler.FlattenFluidDrainPlanner.VerificationStatus.REFLOW;
import static cn.terraleveler.FlattenFluidDrainPlanner.VerificationStatus.VERIFIED_DRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlattenFluidDrainPlannerTest {
    private static final FlattenFluidDrainPlanner.Limits LIMITS =
            new FlattenFluidDrainPlanner.Limits(10, 40, 2);

    @Test
    void clientPredictedDryBlockDoesNotAdvanceBeforeServerActionCompletes() {
        assertFalse(ConstructionController.mayAdvancePastObservedDryFluidPlacement(false, false));
        assertTrue(ConstructionController.mayAdvancePastObservedDryFluidPlacement(false, true));
        assertFalse(ConstructionController.mayAdvancePastObservedDryFluidPlacement(true, true));
    }

    @Test
    void connectedWaterIncludingGroundLayerBecomesOneBottomUpDirtTransaction() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos ground = new BlockPos(2, 10, 2);
        BlockPos upper = ground.above();
        world.set(ground, WATER).set(upper, WATER).set(ground.below(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, upper, world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(List.of(ground, upper), result.transaction().fluidCells());
        assertEquals(ground, result.transaction().placements().getFirst().target());
        assertEquals(ground.below(), result.transaction().placements().getFirst().support());
        assertEquals(ground, result.transaction().placements().getLast().support());
        assertEquals(2, result.transaction().requiredDirt());
        assertFalse(result.transaction().containsLava());
        assertEquals(10, result.transaction().verificationTicks());
    }

    @Test
    void boundedConnectedFluidLargerThanTheFormerLimitIsFullyPlanned() {
        FlattenPlan area = area(0, 24, 0, 19, 10, 14);
        World world = new World();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                BlockPos fluid = new BlockPos(x, area.targetGroundY(), z);
                world.set(fluid, WATER).set(fluid.below(), SOLID);
            }
        }

        var result = FlattenFluidDrainPlanner.plan(
                area, new BlockPos(12, area.targetGroundY(), 9), world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(500, result.transaction().fluidCells().size());
        assertEquals(500, result.transaction().requiredDirt());
    }

    @Test
    void lavaIsDrainedWithTheLongerVerificationWindow() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos lava = new BlockPos(2, 11, 2);
        world.set(lava, LAVA).set(lava.below(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, lava, world, LIMITS);

        assertEquals(READY, result.status());
        assertTrue(result.transaction().containsLava());
        assertEquals(40, result.transaction().verificationTicks());
    }

    @Test
    void connectedFluidOneBlockOutsideSelectionBecomesASealingBuffer() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos boundary = new BlockPos(0, 11, 2);
        BlockPos outside = boundary.west();
        world.set(boundary, WATER).set(outside, WATER)
                .set(boundary.below(), SOLID).set(outside.below(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, boundary, world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(List.of(boundary, outside), result.transaction().fluidCells());
        assertEquals(2, result.transaction().requiredDirt());
    }

    @Test
    void connectedFluidBeyondTheOneBlockSealIsNotAbsorbedIntoTheTransaction() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos boundary = new BlockPos(0, 11, 2);
        BlockPos seal = boundary.west();
        BlockPos river = seal.west();
        world.set(boundary, WATER).set(seal, WATER).set(river, WATER)
                .set(boundary.below(), SOLID).set(seal.below(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, boundary, world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(List.of(boundary, seal), result.transaction().fluidCells());

        world.set(boundary, SOLID).set(seal, SOLID);
        assertEquals(VERIFIED_DRY, FlattenFluidDrainPlanner.verify(result.transaction(), world).status());
    }

    @Test
    void fluidBelowTheFinalGroundCannotFeedUpwardAndIsIgnored() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos ground = new BlockPos(2, 10, 2);
        world.set(ground, WATER).set(ground.below(), WATER).set(ground.north(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, ground, world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(List.of(ground), result.transaction().fluidCells());
    }

    @Test
    void unloadedCellBelowTheFinalGroundIsOutsideTheDrainTransaction() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos ground = new BlockPos(2, 10, 2);
        world.set(ground, WATER).set(ground.below(), UNLOADED).set(ground.north(), SOLID);

        var result = FlattenFluidDrainPlanner.plan(area, ground, world, LIMITS);

        assertEquals(READY, result.status());
        assertEquals(List.of(ground), result.transaction().fluidCells());
    }

    @Test
    void verificationDetectsFluidEnteringThroughAWatchedSelectionBoundary() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos boundary = new BlockPos(0, 11, 2);
        BlockPos outside = boundary.west();
        world.set(boundary, WATER).set(boundary.below(), SOLID);
        var transaction = FlattenFluidDrainPlanner.plan(area, boundary, world, LIMITS).transaction();

        world.set(boundary, SOLID).set(outside, WATER);

        var verification = FlattenFluidDrainPlanner.verify(transaction, world);
        assertEquals(FlattenFluidDrainPlanner.VerificationStatus.EXTERNAL_INLET, verification.status());
        assertEquals(outside, verification.problemPos());
    }

    @Test
    void onlyDrySolidCellsCanSupportAControllerPlacement() {
        assertTrue(ConstructionController.flattenDrainPlacementSupport(SOLID));
        assertFalse(ConstructionController.flattenDrainPlacementSupport(AIR));
        assertFalse(ConstructionController.flattenDrainPlacementSupport(WATER));
        assertFalse(ConstructionController.flattenDrainPlacementSupport(LAVA));
    }

    @Test
    void verificationAcceptsAnyDrySolidIncludingObsidianAndDetectsReflow() {
        FlattenPlan area = area(0, 4, 0, 4, 10, 14);
        World world = new World();
        BlockPos target = new BlockPos(2, 11, 2);
        world.set(target, LAVA).set(target.below(), SOLID);
        var transaction = FlattenFluidDrainPlanner.plan(area, target, world, LIMITS).transaction();

        world.set(target, SOLID);
        assertEquals(VERIFIED_DRY, FlattenFluidDrainPlanner.verify(transaction, world).status());
        assertEquals(FluidCellKind.SOLID,
                ConstructionController.classifyFluidCell(Blocks.OBSIDIAN.defaultBlockState()));
        assertEquals(ExcavationPlanner.Operation.BREAK,
                ExcavationPlanner.operationFor(ExcavationPlanner.CellKind.SOLID));

        world.set(target, LAVA);
        assertEquals(REFLOW, FlattenFluidDrainPlanner.verify(transaction, world).status());
    }

    private static FlattenPlan area(int minX, int maxX, int minZ, int maxZ, int groundY, int topY) {
        return new FlattenPlan(new BlockPos(minX, groundY, minZ), new BlockPos(maxX, groundY, maxZ),
                groundY, topY, List.of(), List.of(), 0);
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
