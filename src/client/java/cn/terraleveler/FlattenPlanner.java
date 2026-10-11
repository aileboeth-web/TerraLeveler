package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a bounded, server-revalidated cut-and-fill plan from the current snapshot. */
public final class FlattenPlanner {
    public enum Status {
        READY,
        WORLD_UNAVAILABLE,
        UNLOADED,
        PROTECTED_BLOCK_ENTITY,
        ISLAND_SEED_WATER,
        ISLAND_OPEN_BOUNDARY,
        ISLAND_TOO_LARGE,
        ISLAND_EMPTY,
        /** Island fully identified, but larger than live terrain: construct it region by region. */
        ISLAND_REGIONAL
    }

    public record Result(Status status, FlattenPlan plan, BlockPos problemPos,
                         int requiredDepth, String detail, IslandFlattenAreaDetector.Result island) {
        public Result(Status status, FlattenPlan plan, BlockPos problemPos, int requiredDepth, String detail) {
            this(status, plan, problemPos, requiredDepth, detail, null);
        }

        public boolean ready() { return status == Status.READY && plan != null; }

        public boolean regional() { return status == Status.ISLAND_REGIONAL && island != null; }
    }

    private FlattenPlanner() { }

    public static Result scan(Minecraft mc, BlockPos first, BlockPos second,
                              int targetGroundY,
                              boolean protectBlockEntities) {
        return scanColumns(mc, first, second, targetGroundY, protectBlockEntities,
                null, 0, 0, 0, 0);
    }

    static Result rescan(Minecraft mc, FlattenPlan previous, boolean protectBlockEntities) {
        return scanColumns(mc,
                new BlockPos(previous.minX(), previous.targetGroundY(), previous.minZ()),
                new BlockPos(previous.maxX(), previous.targetGroundY(), previous.maxZ()),
                previous.targetGroundY(), protectBlockEntities,
                previous.islandArea() ? previous.includedColumns() : null,
                previous.detectedLandColumns(), previous.shoreExcludedColumns(),
                previous.includedInteriorWaterColumns(), previous.narrowConnectionsCut());
    }

    static Result scanIsland(Minecraft mc, BlockPos seed, int targetGroundY,
                             boolean protectBlockEntities,
                             IslandFlattenAreaDetector.Settings settings,
                             IslandSurveyCache survey) {
        ScanJob job = beginIslandScan(mc, seed, targetGroundY, protectBlockEntities, settings, survey);
        while (!job.step(Long.MAX_VALUE)) { }
        return job.result();
    }

    /**
     * A flatten scan that can be spread over several client ticks (0.8.73): large islands
     * used to freeze the client for seconds while every column was classified and the
     * plan generated in one call. Each {@link #step} works until the given deadline.
     */
    interface ScanJob {
        boolean step(long deadlineNanos);
        Result result();
        /** Short progress text for the status line. */
        String progress();
    }

    static ScanJob beginRectangleScan(Minecraft mc, BlockPos first, BlockPos second,
                                      int targetGroundY, boolean protectBlockEntities) {
        return new PlanScanJob(mc, first, second, targetGroundY, protectBlockEntities, null, 0, 0, 0, 0);
    }

    static ScanJob beginIslandScan(Minecraft mc, BlockPos seed, int targetGroundY,
                                   boolean protectBlockEntities,
                                   IslandFlattenAreaDetector.Settings settings,
                                   IslandSurveyCache survey) {
        if (mc.level == null) return finished(new Result(Status.WORLD_UNAVAILABLE, null, null, 0, "世界尚未加载"));
        return new IslandScanJob(mc, seed, targetGroundY, protectBlockEntities, settings, survey);
    }

    private static ScanJob finished(Result result) {
        return new ScanJob() {
            @Override public boolean step(long deadlineNanos) { return true; }
            @Override public Result result() { return result; }
            @Override public String progress() { return "完成"; }
        };
    }

    private static final class IslandScanJob implements ScanJob {
        private final Minecraft mc;
        private final BlockPos seed;
        private final int targetGroundY;
        private final boolean protectBlockEntities;
        private final IslandSurveyCache survey;
        private final int[] cachedReads = new int[1];
        private final IslandFlattenAreaDetector.ColumnReader reader;
        private final IslandFlattenAreaDetector.Job detection;
        private PlanScanJob planning;
        private Result result;

