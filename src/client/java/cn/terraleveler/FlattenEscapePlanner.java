package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Plans one bounded walk or step-up cell of a two-block-high flattening escape corridor. */
public final class FlattenEscapePlanner {
    public enum CellKind { OPEN, BREAKABLE_SOLID, PROTECTED_SOLID, FLUID, UNLOADED }

    /**
     * One movement subgoal belonging to an unchanged construction task.
     * The task key must survive while the destination is opened and walked to.
     */
    public record Step(BlockPos taskKey, BlockPos destination, List<BlockPos> breakTargets) {
        public Step {
            taskKey = taskKey.immutable();
            destination = destination.immutable();
            breakTargets = List.copyOf(breakTargets);
        }
    }

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private FlattenEscapePlanner() { }

    /** An observed AIR cell still belongs to the active break until server confirmation settles. */
    static boolean mustFinishActiveBreak(CellKind observed, boolean ownsCurrentTarget,
                                         boolean actionIdle) {
        return observed == CellKind.OPEN && ownsCurrentTarget && !actionIdle;
    }

    public static Optional<Step> nextStep(WorkArea area, BlockPos startFeet, BlockPos task,
                                          int maximumBreakY,
                                          Function<BlockPos, CellKind> environment) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(startFeet, "startFeet");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(environment, "environment");
        int currentDistance = horizontalDistance(startFeet, task);

        List<Step> candidates = new ArrayList<>();
        if (currentDistance > 0) {
            for (Direction direction : HORIZONTAL) {
                BlockPos destination = startFeet.relative(direction);
                if (!inside(area, destination) || horizontalDistance(destination, task) >= currentDistance) continue;
                CellKind floor = environment.apply(destination.below());
                if (floor != CellKind.BREAKABLE_SOLID && floor != CellKind.PROTECTED_SOLID) continue;

                List<BlockPos> breaks = new ArrayList<>(2);
                if (!corridorCell(destination, maximumBreakY, environment, breaks)) continue;
                if (!corridorCell(destination.above(), maximumBreakY, environment, breaks)) continue;
                candidates.add(new Step(task, destination, breaks));
            }
        }
        Optional<Step> horizontal = candidates.stream().min(Comparator
                .comparingInt((Step step) -> horizontalDistance(step.destination(), task))
                .thenComparingInt(step -> step.breakTargets().size()));
        if (horizontal.isPresent()) return horizontal;

        if (startFeet.getY() <= maximumBreakY) {
            List<Step> ascending = new ArrayList<>();
            for (Direction direction : HORIZONTAL) {
                BlockPos destination = startFeet.relative(direction).above();
                if (!inside(area, destination)) continue;
                CellKind floor = environment.apply(destination.below());
                if (floor != CellKind.BREAKABLE_SOLID && floor != CellKind.PROTECTED_SOLID) continue;

                List<BlockPos> breaks = new ArrayList<>(3);
                if (!clearanceCell(startFeet.above(2), maximumBreakY + 2, environment, breaks)) continue;
                if (!clearanceCell(destination, maximumBreakY + 2, environment, breaks)) continue;
                if (!clearanceCell(destination.above(), maximumBreakY + 2, environment, breaks)) continue;
                ascending.add(new Step(task, destination, breaks));
            }
            Optional<Step> upward = ascending.stream().min(Comparator
                    .comparingInt((Step step) -> step.breakTargets().size())
                    .thenComparingInt(step -> horizontalDistance(step.destination(), task)));
            if (upward.isPresent()) return upward;
        }

        // A surface fill can close the shaft above a player who is still below
        // the final ground. Remove only the first ordinary solid overhead, then
        // return control to the route planner instead of tunnelling blindly.
        if (!inside(area, startFeet) || startFeet.getY() > maximumBreakY) return Optional.empty();
        for (int y = startFeet.getY() + 2; y <= maximumBreakY + 2; y++) {
            BlockPos overhead = new BlockPos(startFeet.getX(), y, startFeet.getZ());
            switch (environment.apply(overhead)) {
                case OPEN -> { }
                case BREAKABLE_SOLID -> {
                    return Optional.of(new Step(task, startFeet, List.of(overhead)));
                }
                case PROTECTED_SOLID, FLUID, UNLOADED -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static boolean corridorCell(BlockPos position, int maximumBreakY,
                                          Function<BlockPos, CellKind> environment,
                                          List<BlockPos> breaks) {
        return clearanceCell(position, maximumBreakY, environment, breaks);
    }

    private static boolean clearanceCell(BlockPos position, int maximumBreakY,
                                          Function<BlockPos, CellKind> environment,
                                          List<BlockPos> breaks) {
        return switch (environment.apply(position)) {
            case OPEN -> true;
            case BREAKABLE_SOLID -> {
                if (position.getY() > maximumBreakY) yield false;
                breaks.add(position.immutable());
                yield true;
            }
            case PROTECTED_SOLID, FLUID, UNLOADED -> false;
        };
    }

    private static boolean inside(WorkArea area, BlockPos position) {
        return position.getX() >= area.minX() && position.getX() <= area.maxX()
                && position.getZ() >= area.minZ() && position.getZ() <= area.maxZ();
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
}
