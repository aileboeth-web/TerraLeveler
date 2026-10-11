package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class FinalScaffoldPillarPlanner {
    private static final Direction[] HORIZONTAL = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public enum CellKind {
        OPEN_DRY,
        SOLID,
        FLUID,
        UNLOADED
    }

    public enum Status {
        READY,
        NOT_FINAL_SINGLE_SCAFFOLD,
        TARGET_NOT_HIGH_ENOUGH,
        NO_CLEAR_COLUMN,
        PILLAR_TOO_TALL,
        INSUFFICIENT_MATERIAL
    }

    public record Candidate(BlockPos baseFeet, BlockPos topFeet, List<BlockPos> placements) {
        public Candidate {
            Objects.requireNonNull(baseFeet, "baseFeet");
            Objects.requireNonNull(topFeet, "topFeet");
            placements = List.copyOf(placements);
        }

        public int requiredBlocks() {
            return placements.size();
        }
    }

    public record Result(Status status, List<Candidate> candidates, int minimumRequiredBlocks) {
        public Result {
            Objects.requireNonNull(status, "status");
            candidates = List.copyOf(candidates);
        }

        public boolean ready() {
            return status == Status.READY && !candidates.isEmpty();
        }
    }

    private FinalScaffoldPillarPlanner() { }

    public static List<BlockPos> descentOrder(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<BlockPos> result = new ArrayList<>(candidate.placements());
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public static Result plan(
        BlockPos target,
        BlockPos start,
        boolean finalCleanup,
        int remainingScaffolds,
        int availableBlocks,
        int maximumPillarHeight,
        int minimumY,
        Function<BlockPos, CellKind> environment
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(environment, "environment");

        if (!finalCleanup || remainingScaffolds != 1) {
            return new Result(Status.NOT_FINAL_SINGLE_SCAFFOLD, List.of(), 0);
        }
        return planAccess(target, start, availableBlocks, maximumPillarHeight, minimumY, environment);
    }

    public static Result planAccess(
        BlockPos target,
        BlockPos start,
        int availableBlocks,
        int maximumPillarHeight,
        int minimumY,
        Function<BlockPos, CellKind> environment
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(environment, "environment");

        if (target.getY() < start.getY() + 4) {
            return new Result(Status.TARGET_NOT_HIGH_ENOUGH, List.of(), 0);
        }

        int heightLimit = Math.max(1, maximumPillarHeight);
        List<Candidate> geometric = new ArrayList<>();
        boolean heightLimited = false;
        for (Direction direction : HORIZONTAL) {
            BlockPos column = target.relative(direction);
            if (environment.apply(new BlockPos(column.getX(), target.getY(), column.getZ())) != CellKind.OPEN_DRY
                || environment.apply(new BlockPos(column.getX(), target.getY() - 1, column.getZ())) != CellKind.OPEN_DRY) {
                continue;
            }

            BlockPos support = null;
            int lowestScanY = Math.max(minimumY, target.getY() - 2 - heightLimit);
            for (int y = target.getY() - 2; y >= lowestScanY; y--) {
                CellKind cell = environment.apply(new BlockPos(column.getX(), y, column.getZ()));
                if (cell == CellKind.SOLID) {
                    support = new BlockPos(column.getX(), y, column.getZ());
                    break;
                }
                if (cell != CellKind.OPEN_DRY) {
                    support = null;
                    break;
                }
            }
            if (support == null) {
                if (lowestScanY > minimumY) heightLimited = true;
                continue;
            }

            BlockPos baseFeet = support.above();
            int finalPlacementY = target.getY() - 2;
            if (baseFeet.getY() > finalPlacementY) continue;
            List<BlockPos> placements = new ArrayList<>();
            for (int y = baseFeet.getY(); y <= finalPlacementY; y++) {
                placements.add(new BlockPos(column.getX(), y, column.getZ()));
            }
            geometric.add(new Candidate(baseFeet.immutable(), new BlockPos(column.getX(), target.getY() - 1, column.getZ()), placements));
        }

        if (geometric.isEmpty()) {
            return new Result(heightLimited ? Status.PILLAR_TOO_TALL : Status.NO_CLEAR_COLUMN, List.of(), 0);
        }

        geometric.sort(Comparator
            .comparingInt(Candidate::requiredBlocks)
            .thenComparingDouble(candidate -> candidate.baseFeet().distSqr(start)));
        int minimumRequired = geometric.getFirst().requiredBlocks();
        List<Candidate> affordable = geometric.stream()
            .filter(candidate -> candidate.requiredBlocks() <= availableBlocks)
            .toList();
        return affordable.isEmpty()
            ? new Result(Status.INSUFFICIENT_MATERIAL, List.of(), minimumRequired)
            : new Result(Status.READY, affordable, minimumRequired);
    }

    /** Free cells kept between the pillar top and the target, so the last jump does not hit it. */
    static final int UNDER_TARGET_HEADROOM = 2;

    /**
     * A pillar straight under the target (0.8.98): the player stands on its top with two free
     * blocks above the head and breaks the target overhead. In the 0.8.97 test all four side
     * columns of a block 38 above the ground ended on old scaffolds about 20 blocks up; the player
     * stood right under the target on open ground for 19 minutes without being able to start a
     * pillar, tearing down scaffolds and rebuilding a bridge in turn.
     */
    public static Result planUnderTarget(
        BlockPos target,
        int maximumPillarHeight,
        int minimumY,
        Function<BlockPos, CellKind> environment
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(environment, "environment");

        BlockPos topFeet = target.below(UNDER_TARGET_HEADROOM + 1);
        for (int y = target.getY() - 1; y >= topFeet.getY(); y--) {
            if (environment.apply(new BlockPos(target.getX(), y, target.getZ())) != CellKind.OPEN_DRY) {
                return new Result(Status.NO_CLEAR_COLUMN, List.of(), 0);
            }
        }

        int finalPlacementY = topFeet.getY() - 1;
        int lowestScanY = Math.max(minimumY, finalPlacementY - Math.max(1, maximumPillarHeight));
        BlockPos support = null;
        for (int y = finalPlacementY; y >= lowestScanY; y--) {
            CellKind cell = environment.apply(new BlockPos(target.getX(), y, target.getZ()));
            if (cell == CellKind.SOLID) {
                support = new BlockPos(target.getX(), y, target.getZ());
                break;
            }
            if (cell != CellKind.OPEN_DRY) {
                return new Result(Status.NO_CLEAR_COLUMN, List.of(), 0);
            }
        }
        if (support == null) {
            return new Result(lowestScanY > minimumY ? Status.PILLAR_TOO_TALL : Status.NO_CLEAR_COLUMN, List.of(), 0);
        }

        BlockPos baseFeet = support.above();
        if (baseFeet.getY() > finalPlacementY) {
            return new Result(Status.TARGET_NOT_HIGH_ENOUGH, List.of(), 0);
        }
        List<BlockPos> placements = new ArrayList<>();
        for (int y = baseFeet.getY(); y <= finalPlacementY; y++) {
            placements.add(new BlockPos(target.getX(), y, target.getZ()));
        }
        Candidate candidate = new Candidate(baseFeet.immutable(), topFeet.immutable(), placements);
        return new Result(Status.READY, List.of(candidate), candidate.requiredBlocks());
    }
}