        IslandScanJob(Minecraft mc, BlockPos seed, int targetGroundY, boolean protectBlockEntities,
                      IslandFlattenAreaDetector.Settings settings, IslandSurveyCache survey) {
            this.mc = mc;
            this.seed = seed;
            this.targetGroundY = targetGroundY;
            this.protectBlockEntities = protectBlockEntities;
            this.survey = survey;
            this.reader = new IslandFlattenAreaDetector.ColumnReader() {
                @Override public IslandFlattenAreaDetector.Cell read(int x, int z) {
                    IslandColumnClassifier.Classification column = surveyedIslandColumn(mc, seed, x, z, targetGroundY, survey);
                    if (column.cached()) cachedReads[0]++;
                    return column.cell();
                }
                @Override public String describe(int x, int z) {
                    return describeIslandColumn(mc, seed, x, z, targetGroundY, survey);
                }
                @Override public IslandFlattenAreaDetector.PathSample pathSample(int x, int z) {
                    IslandColumnClassifier.Classification column = surveyedIslandColumn(mc, seed, x, z, targetGroundY, survey);
                    String block = column.kind() == IslandColumnClassifier.Kind.UNLOADED ? "-"
                            : column.cached() ? "cached"
                            : BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(
                                    new BlockPos(x, column.surfaceY(), z)).getBlock()).toString().replace("minecraft:", "");
                    return new IslandFlattenAreaDetector.PathSample(block + "/" + column.kind(),
                            column.surfaceY(), column.subsurfaceWaterY());
                }
            };
            TerraLevelerDiagnostics.log("PLAN", "island-seed-column seed=" + seed.toShortString()
                    + " " + reader.describe(seed.getX(), seed.getZ()));
            this.detection = new IslandFlattenAreaDetector.Job(seed.getX(), seed.getZ(), settings, reader);
        }

        @Override
        public boolean step(long deadlineNanos) {
            if (result != null) return true;
            if (mc.level == null) {
                result = new Result(Status.WORLD_UNAVAILABLE, null, null, 0, "世界尚未加载");
                return true;
            }
            if (planning != null) {
                if (!planning.step(deadlineNanos)) return false;
                result = planning.result();
                return true;
            }
            if (!detection.step(deadlineNanos)) return false;
            IslandFlattenAreaDetector.Result detected = detection.result();
            if (!detected.ready()) {
                result = islandFailure(detected);
                return true;
            }
            int unloadedChunks = unloadedChunks(mc, detected.columns(), targetGroundY);
            TerraLevelerDiagnostics.log("PLAN", "island-survey-usage status=READY cachedReads=" + cachedReads[0]
                    + " surveyedChunks=" + surveyedChunks(survey) + " columns=" + detected.columns().size()
                    + " unloadedChunks=" + unloadedChunks + " foreignWater=" + detection.foreignWaterColumns());
            if (unloadedChunks > 0) {
                // The boundary is known, but a construction plan is a snapshot of
                // real blocks: never build one from remembered columns.
                result = new Result(Status.ISLAND_REGIONAL, null, null, 0,
                        "岛屿超出当前已加载范围，需分区施工", detected);
                return true;
            }
            Bounds bounds = bounds(detected.columns(), targetGroundY);
            planning = new PlanScanJob(mc, bounds.first(), bounds.second(), targetGroundY, protectBlockEntities,
                    detected.columns(), detected.connectedLandColumns(), detected.shoreExcludedColumns(),
                    detected.includedInteriorWaterColumns(), detected.narrowConnectionsCut());
            return step(deadlineNanos);
        }

