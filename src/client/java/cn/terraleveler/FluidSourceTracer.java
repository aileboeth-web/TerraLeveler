package cn.terraleveler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Follows water upstream from where it enters the selection to the source blocks feeding
 * it (0.8.78). In the 0.8.77 test run a spring at about -196,84,-496, six blocks outside
 * the selection, kept a stream running into it; sealing the cells where the stream entered
 * only made it spill in one block further along, and the job paused.
 */
final class FluidSourceTracer {
    /** More source blocks than this is a pond or river: it is sealed at the edge instead. */
    static final int MAX_SOURCES = 16;
    static final int MAX_VISITED = 4096;
    /** Horizontal distance (Chebyshev) from the nearest start cell the search may go. */
    static final int MAX_DISTANCE = 24;

    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private FluidSourceTracer() { }

    /** Live view of the world around the stream. */
    interface Probe {
        boolean loaded(BlockPos position);

        /** Fluid amount of a replaceable fluid block: 8 for sources and falling water, 1..7 flowing, 0 otherwise. */
        int amount(BlockPos position);

        /** A source block that placing a block into removes (not a waterlogged block). */
        boolean source(BlockPos position);

        /** A block a placement may be made against. */
        boolean support(BlockPos position);
    }

    enum Status { FOUND, NOT_FOUND, TOO_MANY_SOURCES, UNLOADED, NO_SUPPORT }

    record Result(Status status, List<BlockPos> sources, List<FlattenFluidDrainPlanner.Placement> placements, int visited) {
        Result {
            Objects.requireNonNull(status, "status");
            sources = List.copyOf(sources);
            placements = List.copyOf(placements);
        }

        boolean found() {
            return status == Status.FOUND && !placements.isEmpty();
        }
    }

    /**
     * Water at {@code from} is fed by {@code neighbour} when the neighbour holds water and
     * lies above it, is a source, or holds more water (flowing water loses one level per
     * block it spreads).
     */
    static boolean feeds(Probe probe, BlockPos neighbour, BlockPos from) {
        int amount = probe.amount(neighbour);
        if (amount <= 0) return false;
        return neighbour.getY() > from.getY() || probe.source(neighbour) || amount > probe.amount(from);
    }

    /**
     * Searches upstream from {@code starts} through cells {@code allowed} accepts and plans
     * one placement per source found, highest first, each against a solid neighbour or a
     * source planned before it.
     */
    static Result trace(Collection<BlockPos> starts, Probe probe, Predicate<BlockPos> allowed) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        LinkedHashSet<BlockPos> sources = new LinkedHashSet<>();
        boolean unloaded = false;
        for (BlockPos start : starts) {
            if (probe.amount(start) > 0 && visited.add(start.immutable())) queue.add(start.immutable());
        }
        while (!queue.isEmpty() && visited.size() <= MAX_VISITED) {
            BlockPos current = queue.removeFirst();
            if (probe.source(current) && allowed.test(current)) {
                sources.add(current);
                if (sources.size() > MAX_SOURCES) {
                    return new Result(Status.TOO_MANY_SOURCES, List.copyOf(sources), List.of(), visited.size());
                }
            }
            for (Direction direction : Direction.values()) {
                if (direction == Direction.DOWN) continue;
                BlockPos neighbour = current.relative(direction);
                if (visited.contains(neighbour) || !allowed.test(neighbour) || !withinDistance(starts, neighbour)) continue;
                if (!probe.loaded(neighbour)) {
                    unloaded = true;
                    continue;
                }
                if (!feeds(probe, neighbour, current)) continue;
                visited.add(neighbour.immutable());
                queue.addLast(neighbour.immutable());
            }
        }
        if (sources.isEmpty()) {
            return new Result(unloaded ? Status.UNLOADED : Status.NOT_FOUND, List.of(), List.of(), visited.size());
        }

        List<BlockPos> remaining = new ArrayList<>(sources);
        remaining.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed());
        Set<BlockPos> planned = new HashSet<>();
        List<FlattenFluidDrainPlanner.Placement> placements = new ArrayList<>();
        boolean advanced = true;
        while (!remaining.isEmpty() && advanced) {
            advanced = false;
            for (BlockPos source : List.copyOf(remaining)) {
                BlockPos support = supportFor(source, planned, probe);
                if (support == null) continue;
                placements.add(new FlattenFluidDrainPlanner.Placement(source, support));
                planned.add(source);
                remaining.remove(source);
                advanced = true;
            }
        }
        Status status = placements.isEmpty() ? Status.NO_SUPPORT : Status.FOUND;
        return new Result(status, List.copyOf(sources), placements, visited.size());
    }

    /** A neighbour a block placed into {@code target} can be clicked against, or null. */
    static BlockPos supportFor(BlockPos target, Set<BlockPos> planned, Probe probe) {
        for (Direction direction : SUPPORT_ORDER) {
            BlockPos candidate = target.relative(direction);
            if (planned.contains(candidate) || probe.support(candidate)) return candidate.immutable();
        }
        return null;
    }

    private static boolean withinDistance(Collection<BlockPos> starts, BlockPos position) {
        for (BlockPos start : starts) {
            if (Math.abs(start.getX() - position.getX()) <= MAX_DISTANCE
                    && Math.abs(start.getZ() - position.getZ()) <= MAX_DISTANCE
                    && Math.abs(start.getY() - position.getY()) <= MAX_DISTANCE) {
                return true;
            }
        }
        return false;
    }
}
