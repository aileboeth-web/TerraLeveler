package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Selects a loaded adjacent fall column with a solid floor for a water-bucket landing. */
final class WaterBucketFallPlanner {
    /** 0.8.92: no practical limit (the user accepts the risk of a missed placement); covers the whole world height. */
    static final int MAX_BUCKET_FALL = 384;
    static final int MIN_BUCKET_FALL = 4;
    static final int MAX_APPROACH_RADIUS = 12;
    private static final int MAX_APPROACH_VERTICAL = 6;
    private static final int MAX_APPROACH_CANDIDATES = 64;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    record Result(BlockPos origin, BlockPos landingFeet, BlockPos floor, int dropDistance) { }

    private WaterBucketFallPlanner() { }

    static Result findLanding(SafeRoutePlanner.RouteEnvironment environment,
                              BlockPos start, BlockPos task, int maximumDrop) {
        if (environment == null || start == null || task == null) return null;
        int limit = Math.min(MAX_BUCKET_FALL, maximumDrop);
        if (limit < MIN_BUCKET_FALL) return null;
        Result best = null;
        long bestScore = Long.MAX_VALUE;

        for (Direction direction : HORIZONTAL) {
            BlockPos columnTop = start.relative(direction);
            if (!openBody(environment, columnTop)) continue;

            for (int drop = 1; drop <= limit; drop++) {
                BlockPos landingFeet = columnTop.below(drop);
                SafeRoutePlanner.CellKind feet = environment.cell(landingFeet);
                SafeRoutePlanner.CellKind head = environment.cell(landingFeet.above());
                SafeRoutePlanner.CellKind floor = environment.cell(landingFeet.below());
                if (feet == SafeRoutePlanner.CellKind.OPEN
                        && head == SafeRoutePlanner.CellKind.OPEN
                        && floor == SafeRoutePlanner.CellKind.SOLID) {
                    if (drop >= MIN_BUCKET_FALL && landingFeet.getY() < start.getY()) {
                        long score = score(landingFeet, task);
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Result(start.immutable(), landingFeet.immutable(),
                                    landingFeet.below().immutable(), drop);
                        }
                    }
                    break;
                }
                if (feet != SafeRoutePlanner.CellKind.OPEN
                        || head != SafeRoutePlanner.CellKind.OPEN) break;
            }
        }
        return best;
    }

    static List<Result> findNearbyLandings(SafeRoutePlanner.RouteEnvironment environment,
                                           BlockPos start, BlockPos task, int maximumDrop,
                                           int horizontalRadius) {
        if (environment == null || start == null || task == null) return List.of();
        int radius = Math.min(MAX_APPROACH_RADIUS, Math.max(0, horizontalRadius));
        List<Result> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > radius) continue;
                for (int vertical = 0; vertical <= MAX_APPROACH_VERTICAL; vertical++) {
                    int[] offsets = vertical == 0 ? new int[]{0} : new int[]{-vertical, vertical};
                    for (int dy : offsets) {
                        BlockPos launch = start.offset(dx, dy, dz);
                        if (!standable(environment, launch)) continue;
                        Result landing = findLanding(environment, launch, task, maximumDrop);
                        if (landing != null && advancesTowardTask(start, landing.landingFeet(), task)) {
                            candidates.add(landing);
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingLong((Result result) -> approachScore(start, result.origin()))
                .thenComparingLong(result -> score(result.landingFeet(), task)));
        return candidates.stream().distinct().limit(MAX_APPROACH_CANDIDATES).toList();
    }

    static boolean advancesTowardTask(BlockPos start, BlockPos landing, BlockPos task) {
        if (start == null || landing == null || task == null || landing.equals(start)) return false;
        long startHorizontal = horizontalDistanceSquared(start, task);
        long landingHorizontal = horizontalDistanceSquared(landing, task);
        if (landingHorizontal != startHorizontal) return landingHorizontal < startHorizontal;
        return Math.abs((long) landing.getY() - task.getY())
                < Math.abs((long) start.getY() - task.getY());
    }

    private static boolean openBody(SafeRoutePlanner.RouteEnvironment environment, BlockPos feet) {
        return environment.cell(feet) == SafeRoutePlanner.CellKind.OPEN
                && environment.cell(feet.above()) == SafeRoutePlanner.CellKind.OPEN;
    }

    private static boolean standable(SafeRoutePlanner.RouteEnvironment environment, BlockPos feet) {
        return openBody(environment, feet)
                && environment.cell(feet.below()) == SafeRoutePlanner.CellKind.SOLID;
    }

    private static long approachScore(BlockPos start, BlockPos launch) {
        long dx = (long) launch.getX() - start.getX();
        long dy = (long) launch.getY() - start.getY();
        long dz = (long) launch.getZ() - start.getZ();
        return (dx * dx + dz * dz) * 16L + dy * dy * 4L;
    }

    private static long horizontalDistanceSquared(BlockPos position, BlockPos task) {
        long dx = (long) position.getX() - task.getX();
        long dz = (long) position.getZ() - task.getZ();
        return dx * dx + dz * dz;
    }

    private static long score(BlockPos landing, BlockPos task) {
        long dx = (long) landing.getX() - task.getX();
        long dy = (long) landing.getY() - task.getY();
        long dz = (long) landing.getZ() - task.getZ();
        return dy * dy * 16L + dx * dx + dz * dz;
    }
}
