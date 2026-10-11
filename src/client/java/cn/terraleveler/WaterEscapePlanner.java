package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Finds a nearby dry bank for the real player after an accidental fall into water. */
public final class WaterEscapePlanner {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int MIN_CANDIDATE_DY = 0;
    private static final int MAX_CANDIDATE_DY = 5;
    private static final int MIN_WATER_DY = -4;
    private static final int MAX_WATER_VISITS = 4_096;

    public interface Environment {
        boolean loaded(BlockPos position);
        boolean safeDryStand(BlockPos feet);
        boolean water(BlockPos position);

        /** Optional clearance check for the air cell used while climbing onto a bank. */
        default boolean passable(BlockPos position) { return true; }
    }

    /** A reachable bank plus the shortest connected-water route leading to its exit edge. */
    public record EscapePlan(BlockPos bank, List<BlockPos> waterWaypoints) {
        public EscapePlan {
            bank = bank.immutable();
            waterWaypoints = waterWaypoints.stream().map(BlockPos::immutable).toList();
            if (waterWaypoints.isEmpty()) throw new IllegalArgumentException("waterWaypoints cannot be empty");
        }
    }

    private record BankCandidate(BlockPos exitWater, int routeCost) { }

    private WaterEscapePlanner() { }

    public static Optional<BlockPos> nearestDryExit(Environment environment, BlockPos origin, int radius) {
        return nearestDryEscape(environment, origin, radius).map(EscapePlan::bank);
    }

    public static Optional<EscapePlan> nearestDryEscape(Environment environment, BlockPos origin, int radius) {
        if (radius < 1) return Optional.empty();
        Optional<BlockPos> seed = waterSeed(environment, origin);
        if (seed.isEmpty()) return Optional.empty();

        Set<BlockPos> reachableWater = new HashSet<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Map<BlockPos, Integer> waterDistance = new HashMap<>();
        Map<BlockPos, BankCandidate> dryBanks = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        reachableWater.add(seed.get());
        waterDistance.put(seed.get(), 0);
        open.add(seed.get());

        while (!open.isEmpty()) {
            BlockPos water = open.removeFirst();
            int distance = waterDistance.get(water);
            for (Direction direction : HORIZONTAL) {
                collectBank(environment, origin, radius, water, water.relative(direction),
                        distance + 1, false, dryBanks);
                // A one-block-high bank is swimmable from the water immediately
                // beside and below it, matching the original bank predicate.
                collectBank(environment, origin, radius, water, water.relative(direction).above(),
                        distance + 2, true, dryBanks);
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = water.relative(direction);
                if (!insideWaterSearch(origin, next, radius)
                        || reachableWater.contains(next)
                        || reachableWater.size() >= MAX_WATER_VISITS
                        || !environment.loaded(next)
                        || !environment.passable(next)
                        || !environment.water(next)) continue;
                BlockPos immutable = next.immutable();
                reachableWater.add(immutable);
                previous.put(immutable, water);
                waterDistance.put(immutable, distance + 1);
                open.addLast(immutable);
            }
        }

        return dryBanks.entrySet().stream()
                .min(Comparator.<Map.Entry<BlockPos, BankCandidate>>comparingInt(
                                entry -> entry.getValue().routeCost())
                        .thenComparingLong(entry -> score(origin, entry.getKey()))
                        .thenComparingInt(entry -> entry.getKey().getX())
                        .thenComparingInt(entry -> entry.getKey().getY())
                        .thenComparingInt(entry -> entry.getKey().getZ()))
                .map(entry -> new EscapePlan(entry.getKey(),
                        reconstructWaterPath(seed.get(), entry.getValue().exitWater(), previous)));
    }

    private static Optional<BlockPos> waterSeed(Environment environment, BlockPos origin) {
        BlockPos[] candidates = {
                origin, origin.below(), origin.above(),
                origin.north(), origin.south(), origin.west(), origin.east()
        };
        for (BlockPos candidate : candidates) {
            if (environment.loaded(candidate) && environment.passable(candidate)
                    && environment.water(candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static void collectBank(Environment environment, BlockPos origin, int radius,
                                    BlockPos exitWater, BlockPos candidate, int routeCost,
                                    boolean elevated, Map<BlockPos, BankCandidate> dryBanks) {
        if (insideCandidateSearch(origin, candidate, radius)
                && environment.loaded(candidate)
                && (!elevated || environment.passable(exitWater.above()))
                && environment.safeDryStand(candidate)) {
            BlockPos immutable = candidate.immutable();
            BankCandidate incoming = new BankCandidate(exitWater.immutable(), routeCost);
            dryBanks.merge(immutable, incoming,
                    (oldValue, newValue) -> oldValue.routeCost() <= newValue.routeCost()
                            ? oldValue : newValue);
        }
    }

    private static List<BlockPos> reconstructWaterPath(BlockPos seed, BlockPos exit,
                                                       Map<BlockPos, BlockPos> previous) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = exit;
        reversed.add(cursor);
        while (!cursor.equals(seed)) {
            cursor = previous.get(cursor);
            if (cursor == null) throw new IllegalStateException("Broken water BFS predecessor chain");
            reversed.add(cursor);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static boolean insideCandidateSearch(BlockPos origin, BlockPos position, int radius) {
        long dx = Math.abs((long) position.getX() - origin.getX());
        long dz = Math.abs((long) position.getZ() - origin.getZ());
        long dy = (long) position.getY() - origin.getY();
        return dx <= radius && dz <= radius
                && dy >= MIN_CANDIDATE_DY && dy <= MAX_CANDIDATE_DY;
    }

    private static boolean insideWaterSearch(BlockPos origin, BlockPos position, int radius) {
        long dx = Math.abs((long) position.getX() - origin.getX());
        long dz = Math.abs((long) position.getZ() - origin.getZ());
        long dy = (long) position.getY() - origin.getY();
        // Water may dip below the eventual bank while following a bounded
        // connected route, but the selected dry exit itself is never below origin.
        return dx <= radius && dz <= radius
                && dy >= MIN_WATER_DY && dy <= MAX_CANDIDATE_DY;
    }

    private static long score(BlockPos origin, BlockPos candidate) {
        long dx = (long) candidate.getX() - origin.getX();
        long dz = (long) candidate.getZ() - origin.getZ();
        long dy = Math.abs((long) candidate.getY() - origin.getY());
        return dx * dx + dz * dz + dy * 4L;
    }
}
