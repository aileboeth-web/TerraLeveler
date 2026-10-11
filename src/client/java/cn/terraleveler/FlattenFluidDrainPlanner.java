package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Plans one bounded dirt-replacement transaction for fluid encountered by flattening. */
public final class FlattenFluidDrainPlanner {
    private static final int HORIZONTAL_SEAL_MARGIN = 1;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.UP
    };

    private FlattenFluidDrainPlanner() { }

    public enum Status {
        READY,
        TARGET_OUTSIDE_VOLUME,
        NOT_FLUID,
        WAIT_FOR_CHUNK,
        UNSUPPORTED_FLUID,
        EXTERNAL_INLET,
        NO_PLACEMENT_SUPPORT
    }

    public enum VerificationStatus {
        VERIFIED_DRY,
        WAIT_FOR_CHUNK,
        UNSUPPORTED_FLUID,
        EXTERNAL_INLET,
        REFLOW
    }

    @FunctionalInterface
    public interface Snapshot {
        FluidCellKind cellAt(BlockPos position);
    }

    public record Limits(int waterVerificationTicks, int lavaVerificationTicks,
                         int maxReflowAttempts) {
        public Limits {
            if (waterVerificationTicks < 1) throw new IllegalArgumentException("waterVerificationTicks must be positive");
            if (lavaVerificationTicks < 1) throw new IllegalArgumentException("lavaVerificationTicks must be positive");
            if (maxReflowAttempts < 0) throw new IllegalArgumentException("maxReflowAttempts cannot be negative");
        }
    }

    public record Placement(BlockPos target, BlockPos support) {
        public Placement {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(support, "support");
            target = target.immutable();
            support = support.immutable();
            if (target.distManhattan(support) != 1) {
                throw new IllegalArgumentException("placement support must be adjacent");
            }
        }
    }

    public record DrainPlan(List<BlockPos> fluidCells, List<Placement> placements,
                            List<BlockPos> externalWatchCells, int verificationTicks,
                            int maxReflowAttempts, boolean containsLava) {
        public DrainPlan {
            fluidCells = immutable(fluidCells);
            placements = List.copyOf(placements);
            externalWatchCells = immutable(externalWatchCells);
        }

        public int requiredDirt() {
            return placements.size();
        }
    }

    public record PlanResult(Status status, BlockPos problemPos, DrainPlan transaction) {
        public PlanResult {
            Objects.requireNonNull(status, "status");
            problemPos = problemPos == null ? null : problemPos.immutable();
        }

        public boolean ready() {
            return status == Status.READY && transaction != null;
        }
    }

    public record VerificationResult(VerificationStatus status, BlockPos problemPos) {
        public VerificationResult {
            Objects.requireNonNull(status, "status");
            problemPos = problemPos == null ? null : problemPos.immutable();
        }
    }

    public static PlanResult plan(FlattenPlan area, BlockPos start, Snapshot snapshot, Limits limits) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(limits, "limits");
        if (!insideDrainVolume(area, start)) {
            return failure(Status.TARGET_OUTSIDE_VOLUME, start);
        }

        FluidCellKind startKind = snapshot.cellAt(start);
        PlanResult startFailure = classifyFluidFailure(startKind, start);
        if (startFailure != null) return startFailure;
        if (!isSupportedFluid(startKind)) return failure(Status.NOT_FLUID, start);

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LinkedHashSet<BlockPos> component = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> watchCells = new LinkedHashSet<>();
        queue.add(start.immutable());
        boolean containsLava = false;

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (!component.add(current)) continue;
            containsLava |= snapshot.cellAt(current) == FluidCellKind.LAVA;

            for (Direction direction : Direction.values()) {
                BlockPos neighbour = current.relative(direction);
                boolean insideVolume = insideDrainVolume(area, neighbour);
                boolean relevant = insideVolume || canFeedDrainVolume(area, neighbour, direction);
                if (!relevant) continue;
                FluidCellKind kind = snapshot.cellAt(neighbour);
                PlanResult unsafe = classifyFluidFailure(kind, neighbour);
                if (unsafe != null) return unsafe;
                if (isSupportedFluid(kind)) {
                    if (insideVolume) {
                        if (!component.contains(neighbour)) queue.addLast(neighbour.immutable());
                    } else if (canFeedDrainVolume(area, neighbour, direction)) {
                        return failure(Status.EXTERNAL_INLET, neighbour);
                    }
                } else if (insideVolume && !insideWorkVolume(area, neighbour)) {
                    watchCells.add(neighbour.immutable());
                } else if (!insideVolume && canFeedDrainVolume(area, neighbour, direction)) {
                    watchCells.add(neighbour.immutable());
                }
            }
        }

        List<BlockPos> ordered = component.stream()
                .sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                        .thenComparingInt(pos -> horizontalDistance(start, pos))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
        LinkedHashSet<BlockPos> remaining = new LinkedHashSet<>(ordered);
        LinkedHashSet<BlockPos> planned = new LinkedHashSet<>();
        List<Placement> placements = new ArrayList<>(component.size());
        while (!remaining.isEmpty()) {
            boolean advanced = false;
            for (BlockPos target : List.copyOf(remaining)) {
                BlockPos support = placementSupport(target, planned, snapshot);
                if (support == null) continue;
                placements.add(new Placement(target, support));
                planned.add(target.immutable());
                remaining.remove(target);
                advanced = true;
            }
            if (!advanced) {
                return failure(Status.NO_PLACEMENT_SUPPORT, remaining.iterator().next());
            }
        }

        int verificationTicks = containsLava ? limits.lavaVerificationTicks() : limits.waterVerificationTicks();
        return new PlanResult(Status.READY, null,
                new DrainPlan(ordered, placements, List.copyOf(watchCells),
                        verificationTicks, limits.maxReflowAttempts(), containsLava));
    }

    public static VerificationResult verify(DrainPlan plan, Snapshot snapshot) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(snapshot, "snapshot");
        for (BlockPos watch : plan.externalWatchCells()) {
            FluidCellKind kind = snapshot.cellAt(watch);
            if (kind == FluidCellKind.UNLOADED) {
                return verification(VerificationStatus.WAIT_FOR_CHUNK, watch);
            }
            if (kind == FluidCellKind.OTHER_FLUID) {
                return verification(VerificationStatus.UNSUPPORTED_FLUID, watch);
            }
            if (isSupportedFluid(kind)) {
                return verification(VerificationStatus.EXTERNAL_INLET, watch);
            }
        }
        for (BlockPos cell : plan.fluidCells()) {
            FluidCellKind kind = snapshot.cellAt(cell);
            if (kind == FluidCellKind.UNLOADED) {
                return verification(VerificationStatus.WAIT_FOR_CHUNK, cell);
            }
            if (kind == FluidCellKind.OTHER_FLUID) {
                return verification(VerificationStatus.UNSUPPORTED_FLUID, cell);
            }
            if (isSupportedFluid(kind)) {
                return verification(VerificationStatus.REFLOW, cell);
            }
        }
        return verification(VerificationStatus.VERIFIED_DRY, null);
    }

    private static BlockPos placementSupport(BlockPos target, Set<BlockPos> planned, Snapshot snapshot) {
        for (Direction direction : SUPPORT_ORDER) {
            BlockPos candidate = target.relative(direction);
            if (planned.contains(candidate) || isStaticSupport(snapshot.cellAt(candidate))) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static boolean insideDrainVolume(FlattenPlan area, BlockPos position) {
        return position.getX() >= area.minX() - HORIZONTAL_SEAL_MARGIN
                && position.getX() <= area.maxX() + HORIZONTAL_SEAL_MARGIN
                && position.getZ() >= area.minZ() - HORIZONTAL_SEAL_MARGIN
                && position.getZ() <= area.maxZ() + HORIZONTAL_SEAL_MARGIN
                && position.getY() >= area.targetGroundY()
                && position.getY() <= area.scannedTopY();
    }

    private static boolean insideWorkVolume(FlattenPlan area, BlockPos position) {
        return position.getX() >= area.minX() && position.getX() <= area.maxX()
                && position.getZ() >= area.minZ() && position.getZ() <= area.maxZ()
                && position.getY() >= area.targetGroundY()
                && position.getY() <= area.scannedTopY();
    }

    private static boolean canFeedDrainVolume(FlattenPlan area, BlockPos neighbour, Direction directionFromVolume) {
        if (directionFromVolume.getAxis().isHorizontal()) {
            return false;
        }
        return directionFromVolume != Direction.DOWN || neighbour.getY() >= area.targetGroundY();
    }

    private static PlanResult classifyFluidFailure(FluidCellKind kind, BlockPos position) {
        if (kind == FluidCellKind.UNLOADED) {
            return failure(Status.WAIT_FOR_CHUNK, position);
        }
        if (kind == FluidCellKind.OTHER_FLUID) {
            return failure(Status.UNSUPPORTED_FLUID, position);
        }
        return null;
    }

    private static boolean isSupportedFluid(FluidCellKind kind) {
        return kind == FluidCellKind.WATER
                || kind == FluidCellKind.LAVA;
    }

    private static boolean isStaticSupport(FluidCellKind kind) {
        return kind == FluidCellKind.SOLID
                || kind == FluidCellKind.SAND
                || kind == FluidCellKind.WATERLOGGED_SOLID;
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
    }

    private static PlanResult failure(Status status, BlockPos position) {
        return new PlanResult(status, position, null);
    }

    private static VerificationResult verification(VerificationStatus status, BlockPos position) {
        return new VerificationResult(status, position);
    }

    private static List<BlockPos> immutable(List<BlockPos> positions) {
        return positions.stream().map(BlockPos::immutable).toList();
    }
}