        private Result islandFailure(IslandFlattenAreaDetector.Result detected) {
            Status status = switch (detected.status()) {
                case SEED_WATER -> Status.ISLAND_SEED_WATER;
                case UNLOADED -> Status.UNLOADED;
                case OPEN_BOUNDARY -> Status.ISLAND_OPEN_BOUNDARY;
                case TOO_LARGE -> Status.ISLAND_TOO_LARGE;
                case EMPTY_AFTER_INSET -> Status.ISLAND_EMPTY;
                case READY -> Status.WORLD_UNAVAILABLE;
            };
            BlockPos problem = detected.problemPos() == null ? null
                    : new BlockPos(detected.problemPos().getX(), targetGroundY, detected.problemPos().getZ());
            if (problem != null && detected.status() != IslandFlattenAreaDetector.Status.OPEN_BOUNDARY) {
                TerraLevelerDiagnostics.log("PLAN", "island-scan-problem-column status=" + detected.status()
                        + " column=" + problem.getX() + "," + problem.getZ()
                        + " " + reader.describe(problem.getX(), problem.getZ()));
            }
            if (!detected.diagnostics().isEmpty()) {
                TerraLevelerDiagnostics.log("PLAN", "island-scan-path status=" + detected.status()
                        + " " + detected.diagnostics());
            }
            String detail = detected.detail();
            if (detected.status() == IslandFlattenAreaDetector.Status.UNLOADED && !detected.gaps().isEmpty()) {
                List<IslandFlattenAreaDetector.GapCluster> clusters =
                        IslandFlattenAreaDetector.gapClusters(detected.gaps(), seed.getX(), seed.getZ());
                detail = gapSummary(clusters);
                TerraLevelerDiagnostics.log("PLAN", "island-scan-gaps columns=" + detected.gaps().size()
                        + " clusters=" + clusters.size() + " connected=" + detected.connectedLandColumns()
                        + " detail=" + clusters);
            }
            TerraLevelerDiagnostics.log("PLAN", "island-survey-usage status=" + detected.status()
                    + " cachedReads=" + cachedReads[0] + " surveyedChunks=" + surveyedChunks(survey));
            return new Result(status, null, problem, 0, detail, detected.gaps().isEmpty() ? null : detected);
        }

        @Override public Result result() { return result; }

