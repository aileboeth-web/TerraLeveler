package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Plans the narrow recovery used when a flattening job has left the real player
 * below its final surface. The final surface cell must belong to the fill plan;
 * lower cells may form one bounded escape column so surface-only plans can lift
 * the player without filling the surrounding cave.
 */
public final class FlattenPillarPlanner {
    public enum CellKind { OPEN_DRY, SOLID, FLUID, UNLOADED }

    public enum Status {
        READY,
        OUTSIDE_SELECTION,
        ALREADY_ABOVE_TARGET,
        MISSING_PLANNED_FILL,
        SHAFT_BLOCKED,
        SHAFT_FLUID,
        SHAFT_UNLOADED
    }

    public record Result(Status status, List<BlockPos> placements) {
        public Result {
            Objects.requireNonNull(status, "status");
            placements = List.copyOf(placements);
        }

        public boolean ready() {
            return status == Status.READY && !placements.isEmpty();
        }
    }

    private FlattenPillarPlanner() { }

    public static Result plan(WorkArea area, int targetGroundY, BlockPos startFeet,
                              Predicate<BlockPos> isPlannedFill,
                              Function<BlockPos, CellKind> environment) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(startFeet, "startFeet");
        Objects.requireNonNull(isPlannedFill, "isPlannedFill");
        Objects.requireNonNull(environment, "environment");

        if (startFeet.getX() < area.minX() || startFeet.getX() > area.maxX()
                || startFeet.getZ() < area.minZ() || startFeet.getZ() > area.maxZ()) {
            return new Result(Status.OUTSIDE_SELECTION, List.of());
        }
        if (startFeet.getY() > targetGroundY) {
            return new Result(Status.ALREADY_ABOVE_TARGET, List.of());
        }

        List<BlockPos> placements = new ArrayList<>();
        for (int y = startFeet.getY(); y <= targetGroundY; y++) {
            BlockPos position = new BlockPos(startFeet.getX(), y, startFeet.getZ());
            if (y == targetGroundY && !isPlannedFill.test(position)) {
                return new Result(Status.MISSING_PLANNED_FILL, List.of());
            }
            Status obstruction = obstruction(environment.apply(position));
            if (obstruction != null) return new Result(obstruction, List.of());
            placements.add(position.immutable());
        }

        // The completed pillar puts the player's feet one block above targetGroundY;
        // both the feet and head cells must remain dry and collision-free.
        for (int y = targetGroundY + 1; y <= targetGroundY + 2; y++) {
            Status obstruction = obstruction(environment.apply(
                    new BlockPos(startFeet.getX(), y, startFeet.getZ())));
            if (obstruction != null) return new Result(obstruction, List.of());
        }
        return new Result(Status.READY, placements);
    }

    private static Status obstruction(CellKind cell) {
        return switch (Objects.requireNonNull(cell, "cell")) {
            case OPEN_DRY -> null;
            case SOLID -> Status.SHAFT_BLOCKED;
            case FLUID -> Status.SHAFT_FLUID;
            case UNLOADED -> Status.SHAFT_UNLOADED;
        };
    }
}
