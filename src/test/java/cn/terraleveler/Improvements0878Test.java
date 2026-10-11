package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Following a stream from outside the selection back to its spring (0.8.77 test run). */
class Improvements0878Test {
    private static final BlockPos SPRING = new BlockPos(0, 84, -10);
    private static final BlockPos RING_INLET = new BlockPos(0, 82, -6);

    /** Spring in a hillside, a short run, a two-block fall and a spread toward the selection (z >= -5). */
    private static World stream() {
        World world = new World();
        world.spring(SPRING).solid(SPRING.below()).solid(SPRING.north());
        world.water(new BlockPos(0, 84, -9), 7).water(new BlockPos(0, 84, -8), 6);
        world.water(new BlockPos(0, 83, -8), 8).water(new BlockPos(0, 82, -8), 8);
        world.water(new BlockPos(0, 82, -7), 7).water(RING_INLET, 6).water(new BlockPos(0, 82, -5), 5);
        return world;
    }

    @Test
    void springFeedingTheStreamIsFoundAndPluggedAgainstTheHillside() {
        FluidSourceTracer.Result result = FluidSourceTracer.trace(List.of(RING_INLET), stream(), pos -> pos.getZ() < -5);
        assertTrue(result.found());
        assertEquals(List.of(SPRING), result.sources());
        assertEquals(SPRING, result.placements().getFirst().target());
        assertEquals(SPRING.below(), result.placements().getFirst().support());
    }

    @Test
    void theSearchNeverFollowsWaterDownstreamOrIntoTheSelection() {
        World world = stream();
        BlockPos insideSource = new BlockPos(1, 82, -5);
        world.spring(insideSource).solid(insideSource.below());
        FluidSourceTracer.Result result = FluidSourceTracer.trace(List.of(RING_INLET), world, pos -> pos.getZ() < -5);
        assertEquals(List.of(SPRING), result.sources());
        assertFalse(FluidSourceTracer.feeds(world, new BlockPos(0, 82, -5), RING_INLET));
        assertTrue(FluidSourceTracer.feeds(world, new BlockPos(0, 82, -7), RING_INLET));
    }

    @Test
    void aPondIsTooBigToPlugAndIsSealedAtTheEdgeInstead() {
        World world = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -12; z <= -8; z++) world.spring(new BlockPos(x, 82, z)).solid(new BlockPos(x, 81, z));
        }
        world.water(new BlockPos(0, 82, -7), 7).water(RING_INLET, 6);
        FluidSourceTracer.Result result = FluidSourceTracer.trace(List.of(RING_INLET), world, pos -> pos.getZ() < -5);
        assertEquals(FluidSourceTracer.Status.TOO_MANY_SOURCES, result.status());
        assertFalse(result.found());
    }

    @Test
    void waitingForTheStreamToDrainLastsWhileCellsKeepDrying() {
        assertTrue(FlattenFluidExecutionRules.shouldKeepWaitingForRecession(false, 40, 120, 0, 300, 40));
        assertFalse(FlattenFluidExecutionRules.shouldKeepWaitingForRecession(false, 40, 120, 40, 300, 40));
        assertFalse(FlattenFluidExecutionRules.shouldKeepWaitingForRecession(false, 40, 300, 0, 300, 40));
        assertFalse(FlattenFluidExecutionRules.shouldKeepWaitingForRecession(true, 40, 0, 0, 300, 40));
        assertFalse(FlattenFluidExecutionRules.shouldKeepWaitingForRecession(false, 0, 0, 0, 300, 40));
    }

    private static final class World implements FluidSourceTracer.Probe {
        private final Map<BlockPos, Integer> water = new HashMap<>();
        private final Set<BlockPos> sources = new HashSet<>();
        private final Set<BlockPos> solids = new HashSet<>();

        World water(BlockPos pos, int amount) {
            water.put(pos, amount);
            return this;
        }

        World spring(BlockPos pos) {
            sources.add(pos);
            return water(pos, 8);
        }

        World solid(BlockPos pos) {
            solids.add(pos);
            return this;
        }

        @Override
        public boolean loaded(BlockPos position) {
            return true;
        }

        @Override
        public int amount(BlockPos position) {
            return water.getOrDefault(position, 0);
        }

        @Override
        public boolean source(BlockPos position) {
            return sources.contains(position);
        }

        @Override
        public boolean support(BlockPos position) {
            return solids.contains(position);
        }
    }
}