        @Override
        public String progress() {
            if (planning != null) return "生成施工计划 " + planning.progress();
            return "识别岛屿边界：已检查 " + detection.examinedColumns() + " 列，连通陆地 " + detection.connectedColumns() + " 列";
        }
    }

    /**
     * Player-facing summary of unsurveyed parts of the island boundary: every cluster at
     * once (0.8.69 reported one point per press), with X/Z only. The Y of the old message
     * was the target height, not the ground there, so teleporting to it ended underground.
     */
    static String gapSummary(List<IslandFlattenAreaDetector.GapCluster> clusters) {
        if (clusters.isEmpty()) return "岛屿边界经过未加载且未勘测的区块";
        int chunks = clusters.stream().mapToInt(IslandFlattenAreaDetector.GapCluster::chunks).sum();
        StringBuilder text = new StringBuilder("岛屿边界还有 ").append(clusters.size()).append(" 处未勘测（共 ")
                .append(chunks).append(" 个区块）：");
        int shown = Math.min(4, clusters.size());
        for (int index = 0; index < shown; index++) {
            IslandFlattenAreaDetector.GapCluster cluster = clusters.get(index);
            if (index > 0) text.append("；");
            text.append(cluster.direction()).append("约 ").append(cluster.distance()).append(" 格（X=")
                    .append(cluster.nearestX()).append("，Z=").append(cluster.nearestZ()).append("）");
        }
        if (clusters.size() > shown) text.append("；另有 ").append(clusters.size() - shown).append(" 处更远");
        text.append("。坐标只有 X/Z，那里的地面高度未知");
        return text.toString();
    }

    /** Live-terrain plan for one region of a surveyed island; every region column must be loaded. */
    static Result scanIslandRegion(Minecraft mc, IslandRegionPlanner.Region region, int targetGroundY,
                                   boolean protectBlockEntities, IslandFlattenAreaDetector.Result island) {
        Bounds bounds = bounds(region.columns(), targetGroundY);
        return scanColumns(mc, bounds.first(), bounds.second(), targetGroundY, protectBlockEntities,
                region.columns(), island.connectedLandColumns(), island.shoreExcludedColumns(),
                island.includedInteriorWaterColumns(), island.narrowConnectionsCut());
    }

    /** True when the chunk is loaded with real terrain (not a client placeholder). */
    static boolean chunkLoadedWithTerrain(Minecraft mc, long chunkKey, int targetGroundY) {
        if (mc.level == null) return false;
        int x = ((int) (chunkKey >> 32) << 4) + 8;
        int z = ((int) chunkKey << 4) + 8;
        return mc.level.hasChunkAt(new BlockPos(x, targetGroundY, z)) && !isVoidPlaceholder(mc, x, z, targetGroundY);
    }

    private static int unloadedChunks(Minecraft mc, Set<BlockPos> columns, int targetGroundY) {
        Set<Long> chunks = new java.util.HashSet<>();
        for (BlockPos column : columns) chunks.add(IslandSurveyCache.chunkKey(column.getX() >> 4, column.getZ() >> 4));
        int unloaded = 0;
        for (long chunk : chunks) if (!chunkLoadedWithTerrain(mc, chunk, targetGroundY)) unloaded++;
        return unloaded;
    }

    private static int surveyedChunks(IslandSurveyCache survey) {
        return survey == null ? 0 : survey.fullyCapturedChunks();
    }

    /**
     * Records island classifications of loaded chunks around the player, nearest
     * unrecorded chunks first, then refreshes the oldest records.
     */
    static int surveyLoadedChunks(Minecraft mc, IslandSurveyCache survey, int targetGroundY,
                                  int chunkBudget, int chunkRadius, long tick, long refreshAfterTicks) {
        if (mc.level == null || mc.player == null || survey == null || chunkBudget <= 0) return 0;
        int centerX = mc.player.getBlockX() >> 4;
        int centerZ = mc.player.getBlockZ() >> 4;
        int captured = 0;
        // Refreshing already recorded chunks is low priority: at most one per 10 ticks.
        boolean refreshTick = tick % 10 == 0;
        long oldestTick = Long.MAX_VALUE;
        int oldestX = 0, oldestZ = 0;
        boolean haveOldest = false;
        for (int ring = 0; ring <= chunkRadius && captured < chunkBudget; ring++) {
            for (int dz = -ring; dz <= ring && captured < chunkBudget; dz++) {
                for (int dx = -ring; dx <= ring && captured < chunkBudget; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    long at = survey.capturedAt(chunkX, chunkZ);
                    boolean refreshCandidate = at != Long.MIN_VALUE && refreshTick
                            && tick - at >= refreshAfterTicks && at < oldestTick;
                    if (at != Long.MIN_VALUE && !refreshCandidate) continue;
                    if (!chunkLoadedWithTerrain(mc, IslandSurveyCache.chunkKey(chunkX, chunkZ), targetGroundY)) continue;
                    if (at == Long.MIN_VALUE) {
                        captureChunk(mc, survey, chunkX, chunkZ, targetGroundY, tick);
                        captured++;
                    } else {
                        oldestTick = at;
                        oldestX = chunkX;
                        oldestZ = chunkZ;
                        haveOldest = true;
                    }
                }
            }
        }
        if (captured == 0 && haveOldest) {
            captureChunk(mc, survey, oldestX, oldestZ, targetGroundY, tick);
            captured++;
        }
        return captured;
    }

    private static void captureChunk(Minecraft mc, IslandSurveyCache survey, int chunkX, int chunkZ,
                                     int targetGroundY, long tick) {
        byte[] kinds = new byte[256];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                kinds[localZ * 16 + localX] = classifyIslandColumn(mc, null,
                        (chunkX << 4) + localX, (chunkZ << 4) + localZ, targetGroundY).surveyKind();
            }
        }
        survey.putChunk(chunkX, chunkZ, kinds, tick);
    }

    /** Live classification when terrain is loaded (and recorded); otherwise the survey record, if any. */
    private static IslandColumnClassifier.Classification surveyedIslandColumn(
            Minecraft mc, BlockPos seed, int x, int z, int targetGroundY, IslandSurveyCache survey) {
        IslandColumnClassifier.Classification live = classifyIslandColumn(mc, seed, x, z, targetGroundY);
        if (survey == null) return live;
        if (live.kind() != IslandColumnClassifier.Kind.UNLOADED) {
            survey.put(x, z, live.surveyKind());
            return live;
        }
        return IslandColumnClassifier.Classification.fromSurvey(survey.get(x, z));
    }

    private static Result scanColumns(Minecraft mc, BlockPos first, BlockPos second,
                                      int targetGroundY, boolean protectBlockEntities,
                                      Set<BlockPos> includedColumns, int detectedLandColumns,
                                      int shoreExcludedColumns, int includedInteriorWaterColumns,
                                      int narrowConnectionsCut) {
        PlanScanJob job = new PlanScanJob(mc, first, second, targetGroundY, protectBlockEntities, includedColumns,
                detectedLandColumns, shoreExcludedColumns, includedInteriorWaterColumns, narrowConnectionsCut);
        while (!job.step(Long.MAX_VALUE)) { }
        return job.result();
    }

    /** The cut-and-fill scan, resumable between ticks; running it through gives the one-shot result. */
    private static final class PlanScanJob implements ScanJob {
        private enum Phase { COLUMNS, LAYERS, FILL, DONE }

        private final Minecraft mc;
        private final BlockPos first;
        private final BlockPos second;
        private final int targetGroundY;
        private final boolean protectBlockEntities;
        private final Set<BlockPos> includedColumns;
        private final int detectedLandColumns;
        private final int shoreExcludedColumns;
        private final int includedInteriorWaterColumns;
        private final int narrowConnectionsCut;
        private final List<BlockPos> columns;
        // Highest non-air block of every column. Cells above it are air, so the
        // layer scan can skip them instead of reading every column from the
        // tallest tree or hill in the whole area down to the target.
        private final java.util.Map<Long, Integer> columnTops;
        private final List<BlockPos> excavation = new ArrayList<>();
        private final List<BlockPos> treeLogs = new ArrayList<>();
        private final List<BlockPos> deferredFoliage = new ArrayList<>();
        private final List<BlockPos> fill = new ArrayList<>();
        private long estimatedExcavation;
        private Phase phase = Phase.COLUMNS;
        private int index;
        private int topY;
        private int y;
        private int layer;
        private List<BlockPos> row = List.of();
        private Result result;

        PlanScanJob(Minecraft mc, BlockPos first, BlockPos second, int targetGroundY, boolean protectBlockEntities,
                    Set<BlockPos> includedColumns, int detectedLandColumns, int shoreExcludedColumns,
                    int includedInteriorWaterColumns, int narrowConnectionsCut) {
            this.mc = mc;
            this.first = first;
            this.second = second;
            this.targetGroundY = targetGroundY;
            this.protectBlockEntities = protectBlockEntities;
            this.includedColumns = includedColumns;
            this.detectedLandColumns = detectedLandColumns;
            this.shoreExcludedColumns = shoreExcludedColumns;
            this.includedInteriorWaterColumns = includedInteriorWaterColumns;
            this.narrowConnectionsCut = narrowConnectionsCut;
            int minX = Math.min(first.getX(), second.getX());
            int maxX = Math.max(first.getX(), second.getX());
            int minZ = Math.min(first.getZ(), second.getZ());
            int maxZ = Math.max(first.getZ(), second.getZ());
            this.topY = targetGroundY;
            this.columns = columns(minX, maxX, minZ, maxZ, includedColumns);
            this.columnTops = new java.util.HashMap<>(this.columns.size() * 2);
        }

        @Override public Result result() { return result; }

        @Override
        public String progress() {
            return switch (phase) {
                case COLUMNS -> "读取地表 " + index + "/" + columns.size() + " 列";
                case LAYERS -> "扫描高度 Y=" + y + "（目标 " + targetGroundY + "）";
                case FILL -> "检查地面 " + index + "/" + columns.size() + " 列";
                case DONE -> "完成";
            };
        }

        @Override
        public boolean step(long deadlineNanos) {
            if (result != null) return true;
            if (mc.level == null) {
                result = new Result(Status.WORLD_UNAVAILABLE, null, null, 0, "世界尚未加载");
                return true;
            }
            int work = 0;
            while (result == null) {
                if ((++work & 255) == 0 && System.nanoTime() > deadlineNanos) return false;
                switch (phase) {
                    case COLUMNS -> stepColumn();
                    case LAYERS -> stepLayer();
                    case FILL -> stepFill();
                    case DONE -> finishPlan();
                }
            }
            return true;
        }

        private void stepColumn() {
            if (index >= columns.size()) {
                phase = Phase.LAYERS;
                y = topY;
                layer = 0;
                index = 0;
                row = List.of();
                return;
            }
            BlockPos column = columns.get(index++);
            BlockPos probe = new BlockPos(column.getX(), targetGroundY, column.getZ());
            if (!mc.level.hasChunkAt(probe)) {
                result = new Result(Status.UNLOADED, null, probe, 0, "平地选区包含未加载区块");
                return;
            }
            if (isVoidPlaceholder(mc, column.getX(), column.getZ(), targetGroundY)) {
                result = new Result(Status.UNLOADED, null, probe, 0, "平地选区包含尚未获得有效地形数据的区块");
                return;
            }
            int columnTop = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX(), column.getZ()) - 1;
            columnTops.put(columnKey(column.getX(), column.getZ()), columnTop);
            topY = Math.max(topY, columnTop);
        }

        private void stepLayer() {
            if (y <= targetGroundY) {
                phase = Phase.FILL;
                index = 0;
                return;
            }
            if (index == 0 && row.isEmpty()) {
                row = layer(columns, y);
                if ((layer & 1) != 0) java.util.Collections.reverse(row);
            }
            if (index >= row.size()) {
                y--;
                layer++;
                index = 0;
                row = List.of();
                return;
            }
            BlockPos pos = row.get(index++);
            if (columnTops.getOrDefault(columnKey(pos.getX(), pos.getZ()), Integer.MAX_VALUE) < y) return;
            if (protectBlockEntities && mc.level.getBlockEntity(pos) != null) {
                result = new Result(Status.PROTECTED_BLOCK_ENTITY, null, pos, 0, "削高区域存在受保护的容器或方块实体");
                return;
            }
            BlockState state = mc.level.getBlockState(pos);
            // Air used to occupy most of the execution queue. Skipping it
            // only at run time made consecutive real blocks hundreds of
            // metres apart. Queue only observable work; every target is
            // still revalidated immediately before the server action and
            // the whole area is scanned again before filling.
            FlattenExcavationPolicy.Category category = FlattenExcavationPolicy.classify(
                    state.isAir(), state.is(Blocks.BEDROCK), state.is(BlockTags.LEAVES),
                    state.is(BlockTags.LOGS));
            if (category == FlattenExcavationPolicy.Category.IGNORE) return;
            if (category == FlattenExcavationPolicy.Category.DEFERRED_FOLIAGE) {
                deferredFoliage.add(pos);
            } else if (category == FlattenExcavationPolicy.Category.TREE_LOG) {
                treeLogs.add(pos);
            } else {
                excavation.add(pos);
            }
            if (!(FluidBlocks.isPureFluid(state))) estimatedExcavation++;
        }

        // The visual contract is a one-block surface at targetGroundY. Do not
        // solid-fill caves, ravines, or water below it. Runtime placement grows
        // this skin horizontally from an existing neighbour (or immediate
        // support below) so the surface behaves like a thin construction deck.
        private void stepFill() {
            if (index >= columns.size()) {
                phase = Phase.DONE;
                return;
            }
            BlockPos column = columns.get(index++);
            BlockPos target = new BlockPos(column.getX(), targetGroundY, column.getZ());
            if (protectBlockEntities && mc.level.getBlockEntity(target) != null) {
                result = new Result(Status.PROTECTED_BLOCK_ENTITY, null, target, 0, "目标地面存在受保护的容器或方块实体");
                return;
            }
            BlockState targetState = mc.level.getBlockState(target);
            // The requested finish accepts natural dirt or grass. Existing
            // stone/ore/etc. at target Y is removed after the higher layers
            // and rebuilt as ordinary dirt; caves below remain untouched.
            boolean exactDirt = targetState.is(Blocks.DIRT);
            boolean grassBlock = targetState.is(Blocks.GRASS_BLOCK);
            boolean acceptedSurface = exactDirt || grassBlock;
            if (acceptedSurface && fullFloor(mc, target)) return;
            if (needsDirtSurfaceReplacement(exactDirt, grassBlock, targetState.isAir(),
                    FluidBlocks.isPureFluid(targetState), targetState.is(Blocks.BEDROCK))) {
                if (targetState.is(BlockTags.LOGS)) {
                    treeLogs.add(target);
                } else {
                    excavation.add(target);
                }
                estimatedExcavation++;
            }
            fill.add(target);
        }

        private void finishPlan() {
            fill.sort(java.util.Comparator.comparingLong((BlockPos pos) -> pos.getZ())
                    .thenComparingLong(pos -> pos.getX()));
            BoundaryFoliageClassifier.Result foliage = BoundaryFoliageClassifier.classify(treeLogs, deferredFoliage);
            FlattenPlan plan = new FlattenPlan(first, second, targetGroundY, topY,
                    excavation, treeLogs, deferredFoliage, foliage.interior(), fill, estimatedExcavation,
                    includedColumns, detectedLandColumns, shoreExcludedColumns,
                    includedInteriorWaterColumns, narrowConnectionsCut);
            TerraLevelerDiagnostics.log("PLAN", "flatten-foliage-classified interior="
                    + foliage.interior().size() + " boundary=" + foliage.boundary().size());
            result = new Result(Status.READY, plan, null, 0, "计划已就绪");
        }
    }

    private static IslandColumnClassifier.Classification classifyIslandColumn(
            Minecraft mc, BlockPos seed, int x, int z, int targetGroundY) {
        BlockPos probe = new BlockPos(x, targetGroundY, z);
        if (mc.level == null || !mc.level.hasChunkAt(probe)) {
            return new IslandColumnClassifier.Classification(
                    IslandColumnClassifier.Kind.UNLOADED, targetGroundY, Integer.MIN_VALUE);
        }
        // A client-side placeholder chunk reports void_air and the world's
        // minimum height. It is not an ocean column and must never become LAND.
        if (isVoidPlaceholder(mc, x, z, targetGroundY)) {
            return new IslandColumnClassifier.Classification(
                    IslandColumnClassifier.Kind.UNLOADED, targetGroundY, Integer.MIN_VALUE);
        }
        // Classify by the column's exposed surface: open water (shallow or
        // deep) is a boundary, water covered by solid ground is not. Any water
        // in a fixed Y window used to reject dry land above aquifers/caves.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        IslandColumnClassifier.Classification column = IslandColumnClassifier.classify(
                mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1,
                targetGroundY, mc.level.getMinY(), mc.level.getMaxY(),
                y -> islandSample(mc, cursor.set(x, y, z)));
        // The seed is where the player stood when pressing start. Dry feet on a
        // solid block prove land there even if water exists higher in the
        // column; the seed is never moved elsewhere.
        if (seed != null && x == seed.getX() && z == seed.getZ()
                && column.kind() == IslandColumnClassifier.Kind.OPEN_WATER
                && IslandColumnClassifier.seedStandsOnLand(
                        islandSample(mc, cursor.set(x, seed.getY(), z)),
                        islandSample(mc, cursor.set(x, seed.getY() - 1, z)))) {
            return new IslandColumnClassifier.Classification(
                    IslandColumnClassifier.Kind.SURFACE_LAND, seed.getY() - 1, Integer.MIN_VALUE);
        }
        return column;
    }

    private static IslandColumnClassifier.Sample islandSample(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.VOID_AIR)) return IslandColumnClassifier.Sample.VOID_AIR;
        if (state.isAir()) return IslandColumnClassifier.Sample.AIR;
        if (state.getFluidState().is(FluidTags.WATER)) return IslandColumnClassifier.Sample.WATER;
        // Lava is terrain for construction to handle, not an island water boundary.
        if (!state.getFluidState().isEmpty()) return IslandColumnClassifier.Sample.SOLID;
        if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) return IslandColumnClassifier.Sample.ICE;
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.LILY_PAD)) return IslandColumnClassifier.Sample.OVERLAY;
        if (state.getCollisionShape(mc.level, pos).isEmpty()) return IslandColumnClassifier.Sample.OVERLAY;
        return IslandColumnClassifier.Sample.SOLID;
    }

    private static boolean isVoidPlaceholder(Minecraft mc, int x, int z, int targetGroundY) {
        if (mc.level == null) return true;
        int minY = mc.level.getMinY();
        int worldSurface = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int oceanFloor = mc.level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        BlockState target = mc.level.getBlockState(new BlockPos(x, targetGroundY, z));
        BlockState surface = mc.level.getBlockState(new BlockPos(x, worldSurface, z));
        return worldSurface <= minY && oceanFloor <= minY
                && target.is(Blocks.VOID_AIR) && surface.is(Blocks.VOID_AIR);
    }

    private static String describeIslandColumn(Minecraft mc, BlockPos seed, int x, int z, int targetGroundY,
                                               IslandSurveyCache survey) {
        int worldSurface = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int oceanFloor = mc.level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        BlockState target = mc.level.getBlockState(new BlockPos(x, targetGroundY, z));
        BlockState surface = mc.level.getBlockState(new BlockPos(x, worldSurface, z));
        IslandColumnClassifier.Classification column = surveyedIslandColumn(mc, seed, x, z, targetGroundY, survey);
        String result = "worldSurface=" + worldSurface + " oceanFloor=" + oceanFloor
                + " target=" + target.getBlock().builtInRegistryHolder().key().toString()
                + " targetFluid=" + target.getFluidState().getType().toString()
                + " surface=" + surface.getBlock().builtInRegistryHolder().key().toString()
                + " surfaceFluid=" + surface.getFluidState().getType().toString()
                + " column=" + IslandColumnClassifier.describe(column)
                + " classified=" + column.cell();
        if (seed != null && x == seed.getX() && z == seed.getZ()) {
            result += " seedFeet=" + mc.level.getBlockState(seed).getBlock().builtInRegistryHolder().key()
                    + " seedSupport=" + mc.level.getBlockState(seed.below()).getBlock().builtInRegistryHolder().key();
        }
        return result;
    }

    static boolean islandWaterColumn(boolean surfaceWater, boolean belowWater, boolean surfaceFullFloor) {
        return surfaceWater || belowWater && !surfaceFullFloor;
    }

    private static List<BlockPos> columns(int minX, int maxX, int minZ, int maxZ,
                                          Set<BlockPos> includedColumns) {
        List<BlockPos> result = new ArrayList<>();
        if (includedColumns != null) {
            for (BlockPos column : includedColumns) result.add(new BlockPos(column.getX(), 0, column.getZ()));
        } else {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) result.add(new BlockPos(x, 0, z));
            }
        }
        result.sort(Comparator.comparingInt((BlockPos pos) -> pos.getZ())
                .thenComparingInt(pos -> pos.getX()));
        return result;
    }

    private static List<BlockPos> layer(List<BlockPos> columns, int y) {
        List<BlockPos> result = new ArrayList<>(columns.size());
        int currentZ = Integer.MIN_VALUE;
        boolean reverse = false;
        List<BlockPos> row = new ArrayList<>();
        for (BlockPos column : columns) {
            if (column.getZ() != currentZ && !row.isEmpty()) {
                if (reverse) java.util.Collections.reverse(row);
                result.addAll(row);
                row.clear();
                reverse = !reverse;
            }
            currentZ = column.getZ();
            row.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        if (reverse) java.util.Collections.reverse(row);
        result.addAll(row);
        return result;
    }

    private static Bounds bounds(Set<BlockPos> columns, int targetGroundY) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos column : columns) {
            minX = Math.min(minX, column.getX());
            maxX = Math.max(maxX, column.getX());
            minZ = Math.min(minZ, column.getZ());
            maxZ = Math.max(maxZ, column.getZ());
        }
        return new Bounds(new BlockPos(minX, targetGroundY, minZ),
                new BlockPos(maxX, targetGroundY, maxZ));
    }

    private record Bounds(BlockPos first, BlockPos second) { }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static boolean needsDirtSurfaceReplacement(boolean exactDirt, boolean grassBlock,
                                                boolean air, boolean liquid, boolean bedrock) {
        return !exactDirt && !grassBlock && !air && !liquid && !bedrock;
    }

    static List<BlockPos> rectangularLayer(int minX, int maxX, int minZ, int maxZ, int y) {
        List<BlockPos> result = new ArrayList<>();
        boolean reverse = false;
        for (int z = minZ; z <= maxZ; z++) {
            if (!reverse) {
                for (int x = minX; x <= maxX; x++) result.add(new BlockPos(x, y, z));
            } else {
                for (int x = maxX; x >= minX; x--) result.add(new BlockPos(x, y, z));
            }
            reverse = !reverse;
        }
        return result;
    }

    private static boolean fullFloor(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (FluidBlocks.isPureFluid(state)) return false;
        var shape = state.getCollisionShape(mc.level, pos);
        if (shape.isEmpty() || shape.toAabbs().size() != 1) return false;
        var bounds = shape.bounds();
        double epsilon = 1.0e-6;
        return bounds.minX <= epsilon && bounds.minY <= epsilon && bounds.minZ <= epsilon
                && bounds.maxX >= 1.0 - epsilon && bounds.maxY >= 1.0 - epsilon
                && bounds.maxZ >= 1.0 - epsilon;
    }
}
