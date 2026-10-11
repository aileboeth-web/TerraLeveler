package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules introduced by the 0.8.70 safety and robustness pass. */
final class SafetyRules0870Test {
    private static final WorkArea OPEN_AREA = new WorkArea() {
        @Override public int minX() { return -100; }
        @Override public int maxX() { return 100; }
        @Override public int minZ() { return -100; }
        @Override public int maxZ() { return 100; }
        @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
        @Override public boolean permitsScaffoldAt(int x, int z) { return false; }
    };

    @Test
    void hazardousBlocksAreRecognised() {
        assertTrue(HazardousBlocks.isHazard(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.FIRE.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.SOUL_FIRE.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.COBWEB.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.POWDER_SNOW.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.CACTUS.defaultBlockState()));
        assertTrue(HazardousBlocks.isHazard(Blocks.CAMPFIRE.defaultBlockState()));
        assertFalse(HazardousBlocks.isHazard(Blocks.STONE.defaultBlockState()));
        assertFalse(HazardousBlocks.isHazard(Blocks.DIRT.defaultBlockState()));
        assertFalse(HazardousBlocks.isHazard(Blocks.SHORT_GRASS.defaultBlockState()));
    }

    @Test
    void hazardousFeetOrFloorIsNeverAStandCell() {
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        cells.put(feet.below(), SafeRoutePlanner.CellKind.HAZARD);
        assertEquals(SafeRoutePlanner.StandStatus.BLOCKED,
                SafeRoutePlanner.inspectStandPosition(environment(cells), feet));

        cells.put(feet.below(), SafeRoutePlanner.CellKind.SOLID);
        cells.put(feet, SafeRoutePlanner.CellKind.HAZARD);
        assertEquals(SafeRoutePlanner.StandStatus.BLOCKED,
                SafeRoutePlanner.inspectStandPosition(environment(cells), feet));

        cells.remove(feet);
        assertEquals(SafeRoutePlanner.StandStatus.SAFE,
                SafeRoutePlanner.inspectStandPosition(environment(cells), feet));
    }

    @Test
    void routesDetourAroundAMagmaFloor() {
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 2; z++) cells.put(new BlockPos(x, 63, z), SafeRoutePlanner.CellKind.SOLID);
        }
        cells.put(new BlockPos(2, 63, 1), SafeRoutePlanner.CellKind.HAZARD);

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment(cells),
                new BlockPos(0, 64, 1), List.of(new BlockPos(4, 64, 1)), OPEN_AREA,
                SafeRoutePlanner.RoutePolicy.flatteningFloor());

        assertTrue(result.hasRoute());
        assertTrue(result.steps().stream().noneMatch(step -> step.position().equals(new BlockPos(2, 64, 1))));
    }

    @Test
    void aOneWideHazardCorridorHasNoRoute() {
        Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();
        for (int x = 0; x <= 4; x++) cells.put(new BlockPos(x, 63, 0), SafeRoutePlanner.CellKind.SOLID);
        cells.put(new BlockPos(2, 64, 0), SafeRoutePlanner.CellKind.HAZARD);
        // Walls on both sides so the only way is through the fire cell.
        for (int x = 0; x <= 4; x++) {
            cells.put(new BlockPos(x, 64, 1), SafeRoutePlanner.CellKind.SOLID);
            cells.put(new BlockPos(x, 65, 1), SafeRoutePlanner.CellKind.SOLID);
            cells.put(new BlockPos(x, 64, -1), SafeRoutePlanner.CellKind.SOLID);
            cells.put(new BlockPos(x, 65, -1), SafeRoutePlanner.CellKind.SOLID);
        }

        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(environment(cells),
                new BlockPos(0, 64, 0), List.of(new BlockPos(4, 64, 0)), OPEN_AREA,
                SafeRoutePlanner.RoutePolicy.flatteningFloor());

        assertFalse(result.hasRoute());
    }

    @Test
    void creepersAndPlayersAreNeverAutoAttacked() {
        assertTrue(DefensiveCombat.attackableTarget(false, false));
        assertFalse(DefensiveCombat.attackableTarget(true, false));
        assertFalse(DefensiveCombat.attackableTarget(false, true));
    }

    @Test
    void eatingWaitsUntilFoodIsNotWastedAndKeepsCommittedRoutes() {
        // The old rule ate at 19/20 and aborted routes.
        assertFalse(ConstructionController.shouldBeginEating(19, 20.0F, 20.0F, 12, false));
        assertTrue(ConstructionController.shouldBeginEating(14, 20.0F, 20.0F, 12, false));
        // Injured and below the natural regeneration level.
        assertTrue(ConstructionController.shouldBeginEating(17, 15.0F, 20.0F, 12, false));
        assertFalse(ConstructionController.shouldBeginEating(18, 15.0F, 20.0F, 12, false));
        // A committed route is only interrupted when starving or critical.
        assertFalse(ConstructionController.shouldBeginEating(14, 20.0F, 20.0F, 12, true));
        assertTrue(ConstructionController.shouldBeginEating(6, 20.0F, 20.0F, 12, true));
        assertTrue(ConstructionController.shouldBeginEating(19, 10.0F, 20.0F, 12, true));
        assertFalse(ConstructionController.shouldBeginEating(20, 5.0F, 20.0F, 12, false));
    }

    @Test
    void lowHealthPauseResumesWithHysteresis() {
        assertEquals(16.0F, ConstructionController.lowHealthResumeThreshold(12, 20.0F));
        assertEquals(20.0F, ConstructionController.lowHealthResumeThreshold(18, 20.0F));
    }

    @Test
    void anyHeldMovementKeyIsAManualTakeover() {
        assertFalse(ConstructionController.manualMovementKeysHeld(false, false, false, false, false));
        assertTrue(ConstructionController.manualMovementKeysHeld(true, false, false, false, false));
        assertTrue(ConstructionController.manualMovementKeysHeld(false, false, false, false, true));
    }

    @Test
    void valuableOrRiskyFoodIsNotEatenForOrdinaryHunger() {
        assertFalse(ConstructionController.isSafeOrdinaryFoodId("minecraft:golden_apple"));
        assertFalse(ConstructionController.isSafeOrdinaryFoodId("minecraft:enchanted_golden_apple"));
        assertFalse(ConstructionController.isSafeOrdinaryFoodId("minecraft:chicken"));
        assertTrue(ConstructionController.isSafeOrdinaryFoodId("minecraft:golden_carrot"));
        assertTrue(ConstructionController.isSafeOrdinaryFoodId("minecraft:cooked_chicken"));
    }

    @Test
    void grassGrownOnADirtScaffoldIsStillTheOwnedScaffold() {
        assertTrue(ConstructionController.scaffoldBlockMatches(Blocks.DIRT, Blocks.DIRT));
        assertTrue(ConstructionController.scaffoldBlockMatches(Blocks.DIRT, Blocks.GRASS_BLOCK));
        assertFalse(ConstructionController.scaffoldBlockMatches(Blocks.COBBLESTONE, Blocks.GRASS_BLOCK));
        assertFalse(ConstructionController.scaffoldBlockMatches(Blocks.DIRT, Blocks.AIR));
    }

    @Test
    void swordsAreKeptForCobwebsAndInstantMineBlocksOnly() {
        // sword, cobweb, block the sword mines instantly (bamboo)
        assertTrue(ServerActionExecutor.swordWastedOn(true, false, false));
        assertFalse(ServerActionExecutor.swordWastedOn(true, true, false));
        assertFalse(ServerActionExecutor.swordWastedOn(true, false, true));
        assertFalse(ServerActionExecutor.swordWastedOn(false, false, false));
    }

    @Test
    void ledgerScopeIsServerAndDimension() {
        assertTrue(ScaffoldLedgerStore.sameScope("FLATTEN|s1|minecraft:overworld|0|0|5|5|62|9",
                "TRENCH|s1|minecraft:overworld|1|1|2|2|-64"));
        assertFalse(ScaffoldLedgerStore.sameScope("FLATTEN|s1|minecraft:overworld|0",
                "FLATTEN|s2|minecraft:overworld|0"));
        assertFalse(ScaffoldLedgerStore.sameScope("FLATTEN|s1|minecraft:overworld|0",
                "FLATTEN|s1|minecraft:the_nether|0"));
    }

    @Test
    void restartedJobAdoptsOnlyOrphansInsideItsAreaAndScope() {
        Map<String, Map<String, String>> ledgers = new LinkedHashMap<>();
        ledgers.put("FLATTEN|s1|ow|old", new LinkedHashMap<>(Map.of(
                "1,70,1", "minecraft:dirt", "50,70,50", "minecraft:dirt")));
        ledgers.put("FLATTEN|s2|ow|other-server", new LinkedHashMap<>(Map.of("2,70,2", "minecraft:dirt")));

        int adopted = ScaffoldLedgerStore.adoptOrphans(ledgers, "FLATTEN|s1|ow|new",
                pos -> pos.getX() <= 10 && pos.getZ() <= 10);

        assertEquals(1, adopted);
        assertEquals(Map.of("1,70,1", "minecraft:dirt"), ledgers.get("FLATTEN|s1|ow|new"));
        assertEquals(Map.of("50,70,50", "minecraft:dirt"), ledgers.get("FLATTEN|s1|ow|old"));
        assertEquals(Map.of("2,70,2", "minecraft:dirt"), ledgers.get("FLATTEN|s2|ow|other-server"));
    }

    @Test
    void staleEntriesArePrunedAndEmptyJobsDisappear() {
        Map<String, Map<String, String>> ledgers = new LinkedHashMap<>();
        ledgers.put("FLATTEN|s1|ow|a", new LinkedHashMap<>(Map.of("1,70,1", "minecraft:dirt")));
        ledgers.put("FLATTEN|s1|ow|b", new LinkedHashMap<>(Map.of("2,70,2", "minecraft:dirt", "3,70,3", "minecraft:dirt")));
        ledgers.put("FLATTEN|s2|ow|c", new LinkedHashMap<>(Map.of("1,70,1", "minecraft:dirt")));

        int removed = ScaffoldLedgerStore.pruneSameScope(ledgers, "FLATTEN|s1|ow|x",
                (pos, block) -> pos.getX() != 3);

        assertEquals(2, removed);
        assertFalse(ledgers.containsKey("FLATTEN|s1|ow|a"));
        assertEquals(Map.of("3,70,3", "minecraft:dirt"), ledgers.get("FLATTEN|s1|ow|b"));
        assertTrue(ledgers.containsKey("FLATTEN|s2|ow|c"));
    }

    @Test
    void ledgerFileRoundTripsAndAnUnreadableFileIsKeptAside(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("terraleveler-scaffold-ledgers.json");
        ScaffoldLedgerStore store = new ScaffoldLedgerStore(file);
        store.load();
        store.replace("FLATTEN|s1|ow|job", Map.of("1,2,3", "minecraft:dirt"));
        assertTrue(store.saveNow());

        ScaffoldLedgerStore reloaded = new ScaffoldLedgerStore(file);
        reloaded.load();
        assertEquals(Map.of("1,2,3", "minecraft:dirt"), reloaded.ledger("FLATTEN|s1|ow|job"));

        Files.writeString(file, "{ this is not json");
        ScaffoldLedgerStore broken = new ScaffoldLedgerStore(file);
        broken.load();
        assertEquals(0, broken.jobCount());
        assertFalse(Files.exists(file));
        try (var listing = Files.list(directory)) {
            assertTrue(listing.anyMatch(path -> path.getFileName().toString().startsWith("terraleveler-scaffold-ledgers.json.broken-")));
        }
    }

    @Test
    void islandDetectionReadsEveryColumnAtMostOnce() {
        Set<Long> land = new HashSet<>();
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 12; z++) land.add(key(x, z));
        }
        Map<Long, Integer> reads = new HashMap<>();
        IslandFlattenAreaDetector.ColumnReader reader = (x, z) -> {
            reads.merge(key(x, z), 1, Integer::sum);
            return land.contains(key(x, z)) ? IslandFlattenAreaDetector.Cell.LAND : IslandFlattenAreaDetector.Cell.WATER;
        };

        IslandFlattenAreaDetector.Result result = IslandFlattenAreaDetector.detect(6, 6,
                new IslandFlattenAreaDetector.Settings(64, 10000, 1, true, true, 4), reader);

        assertTrue(result.ready());
        assertTrue(reads.values().stream().allMatch(count -> count == 1), "columns read more than once: " + reads);
    }

    @Test
    void tileSelectionMatchesTheExhaustiveScanOnRandomTerrain() {
        Random random = new Random(870);
        for (int round = 0; round < 25; round++) {
            List<BlockPos> targets = new ArrayList<>();
            int count = 50 + random.nextInt(400);
            for (int index = 0; index < count; index++) {
                targets.add(new BlockPos(random.nextInt(160) - 80, 62 + random.nextInt(12), random.nextInt(160) - 80));
            }
            BlockPos origin = new BlockPos(random.nextInt(200) - 100, 64, random.nextInt(200) - 100);

            assertEquals(referenceSchedule(targets, origin),
                    FlattenExcavationScheduler.schedule(targets, origin, Set.of()).targets(), "round " + round);
        }
    }

    /** The 0.8.69 scheduler, kept verbatim as the reference for the faster tile choice. */
    private static List<BlockPos> referenceSchedule(List<BlockPos> source, BlockPos origin) {
        Map<Long, List<BlockPos>> tiles = new HashMap<>();
        Set<BlockPos> unique = new LinkedHashSet<>();
        for (BlockPos candidate : source) {
            if (!unique.add(candidate)) continue;
            tiles.computeIfAbsent(key(Math.floorDiv(candidate.getX(), 16), Math.floorDiv(candidate.getZ(), 16)),
                    ignored -> new ArrayList<>()).add(candidate);
        }
        List<BlockPos> ordered = new ArrayList<>();
        BlockPos cursor = origin;
        while (!tiles.isEmpty()) {
            Long best = null;
            long bestDistance = Long.MAX_VALUE;
            for (Map.Entry<Long, List<BlockPos>> entry : tiles.entrySet()) {
                BlockPos finalCursor = cursor;
                long distance = entry.getValue().stream()
                        .mapToLong(target -> horizontal(target, finalCursor)).min().orElse(Long.MAX_VALUE);
                int tileX = (int) (entry.getKey() >> 32);
                int tileZ = (int) (long) entry.getKey();
                int bestX = best == null ? 0 : (int) (best >> 32);
                int bestZ = best == null ? 0 : (int) (long) best;
                if (best == null || distance < bestDistance
                        || distance == bestDistance && tileX < bestX
                        || distance == bestDistance && tileX == bestX && tileZ < bestZ) {
                    best = entry.getKey();
                    bestDistance = distance;
                }
            }
            List<BlockPos> tile = tiles.remove(best);
            Map<Integer, List<BlockPos>> layers = new TreeMap<>(Comparator.reverseOrder());
            for (BlockPos target : tile) layers.computeIfAbsent(target.getY(), ignored -> new ArrayList<>()).add(target);
            Comparator<BlockPos> positionOrder = Comparator.comparingInt((BlockPos pos) -> pos.getX())
                    .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY);
            for (List<BlockPos> layer : layers.values()) {
                List<BlockPos> remaining = new ArrayList<>(layer);
                while (!remaining.isEmpty()) {
                    int bestIndex = 0;
                    for (int index = 1; index < remaining.size(); index++) {
                        long candidateDistance = horizontal(remaining.get(index), cursor);
                        long currentBest = horizontal(remaining.get(bestIndex), cursor);
                        if (candidateDistance < currentBest || candidateDistance == currentBest
                                && positionOrder.compare(remaining.get(index), remaining.get(bestIndex)) < 0) {
                            bestIndex = index;
                        }
                    }
                    cursor = remaining.remove(bestIndex);
                    ordered.add(cursor);
                }
            }
        }
        return ordered;
    }

    private static long horizontal(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static SafeRoutePlanner.RouteEnvironment environment(Map<BlockPos, SafeRoutePlanner.CellKind> cells) {
        return position -> cells.getOrDefault(position, SafeRoutePlanner.CellKind.OPEN);
    }
}
