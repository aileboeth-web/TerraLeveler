package cn.terraleveler;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;

public final class TerraLeveler implements ClientModInitializer {
    public static final String MOD_ID = "terraleveler";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static String VERSION = "unknown";
    public static TerraLevelerConfig CONFIG;
    /** TerraLeveler-owned scaffold records, stored outside terraleveler.json (see ScaffoldLedgerStore). */
    static final ScaffoldLedgerStore SCAFFOLD_LEDGERS = ScaffoldLedgerStore.createDefault();
    public static final ConstructionController CONTROLLER = new ConstructionController();
    static final LightingController LIGHTING_CONTROLLER = new LightingController();
    public static BlockPos FLATTEN_FIRST;
    public static BlockPos FLATTEN_SECOND;
    public static Integer FLATTEN_TARGET_Y;
    public static BlockPos LIGHTING_FIRST;
    public static BlockPos LIGHTING_SECOND;
    public static WorkMode ACTIVE_MODE = WorkMode.FLATTEN;
    private static ResourceKey<Level> flattenSelectionDimension;
    private static ResourceKey<Level> lightingSelectionDimension;
    private static FlattenPlanner.Result cachedFlattenResult;
    private static String cachedFlattenKey;
    private static long confirmedIslandFingerprint = Long.MIN_VALUE;
    private static BlockPos flattenIslandSeed;
    /** Server/world identity used to prevent island scan caches leaking across server switches. */
    private static String lastWorldScope;
    private static long lastSelectionPressPosition = Long.MIN_VALUE;
    private static boolean lastSelectionPressAttack;
    private static long lastSelectionPressMillis;
    /** Island columns observed while loaded; lets boundary detection cross chunks the server has unloaded. */
    static final IslandSurveyCache ISLAND_SURVEY = new IslandSurveyCache();
    /** Survey records on disk, one file per server/dimension/target-Y scope (0.8.73). */
    static final IslandSurveyStore ISLAND_SURVEY_STORE = IslandSurveyStore.createDefault();
    private static final long ISLAND_SURVEY_SAVE_TICKS = 1200L;
    private static final int ISLAND_SURVEY_CHUNKS_PER_TICK = 2;
    private static final int ISLAND_SURVEY_CHUNK_RADIUS = 16;
    private static final long ISLAND_SURVEY_REFRESH_TICKS = 1200L;
    private static long islandSurveyTick;
    private static IslandRegionJob islandJob;
    /** Flatten scan spread over ticks (0.8.73) and the cache key it will be stored under. */
    private static FlattenPlanner.ScanJob pendingFlattenScan;
    private static String pendingFlattenScanKey;
    private static long pendingFlattenScanStartedNanos;
    private static long awaitingLoadSinceMillis;
    private static int pendingScanStatusTicks;
    private static long lastArrivalMillis = Long.MIN_VALUE / 2;
    private static net.minecraft.world.phys.Vec3 lastTrackedPlayerPosition;
    /** Work per client tick for a pending scan; the rest of the tick stays responsive. */
    static final long FLATTEN_SCAN_SLICE_NANOS = 8_000_000L;
    static final long ARRIVAL_SETTLE_MILLIS = 10_000L;
    static final long LOAD_WAIT_MAXIMUM_MILLIS = 15_000L;
    static final int ARRIVAL_LOAD_CHUNK_RADIUS = 3;

    /** A confirmed island that is constructed one live-planned region at a time. */
    private static final class IslandRegionJob {
        final IslandFlattenAreaDetector.Result island;
        final java.util.List<IslandRegionPlanner.Region> regions;
        final java.util.List<IslandRegionPlanner.Region> remaining;
        final int targetY;
        final String scope;
        final ResourceKey<Level> dimension;
        final int[] bounds;
        IslandRegionPlanner.Region current;
        int completed;
        boolean awaiting;
        String awaitingDetail = "";

        IslandRegionJob(IslandFlattenAreaDetector.Result island, java.util.List<IslandRegionPlanner.Region> regions,
                        int targetY, String scope, ResourceKey<Level> dimension) {
            this.island = island;
            this.regions = java.util.List.copyOf(regions);
            this.remaining = new java.util.ArrayList<>(regions);
            this.targetY = targetY;
            this.scope = scope;
            this.dimension = dimension;
            this.bounds = columnBounds(island.columns());
        }
    }

    private boolean startWasDown;
    private boolean stopWasDown;
    private boolean heightWasDown;
    private boolean retainWasDown;
    private int statusTicks;
    private ClientLevel lastLevel;

    @Override
    public void onInitializeClient() {
        java.util.List<String> migrated = LegacyFileMigration.migrate(FabricLoader.getInstance().getConfigDir());
        if (!migrated.isEmpty()) LOGGER.info("Copied old WETA files to TerraLeveler names: {}", migrated);
        CONFIG = TerraLevelerConfig.load();
        ACTIVE_MODE = WorkMode.parse(CONFIG.activeWorkMode);
        VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        TerraLevelerDiagnostics.initialize(VERSION, CONFIG.diagnosticsEnabled);
        loadScaffoldLedgers();
        registerMalilibConfigScreen();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SCAFFOLD_LEDGERS.saveBlocking();
            ISLAND_SURVEY_STORE.saveBlocking(ISLAND_SURVEY);
            TerraLevelerDiagnostics.close();
        });
        registerAppleSelection();
        LOGGER.info("TerraLeveler initialized for Minecraft 26.1.2");
    }

    /** Loads terraleveler-scaffold-ledgers.json and moves ledgers older versions kept in terraleveler.json. */
    private static void loadScaffoldLedgers() {
        SCAFFOLD_LEDGERS.load();
        if (CONFIG.ownedScaffoldLedgers == null || CONFIG.ownedScaffoldLedgers.isEmpty()) return;
        SCAFFOLD_LEDGERS.importLegacy(CONFIG.ownedScaffoldLedgers);
        if (SCAFFOLD_LEDGERS.saveNow()) {
            int jobs = CONFIG.ownedScaffoldLedgers.size();
            CONFIG.ownedScaffoldLedgers.clear();
            CONFIG.save();
            LOGGER.info("Moved {} TerraLeveler scaffold ledgers from terraleveler.json to terraleveler-scaffold-ledgers.json", jobs);
        }
    }

    /** Apple clicks while a job runs were silently ignored before 0.9.12. */
    private static String selectionLockedMessage() {
        return "TerraLeveler：施工进行中，苹果选点无效（这次点击已忽略）；要换区域请先按" + keyHint(1)
                + "暂停，或按" + keyHint(0) + "停止。";
    }

    /** One chat line; the detection figures show when the mouse rests on "[详细]" in the open chat. */
    private static Component islandPreview(String summary, String details) {
        return Component.literal(summary).append(Component.literal(" [详细]").withStyle(style -> style
                .withColor(net.minecraft.ChatFormatting.GRAY)
                .withUnderlined(true)
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(details)))));
    }

    private static String keyHint(int action) {
        return KeyHintText.hint(action);
    }

    private void registerAppleSelection() {
        KeyHintText.boundKeys = TerraLeveler::boundKeyDisplayName;
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            boolean appleHeld = player.getItemInHand(hand).is(Items.APPLE);
            if (level.isClientSide() && appleHeld && repeatedSelectionPress(pos, true)) {
                return InteractionResult.SUCCESS;
            }
            if (level.isClientSide() && ACTIVE_MODE == WorkMode.FLATTEN
                    && CONFIG.flattenAutoIslandEnabled && appleHeld) {
                rememberSelectionPress(pos, true);
                player.sendSystemMessage(Component.literal("TerraLeveler 平地机：自动岛屿模式已开启，不需要苹果角点；用" + keyHint(2) + "记录目标高度后，站在岛上按" + keyHint(0) + "。"));
                return InteractionResult.SUCCESS;
            }
            if (!SelectionInteractionRules.mayChangeSelection(
                    level.isClientSide(), CONFIG.appleSelectionEnabled, appleHeld, activeSelectionMutable())) {
                if (level.isClientSide() && CONFIG.appleSelectionEnabled && appleHeld) {
                    TerraLevelerDiagnostics.log("SELECTION", "ignored-active left state=" + activeStateName() + " pos=" + pos.toShortString());
                    if (playerPressing(true)) {
                        rememberSelectionPress(pos, true);
                        player.sendSystemMessage(Component.literal(selectionLockedMessage()));
                        return InteractionResult.SUCCESS;
                    }
                }
                return InteractionResult.PASS;
            }
            rememberSelectionPress(pos, true);
            resetActiveForNewSelection();
            if (ACTIVE_MODE == WorkMode.FLATTEN) {
                FLATTEN_FIRST = pos.immutable();
                FLATTEN_SECOND = null;
                flattenSelectionDimension = level.dimension();
            } else {
                LIGHTING_FIRST = pos.immutable();
                LIGHTING_SECOND = null;
                lightingSelectionDimension = level.dimension();
            }
            invalidatePlanCache();
            saveSelection(Minecraft.getInstance());
            TerraLevelerDiagnostics.log("SELECTION", "mode=" + ACTIVE_MODE + " first=" + pos.toShortString() + " second=cleared");
            player.sendSystemMessage(Component.literal("TerraLeveler " + ACTIVE_MODE.label()
                    + "：旧区域已清除。角点一 = " + pos.toShortString() + "，请右键设置角点二。"));
            return InteractionResult.SUCCESS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            boolean appleHeld = player.getItemInHand(hand).is(Items.APPLE);
            if (level.isClientSide() && appleHeld && repeatedSelectionPress(hit.getBlockPos(), false)) {
                return InteractionResult.SUCCESS;
            }
            if (level.isClientSide() && ACTIVE_MODE == WorkMode.FLATTEN
                    && CONFIG.flattenAutoIslandEnabled && appleHeld) {
                rememberSelectionPress(hit.getBlockPos(), false);
                player.sendSystemMessage(Component.literal("TerraLeveler 平地机：自动岛屿模式已开启，不需要苹果角点；用" + keyHint(2) + "记录目标高度后，站在岛上按" + keyHint(0) + "。"));
                return InteractionResult.SUCCESS;
            }
            if (!SelectionInteractionRules.mayChangeSelection(
                    level.isClientSide(), CONFIG.appleSelectionEnabled, appleHeld, activeSelectionMutable())) {
                if (level.isClientSide() && CONFIG.appleSelectionEnabled && appleHeld) {
                    TerraLevelerDiagnostics.log(
                            "SELECTION",
                            "ignored-active right state=" + activeStateName() + " pos=" + hit.getBlockPos().toShortString()
                    );
                    if (playerPressing(false)) {
                        rememberSelectionPress(hit.getBlockPos(), false);
                        player.sendSystemMessage(Component.literal(selectionLockedMessage()));
                        return InteractionResult.SUCCESS;
                    }
                }
                return InteractionResult.PASS;
            }
            BlockPos first = activeFirst();
            ResourceKey<Level> dimension = activeSelectionDimension();
            if (first == null || dimension == null || !dimension.equals(level.dimension())) {
                player.sendSystemMessage(Component.literal("TerraLeveler：请先在当前维度左键设置角点一。"));
                return InteractionResult.SUCCESS;
            }
            rememberSelectionPress(hit.getBlockPos(), false);
            resetActiveForNewSelection();
            if (ACTIVE_MODE == WorkMode.FLATTEN) FLATTEN_SECOND = hit.getBlockPos().immutable();
            else LIGHTING_SECOND = hit.getBlockPos().immutable();
            invalidatePlanCache();
            saveSelection(Minecraft.getInstance());
            BlockPos second = activeSecond();
            TerraLevelerDiagnostics.log("SELECTION", "mode=" + ACTIVE_MODE + " first=" + first.toShortString()
                    + " second=" + second.toShortString());
            player.sendSystemMessage(Component.literal("TerraLeveler " + ACTIVE_MODE.label() + "：角点二 = "
                    + second.toShortString() + "，新区域已覆盖旧区域。"));
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * The player is holding the mouse button themselves. TerraLeveler's own breaks also pass
     * through these callbacks (startDestroyBlock) and must never be cancelled by the hint.
     */
    private static boolean playerPressing(boolean attack) {
        Minecraft mc = Minecraft.getInstance();
        return mc.options != null && (attack ? mc.options.keyAttack.isDown() : mc.options.keyUse.isDown());
    }

    /** Swallows the repeated callbacks of an apple press TerraLeveler already handled. */
    private static boolean repeatedSelectionPress(BlockPos pos, boolean attack) {
        long now = System.currentTimeMillis();
        if (!SelectionInteractionRules.isRepeatedPress(lastSelectionPressPosition, lastSelectionPressAttack,
                lastSelectionPressMillis, pos.asLong(), attack, now)) {
            return false;
        }
        lastSelectionPressMillis = now;
        return true;
    }

    private static void rememberSelectionPress(BlockPos pos, boolean attack) {
        lastSelectionPressPosition = pos.asLong();
        lastSelectionPressAttack = attack;
        lastSelectionPressMillis = System.currentTimeMillis();
    }

    private void tick(Minecraft mc) {
        String worldScope = mc.level == null ? null : currentSelectionScope(mc);
        if (mc.level != null && !java.util.Objects.equals(worldScope, lastWorldScope)) {
            if (lastWorldScope != null) {
                invalidatePlanCache();
                TerraLevelerDiagnostics.log("LIFECYCLE", "server-scope-changed old=" + lastWorldScope
                        + " new=" + worldScope + " island-cache-cleared");
            }
            lastWorldScope = worldScope;
        }
        if (mc.level != lastLevel) {
            CONTROLLER.onWorldChanged(mc);
            LIGHTING_CONTROLLER.onWorldChanged(mc);
            FLATTEN_FIRST = null;
            FLATTEN_SECOND = null;
            FLATTEN_TARGET_Y = null;
            LIGHTING_FIRST = null;
            LIGHTING_SECOND = null;
            flattenSelectionDimension = null;
            lightingSelectionDimension = null;
            invalidatePlanCache();
            lastLevel = mc.level;
            lastArrivalMillis = System.currentTimeMillis();
            lastTrackedPlayerPosition = null;
            restoreSelection(mc);
        }
        // The settings screen is where shortcuts are rebound; keys pressed there must not act.
        boolean shortcutCapture = mc.screen instanceof TerraLevelerMalilibConfigScreen;
        boolean startDown = !shortcutCapture && mc.screen == null && keyDown(mc, CONFIG.startPauseKey);
        // emergencyStopKey is the pause/resume binding; like every other TerraLeveler key it
        // must not fire while chat, a sign or an inventory screen receives typing.
        boolean stopDown = !shortcutCapture && mc.screen == null && keyDown(mc, CONFIG.emergencyStopKey);
        boolean heightDown = !shortcutCapture && mc.screen == null && keyDown(mc, CONFIG.flattenHeightKey);
        boolean retainDown = !shortcutCapture && mc.screen == null && CONFIG.customRetainedItemsEnabled
                && keyDown(mc, CONFIG.retainItemKey);
        if (shortcutCapture) {
            startWasDown = false;
            stopWasDown = false;
            heightWasDown = false;
            retainWasDown = false;
        } else {
            if (startDown && !startWasDown) toggleConstruction(mc);
            if (stopDown && !stopWasDown) startOrPauseActive(mc);
            if (heightDown && !heightWasDown) recordFlattenHeight(mc);
            if (retainDown && !retainWasDown) captureRetainedItems(mc);
            startWasDown = startDown;
            stopWasDown = stopDown;
            heightWasDown = heightDown;
            retainWasDown = retainDown;
        }
        BoxScreenHider.tick(mc);
        if (ACTIVE_MODE == WorkMode.LIGHTING) LIGHTING_CONTROLLER.tick(mc, CONFIG);
        else CONTROLLER.tick(mc, CONFIG);
        trackArrival(mc);
        if (ACTIVE_MODE == WorkMode.FLATTEN) {
            tickIslandSurvey(mc);
            tickIslandRegionJob(mc);
            tickPendingFlattenScan(mc);
        } else if (pendingFlattenScanKey != null) {
            clearPendingFlattenScan("mode-changed");
        }
        if (mc.player != null && activeStatusVisible() && ++statusTicks >= 10) {
            statusTicks = 0;
            mc.player.sendOverlayMessage(Component.literal(statusLine()));
        }
    }

    static boolean startOrPauseActive(Minecraft mc) {
        if (ACTIVE_MODE == WorkMode.LIGHTING) {
            LightingPlan plan = currentLightingPlan(mc);
            if (plan == null) {
                if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                        "TerraLeveler 区域照明：请先手拿苹果，左键和右键设置三维区域的两个角点。"));
                return false;
            }
            LIGHTING_CONTROLLER.toggle(plan);
            return true;
        }
        boolean controllerIdle = CONTROLLER.state() == ConstructionController.State.IDLE
                || CONTROLLER.state() == ConstructionController.State.COMPLETE;
        if (islandJob != null) {
            if (!controllerIdle) {
                // A region plan is running or paused: plain pause/resume.
                CONTROLLER.toggle((FlattenPlan) null);
                return true;
            }
            if (islandJob.awaiting && playerOnJobIsland(mc)) return advanceIslandJob(mc);
            clearIslandJob(islandJob.awaiting ? "player-left-island" : "stale");
        }
        if (!controllerIdle) {
            // Pausing or resuming a live job must never depend on re-planning the
            // half-finished terrain: an unloaded chunk would otherwise block the key.
            CONTROLLER.toggle((FlattenPlan) null);
            return true;
        }
        if (controllerIdle) {
            cachedFlattenResult = null;
            cachedFlattenKey = null;
            if (CONFIG.flattenAutoIslandEnabled && mc.player != null) {
                // Keep confirmedIslandFingerprint: the confirming press re-seeds
                // too, and only an identical island geometry may skip the preview.
                flattenIslandSeed = mc.player.blockPosition().immutable();
                TerraLevelerDiagnostics.log("PLAN", "flatten-island-seed x=" + flattenIslandSeed.getX()
                        + " y=" + flattenIslandSeed.getY() + " z=" + flattenIslandSeed.getZ()
                        + " dimension=" + mc.level.dimension().identifier());
            }
        }
        FlattenPlanner.Result result = flattenResultOrBeginScan(mc);
        // null: the scan continues over the next ticks and finishes this press itself.
        if (result == null) return true;
        return continueFlattenStart(mc, result, controllerIdle);
    }

    /** Second half of a flatten start press, once the scan result exists (now or after a pending scan). */
    private static boolean continueFlattenStart(Minecraft mc, FlattenPlanner.Result result, boolean controllerIdle) {
        if (result.regional() && CONFIG.flattenAutoIslandEnabled && controllerIdle) {
            IslandFlattenAreaDetector.Result island = result.island();
            java.util.List<IslandRegionPlanner.Region> regions =
                    IslandRegionPlanner.partition(island.columns(), IslandRegionPlanner.REGION_CHUNKS);
            long fingerprint = FlattenPlan.columnsFingerprint(island.columns());
            if (confirmedIslandFingerprint != fingerprint) {
                confirmedIslandFingerprint = fingerprint;
                int[] bounds = columnBounds(island.columns());
                int regionBlocks = IslandRegionPlanner.REGION_CHUNKS * 16;
                if (mc.player != null) mc.player.sendSystemMessage(islandPreview(
                        IslandPreviewText.summary(island.columns().size(), bounds[0], bounds[1], bounds[2], bounds[3],
                                regions.size(), keyHint(0)),
                        IslandPreviewText.details(island.connectedLandColumns(), island.shoreExcludedColumns(),
                                island.includedInteriorWaterColumns(), island.narrowConnectionsCut(), regionBlocks)));
                TerraLevelerDiagnostics.log("PLAN", "flatten-island-preview regional=true fingerprint="
                        + Long.toUnsignedString(fingerprint) + " columns=" + island.columns().size()
                        + " connected=" + island.connectedLandColumns() + " shoreExcluded="
                        + island.shoreExcludedColumns() + " interiorWater="
                        + island.includedInteriorWaterColumns() + " narrowCuts=" + island.narrowConnectionsCut()
                        + " regions=" + regions.size() + " bounds=x" + bounds[0] + ".." + bounds[1]
                        + ",z" + bounds[2] + ".." + bounds[3]);
                return true;
            }
            confirmedIslandFingerprint = Long.MIN_VALUE;
            startIslandJob(mc, island, regions);
            return true;
        }
        if (!result.ready()) {
            String hint = CONFIG.flattenAutoIslandEnabled && controllerIdle
                    && result.status() == FlattenPlanner.Status.UNLOADED
                    ? (result.island() != null && !result.island().gaps().isEmpty()
                    ? "。TerraLeveler 会自动记录你走过时已加载的岛屿区块（重启游戏也会保留），请依次走近这些位置后再按" + keyHint(0)
                    : "；该处区块尚未加载且没有勘测记录。TerraLeveler 会自动记录你走过时已加载的岛屿区块，请朝该位置走近一段后再按" + keyHint(0))
                    : "";
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                    "TerraLeveler 平地机：" + flattenPlanProblem(result) + hint));
            return false;
        }
        if (CONFIG.flattenAutoIslandEnabled
                && (CONTROLLER.state() == ConstructionController.State.IDLE
                || CONTROLLER.state() == ConstructionController.State.COMPLETE)) {
            long fingerprint = result.plan().geometryFingerprint();
            if (confirmedIslandFingerprint != fingerprint) {
                confirmedIslandFingerprint = fingerprint;
                FlattenPlan plan = result.plan();
                if (mc.player != null) mc.player.sendSystemMessage(islandPreview(
                        IslandPreviewText.summary(plan.columns(), plan.minX(), plan.maxX(), plan.minZ(), plan.maxZ(),
                                0, keyHint(0)),
                        IslandPreviewText.details(plan.detectedLandColumns(), plan.shoreExcludedColumns(),
                                plan.includedInteriorWaterColumns(), plan.narrowConnectionsCut(), 0)));
                TerraLevelerDiagnostics.log("PLAN", "flatten-island-preview fingerprint="
                        + Long.toUnsignedString(fingerprint) + " columns=" + plan.columns()
                        + " connected=" + plan.detectedLandColumns() + " shoreExcluded="
                        + plan.shoreExcludedColumns() + " interiorWater="
                        + plan.includedInteriorWaterColumns() + " narrowCuts=" + plan.narrowConnectionsCut());
                return true;
            }
        }
        confirmedIslandFingerprint = Long.MIN_VALUE;
        CONTROLLER.toggle(result.plan());
        return true;
    }

    private static void recordFlattenHeight(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (ACTIVE_MODE != WorkMode.FLATTEN) {
            mc.player.sendSystemMessage(Component.literal("TerraLeveler：请先在配置界面切换到“平地机”，再按" + keyHint(2) + "记录高度。"));
            return;
        }
        OptionalInt ground = PlayerPhysicalState.integerGroundY(mc);
        if (ground.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal(
                    "TerraLeveler 平地机：请稳定站在完整方块顶面后再按" + keyHint(2) + "；半砖、楼梯和腾空状态不会记录。"));
            return;
        }
        String problem = applyFlattenTargetY(mc, ground.getAsInt(), "hotkey");
        if (problem != null) {
            mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：" + problem));
            return;
        }
        CONFIG.save();
        mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：已记录目标地面方块 Y="
                + FLATTEN_TARGET_Y + "（玩家站立 Y=" + (FLATTEN_TARGET_Y + 1) + "）。"));
    }

    static Integer flattenTargetYForSettings(Minecraft mc) {
        return mc != null && mc.level != null ? FLATTEN_TARGET_Y : CONFIG.lastFlattenTargetY;
    }

    static String applyFlattenTargetYFromSettings(Minecraft mc, Integer targetY) {
        return applyFlattenTargetY(mc, targetY, "settings");
    }

    private static String applyFlattenTargetY(Minecraft mc, Integer targetY, String source) {
        if (targetY != null && (targetY < TerraLevelerConfig.MIN_FLATTEN_TARGET_Y
                || targetY > TerraLevelerConfig.MAX_FLATTEN_TARGET_Y)) {
            return "目标地面 Y 必须在 -64 到 512 之间";
        }
        if (mc == null || mc.level == null) {
            return Objects.equals(CONFIG.lastFlattenTargetY, targetY)
                    ? null : "请进入世界后再设置目标高度";
        }
        boolean sameDimension = flattenSelectionDimension != null
                && flattenSelectionDimension.equals(mc.level.dimension());
        if (Objects.equals(FLATTEN_TARGET_Y, targetY) && sameDimension) {
            storeFlattenSelection(mc);
            return null;
        }
        if (ACTIVE_MODE == WorkMode.FLATTEN) {
            ConstructionController.State state = CONTROLLER.state();
            if (state != ConstructionController.State.IDLE
                    && state != ConstructionController.State.COMPLETE
                    && state != ConstructionController.State.PAUSED) {
                return "请先暂停施工，再修改目标高度";
            }
            CONTROLLER.resetForNewSelection();
        }
        FLATTEN_TARGET_Y = targetY;
        flattenSelectionDimension = mc.level.dimension();
        clearIslandPreview();
        storeFlattenSelection(mc);
        TerraLevelerDiagnostics.log("SELECTION", "mode=FLATTEN targetGroundY="
                + (targetY == null ? "cleared" : targetY) + " source=" + source);
        return null;
    }

    private static boolean keyDown(Minecraft mc, String keyName) {
        ShortcutBinding binding = configuredBinding(keyName);
        return binding.key() != InputConstants.UNKNOWN && binding.key().getType() == InputConstants.Type.KEYSYM
                && InputConstants.isKeyDown(mc.getWindow(), binding.key().getValue())
                && modifiersMatch(mc, binding);
    }

    private static void captureRetainedItems(Minecraft mc) {
        if (mc.player == null || !CONFIG.customRetainedItemsEnabled) return;
        LinkedHashSet<String> ids = new LinkedHashSet<>(CONFIG.retainedItemIds);
        if (CONFIG.retainEntireInventory) {
            int before = ids.size();
            for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            CONFIG.retainedItemIds = new java.util.ArrayList<>(ids);
            CONFIG.save();
            CONTROLLER.onRetentionConfigChanged();
            mc.player.sendSystemMessage(Component.literal("TerraLeveler：已记录当前全背包物品类型，新增 "
                    + (ids.size() - before) + " 种，共保留 " + ids.size() + " 种。"));
            return;
        }
        Item item = mc.player.getMainHandItem().getItem();
        if (mc.player.getMainHandItem().isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("TerraLeveler：请在主手拿着要切换保留状态的物品。"));
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        boolean added = ids.add(id);
        if (!added) ids.remove(id);
        CONFIG.retainedItemIds = new java.util.ArrayList<>(ids);
        CONFIG.save();
        CONTROLLER.onRetentionConfigChanged();
        mc.player.sendSystemMessage(Component.literal("TerraLeveler：" + id + (added ? " 已加入" : " 已移出") + "自定义保留清单。"));
    }

    /**
     * The construction switch (0.9.02): a press starts when nothing runs and stops a job
     * that is running or paused (or an island scan in progress); the next press starts
     * again from a fresh plan. Pause/resume has its own key.
     */
    static boolean toggleConstruction(Minecraft mc) {
        boolean flatten = ACTIVE_MODE == WorkMode.FLATTEN;
        ConstructionSwitchRules.Action action = ConstructionSwitchRules.action(
                constructionSwitchOn(),
                flatten && pendingFlattenScanKey != null,
                flatten && islandJob != null && islandJob.awaiting
                        && CONTROLLER.state() == ConstructionController.State.COMPLETE && playerOnJobIsland(mc));
        if (action == ConstructionSwitchRules.Action.STOP) {
            stopFromSwitch(mc);
            return true;
        }
        if (action == ConstructionSwitchRules.Action.CANCEL_SCAN) {
            clearPendingFlattenScan("switch-stop");
            clearIslandJob("switch-stop");
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                    "TerraLeveler 平地机：已取消识别；再按" + keyHint(0) + "重新开始。"));
            return true;
        }
        // Waiting between regions: the construction switch continues the job.
        if (action == ConstructionSwitchRules.Action.NEXT_ISLAND_REGION) return advanceIslandJob(mc);
        if (ACTIVE_MODE == WorkMode.LIGHTING) {
            if (LIGHTING_CONTROLLER.state() == LightingController.State.COMPLETE) LIGHTING_CONTROLLER.stop();
            return startOrPauseActive(mc);
        }
        if (CONTROLLER.state() == ConstructionController.State.COMPLETE && islandJob == null) {
            // A finished plan keeps the preview confirmation; geometry is still compared.
            CONTROLLER.stop();
        }
        return startOrPauseActive(mc);
    }

    /** Whether the construction switch is "on": a job of the active mode is running or paused. */
    private static boolean constructionSwitchOn() {
        if (ACTIVE_MODE == WorkMode.LIGHTING) {
            LightingController.State state = LIGHTING_CONTROLLER.state();
            return state != LightingController.State.IDLE && state != LightingController.State.COMPLETE;
        }
        ConstructionController.State state = CONTROLLER.state();
        return state != ConstructionController.State.IDLE && state != ConstructionController.State.COMPLETE;
    }

    /**
     * Stops the active job. An island job is dropped, but its confirmed preview is kept:
     * starting again on the same island skips the second confirmation press (the
     * geometry is still compared), a different island is previewed again.
     */
    private static void stopFromSwitch(Minecraft mc) {
        if (ACTIVE_MODE == WorkMode.LIGHTING) {
            LIGHTING_CONTROLLER.stop();
        } else {
            clearPendingFlattenScan("switch-stop");
            clearIslandJob("switch-stop");
            CONTROLLER.stop();
        }
        TerraLevelerDiagnostics.log("STATE", "switch-stop mode=" + ACTIVE_MODE + " state=" + activeStateName());
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("TerraLeveler " + ACTIVE_MODE.label()
                + "：已停止" + (constructionSwitchOn() ? "（正在安全收尾，完成后停下）" : "")
                + "；再按" + keyHint(0) + "重新开始" + (ACTIVE_MODE == WorkMode.FLATTEN ? "，已平整的部分会跳过" : "") + "。"));
    }

    static String boundKeyDisplayName(int action) {
        ShortcutBinding binding = configuredBinding(boundKeyNameValue(action));
        if (binding.key() == InputConstants.UNKNOWN) return "无";
        StringBuilder label = new StringBuilder();
        if (binding.control()) label.append("Ctrl + ");
        if (binding.shift()) label.append("Shift + ");
        if (binding.alt()) label.append("Alt + ");
        return label.append(binding.key().getDisplayName().getString()).toString();
    }

    static void setBoundKeyComposite(int action, net.minecraft.client.input.KeyEvent event) {
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(event.key());
        if (key == InputConstants.UNKNOWN || isModifierKey(event.key())) {
            setBoundKeyNameValue(action, "");
        } else {
            StringBuilder value = new StringBuilder();
            if (event.hasControlDown()) value.append("ctrl+");
            if (event.hasShiftDown()) value.append("shift+");
            if (event.hasAltDown()) value.append("alt+");
            value.append(key.getName());
            setBoundKeyNameValue(action, value.toString());
        }
        CONFIG.save();
    }

    static void clearBoundKey(int action) {
        setBoundKeyNameValue(action, "");
        CONFIG.save();
    }

    static boolean isModifierKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
                || keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT
                || keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT;
    }

    private static ShortcutBinding configuredBinding(String value) {
        if (value == null || value.isBlank()) return ShortcutBinding.empty();
        String[] parts = value.split("\\+");
        if (parts.length == 0) return ShortcutBinding.empty();
        boolean control = false;
        boolean shift = false;
        boolean alt = false;
        for (int index = 0; index < parts.length - 1; index++) {
            switch (parts[index]) {
                case "ctrl" -> control = true;
                case "shift" -> shift = true;
                case "alt" -> alt = true;
                default -> { return ShortcutBinding.empty(); }
            }
        }
        return new ShortcutBinding(configuredKey(parts[parts.length - 1]), control, shift, alt);
    }

    private static boolean modifiersMatch(Minecraft mc, ShortcutBinding binding) {
        boolean control = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean shift = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean alt = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        return control == binding.control() && shift == binding.shift() && alt == binding.alt();
    }

    private record ShortcutBinding(InputConstants.Key key, boolean control, boolean shift, boolean alt) {
        private static ShortcutBinding empty() {
            return new ShortcutBinding(InputConstants.UNKNOWN, false, false, false);
        }
    }
    private static InputConstants.Key configuredKey(String keyName) {
        if (keyName == null || keyName.isBlank()) return InputConstants.UNKNOWN;
        try {
            return InputConstants.getKey(keyName);
        } catch (IllegalArgumentException ignored) {
            return InputConstants.UNKNOWN;
        }
    }

    /** Shortcut actions: 0 construction switch, 1 pause/resume, 2 record flatten height, 3 record retained item. */
    private static String boundKeyNameValue(int action) {
        return action == 0 ? CONFIG.startPauseKey
                : action == 1 ? CONFIG.emergencyStopKey
                : action == 2 ? CONFIG.flattenHeightKey : CONFIG.retainItemKey;
    }

    private static void setBoundKeyNameValue(int action, String value) {
        if (action == 0) CONFIG.startPauseKey = value;
        else if (action == 1) CONFIG.emergencyStopKey = value;
        else if (action == 2) CONFIG.flattenHeightKey = value;
        else CONFIG.retainItemKey = value;
    }

    public static boolean setActiveMode(WorkMode mode) {
        if (mode == null || mode == ACTIVE_MODE) return true;
        ConstructionController.State state = CONTROLLER.state();
        if (state != ConstructionController.State.IDLE
                && state != ConstructionController.State.COMPLETE) return false;
        if (!LIGHTING_CONTROLLER.modeSwitchSafe()) return false;
        clearIslandPreview();
        ACTIVE_MODE = mode;
        CONFIG.activeWorkMode = mode.name();
        CONFIG.save();
        return true;
    }

    public static BlockPos activeFirst() {
        return ACTIVE_MODE == WorkMode.FLATTEN ? FLATTEN_FIRST : LIGHTING_FIRST;
    }

    public static BlockPos activeSecond() {
        return ACTIVE_MODE == WorkMode.FLATTEN ? FLATTEN_SECOND : LIGHTING_SECOND;
    }

    static LightingPlan currentLightingPlan(Minecraft mc) {
        if (LIGHTING_FIRST == null || LIGHTING_SECOND == null || mc.level == null
                || lightingSelectionDimension == null
                || !lightingSelectionDimension.equals(mc.level.dimension())) return null;
        return new LightingPlan(LIGHTING_FIRST, LIGHTING_SECOND);
    }

    static String activeDetail() {
        if (ACTIVE_MODE == WorkMode.FLATTEN && islandJob != null) {
            String prefix = "岛屿区域 " + (islandJob.completed + (islandJob.current == null ? 0 : 1))
                    + "/" + islandJob.regions.size() + "：";
            return prefix + (islandJob.awaiting ? islandJob.awaitingDetail : CONTROLLER.detail());
        }
        return ACTIVE_MODE == WorkMode.LIGHTING ? LIGHTING_CONTROLLER.detail() : CONTROLLER.detail();
    }

    /** Status bar text (0.9.10): mode, what it is doing in words, progress, detail. */
    private static String statusLine() {
        String progress;
        if (ACTIVE_MODE == WorkMode.LIGHTING) {
            progress = StatusText.percent(LIGHTING_CONTROLLER.placed(), LIGHTING_CONTROLLER.candidates(),
                    LIGHTING_CONTROLLER.state() == LightingController.State.COMPLETE);
        } else {
            progress = StatusText.percent(CONTROLLER.completed(), CONTROLLER.total(),
                    CONTROLLER.state() == ConstructionController.State.COMPLETE);
        }
        return "TerraLeveler " + ACTIVE_MODE.label() + "｜" + StatusText.stateLabel(activeStateName())
                + (progress.isEmpty() ? "" : " " + progress) + "｜" + activeDetail();
    }

    static String activeStateName() {
        return ACTIVE_MODE == WorkMode.LIGHTING
                ? LIGHTING_CONTROLLER.state().name() : CONTROLLER.state().name();
    }

    static void stopActive() {
        if (ACTIVE_MODE == WorkMode.LIGHTING) LIGHTING_CONTROLLER.stop();
        else {
            if (ACTIVE_MODE == WorkMode.FLATTEN) clearIslandPreview();
            CONTROLLER.stop();
        }
    }

    private static boolean activeStatusVisible() {
        return ACTIVE_MODE == WorkMode.LIGHTING
                ? LIGHTING_CONTROLLER.state() != LightingController.State.IDLE
                : CONTROLLER.state() != ConstructionController.State.IDLE
                && (CONTROLLER.state() != ConstructionController.State.COMPLETE
                        || ACTIVE_MODE == WorkMode.FLATTEN && islandJob != null);
    }

    private static boolean activeSelectionMutable() {
        return ACTIVE_MODE == WorkMode.LIGHTING
                ? LIGHTING_CONTROLLER.selectionMutable()
                : SelectionInteractionRules.mayChangeSelection(true, true, true, CONTROLLER.state());
    }

    private static void resetActiveForNewSelection() {
        if (ACTIVE_MODE == WorkMode.LIGHTING) LIGHTING_CONTROLLER.resetForNewSelection();
        else CONTROLLER.resetForNewSelection();
    }

    private static ResourceKey<Level> activeSelectionDimension() {
        return ACTIVE_MODE == WorkMode.FLATTEN ? flattenSelectionDimension : lightingSelectionDimension;
    }

    /** malilib is a required dependency (0.9.01); its shared menu (A+C by default) opens the settings. */
    private static void registerMalilibConfigScreen() {
        TerraLevelerMalilibIntegration.register();
        LOGGER.info("Registered TerraLeveler config screen in malilib as {}", TerraLevelerMalilibIntegration.DISPLAY_NAME);
    }

    static void refreshMalilibConfigOptions() {
        TerraLevelerMalilibConfigOptions.loadFrom(CONFIG);
    }

    /**
     * Cached result for the current flatten request, or null after starting (or while
     * running) a scan that is spread over the following ticks. Right after joining a
     * server or teleporting, an island scan first waits for nearby chunks (0.8.69 testing:
     * a scan 1.4 seconds after joining reported unloaded terrain).
     */
    private static FlattenPlanner.Result flattenResultOrBeginScan(Minecraft mc) {
        String key = flattenScanKey(mc);
        if (key == null) return currentFlattenResult(mc);
        if (cachedFlattenResult != null && key.equals(cachedFlattenKey)) return cachedFlattenResult;
        if (key.equals(pendingFlattenScanKey)) {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：仍在"
                    + (pendingFlattenScan == null ? "等待周围区块加载" : "识别，" + pendingFlattenScan.progress()) + "，完成后会自动显示结果。"));
            return null;
        }
        clearPendingFlattenScan("new-request");
        pendingFlattenScanKey = key;
        if (CONFIG.flattenAutoIslandEnabled && recentlyArrived() && !nearbyChunksLoaded(mc)) {
            awaitingLoadSinceMillis = System.currentTimeMillis();
            TerraLevelerDiagnostics.log("PLAN", "flatten-scan-await-load player=" + mc.player.blockPosition().toShortString()
                    + " sinceArrivalMs=" + (System.currentTimeMillis() - lastArrivalMillis));
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                    "TerraLeveler 平地机：刚进入服务器或传送，周围区块还在加载；加载完成后会自动开始识别岛屿（最多等 15 秒）。"));
            return null;
        }
        beginPendingFlattenScan(mc);
        // Small areas usually finish within the first slice.
        if (pendingFlattenScan.step(System.nanoTime() + FLATTEN_SCAN_SLICE_NANOS)) {
            return completePendingFlattenScan(mc);
        }
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                "TerraLeveler 平地机：开始识别" + (CONFIG.flattenAutoIslandEnabled ? "岛屿范围" : "选区") + "，分多帧进行，不会卡住游戏；完成后自动显示结果。"));
        return null;
    }

    private static void beginPendingFlattenScan(Minecraft mc) {
        boolean island = CONFIG.flattenAutoIslandEnabled;
        BlockPos seed = flattenIslandSeed == null ? mc.player.blockPosition() : flattenIslandSeed;
        if (island) TerraLevelerDiagnostics.log("PLAN", "island-scan-start seed=" + seed.toShortString()
                + " player=" + mc.player.blockPosition().toShortString()
                + " radius=" + CONFIG.flattenIslandMaximumRadius
                + " maxColumns=" + CONFIG.flattenIslandMaximumColumns
                + " shoreInset=" + CONFIG.flattenIslandShoreInset
                + " cutNarrow=" + CONFIG.flattenIslandCutNarrowConnections
                + " neckWidth=" + CONFIG.flattenIslandMaximumNeckWidth
                + " targetY=" + FLATTEN_TARGET_Y);
        pendingFlattenScan = island
                ? FlattenPlanner.beginIslandScan(mc, seed, FLATTEN_TARGET_Y, CONFIG.flattenProtectBlockEntities,
                new IslandFlattenAreaDetector.Settings(CONFIG.flattenIslandMaximumRadius,
                        CONFIG.flattenIslandMaximumColumns, CONFIG.flattenIslandShoreInset,
                        CONFIG.flattenIslandFillInteriorWater, CONFIG.flattenIslandCutNarrowConnections,
                        CONFIG.flattenIslandMaximumNeckWidth), islandSurvey(mc))
                : FlattenPlanner.beginRectangleScan(mc, FLATTEN_FIRST, FLATTEN_SECOND,
                FLATTEN_TARGET_Y, CONFIG.flattenProtectBlockEntities);
        pendingFlattenScanStartedNanos = System.nanoTime();
        pendingScanStatusTicks = 0;
    }

    /** Stores the finished pending scan as the cached result (same logging as the old one-shot scan). */
    private static FlattenPlanner.Result completePendingFlattenScan(Minecraft mc) {
        FlattenPlanner.Result result = pendingFlattenScan.result();
        String key = pendingFlattenScanKey;
        long elapsedMs = (System.nanoTime() - pendingFlattenScanStartedNanos) / 1_000_000L;
        pendingFlattenScan = null;
        pendingFlattenScanKey = null;
        cachedFlattenResult = result;
        cachedFlattenKey = key;
        BlockPos seed = flattenIslandSeed == null ? mc.player.blockPosition() : flattenIslandSeed;
        if (CONFIG.flattenAutoIslandEnabled) {
            TerraLevelerDiagnostics.log("PLAN", "flatten-island-scan scope=" + currentSelectionScope(mc)
                    + " dimension=" + mc.level.dimension().identifier()
                    + " seed=" + seed.toShortString() + " player=" + mc.player.blockPosition().toShortString()
                    + " targetY=" + FLATTEN_TARGET_Y + " status=" + result.status()
                    + " detail=" + result.detail()
                    + (result.problemPos() == null ? "" : " problem=" + result.problemPos().toShortString())
                    + " elapsedMs=" + elapsedMs);
        }
        if (result.ready()) {
            FlattenPlan plan = result.plan();
            TerraLevelerDiagnostics.log("PLAN", "mode=flatten targetGroundY=" + plan.targetGroundY()
                    + " topY=" + plan.scannedTopY() + " columns=" + plan.columns()
                    + " excavation=" + plan.estimatedBlocks() + " fill=" + plan.estimatedFillBlocks()
                    + " elapsedMs=" + elapsedMs);
        }
        return result;
    }

    private static void tickPendingFlattenScan(Minecraft mc) {
        if (pendingFlattenScanKey == null) return;
        if (mc.level == null || mc.player == null) {
            clearPendingFlattenScan("world-unavailable");
            return;
        }
        if (pendingFlattenScan == null) {
            boolean loaded = nearbyChunksLoaded(mc);
            if (!loaded && System.currentTimeMillis() - awaitingLoadSinceMillis < LOAD_WAIT_MAXIMUM_MILLIS) {
                if (++pendingScanStatusTicks % 20 == 0) {
                    mc.player.sendOverlayMessage(Component.literal("TerraLeveler 平地机：等待周围区块加载后开始识别岛屿…"));
                }
                return;
            }
            TerraLevelerDiagnostics.log("PLAN", "flatten-scan-load-wait-end loaded=" + loaded
                    + " waitedMs=" + (System.currentTimeMillis() - awaitingLoadSinceMillis));
            beginPendingFlattenScan(mc);
        }
        if (!pendingFlattenScan.step(System.nanoTime() + FLATTEN_SCAN_SLICE_NANOS)) {
            if (++pendingScanStatusTicks % 10 == 0) {
                mc.player.sendOverlayMessage(Component.literal("TerraLeveler 平地机：" + pendingFlattenScan.progress()));
            }
            return;
        }
        FlattenPlanner.Result result = completePendingFlattenScan(mc);
        ConstructionController.State state = CONTROLLER.state();
        boolean controllerIdle = state == ConstructionController.State.IDLE || state == ConstructionController.State.COMPLETE;
        if (controllerIdle && islandJob == null) continueFlattenStart(mc, result, true);
    }

    private static void clearPendingFlattenScan(String reason) {
        if (pendingFlattenScanKey == null) return;
        TerraLevelerDiagnostics.log("PLAN", "flatten-scan-cancelled reason=" + reason);
        pendingFlattenScan = null;
        pendingFlattenScanKey = null;
    }

    private static void trackArrival(Minecraft mc) {
        if (mc.player == null) {
            lastTrackedPlayerPosition = null;
            return;
        }
        net.minecraft.world.phys.Vec3 position = mc.player.position();
        if (lastTrackedPlayerPosition != null && position.distanceToSqr(lastTrackedPlayerPosition) > 64.0 * 64.0) {
            lastArrivalMillis = System.currentTimeMillis();
        }
        lastTrackedPlayerPosition = position;
    }

    private static boolean recentlyArrived() {
        return System.currentTimeMillis() - lastArrivalMillis < ARRIVAL_SETTLE_MILLIS;
    }

    /** All chunks within {@link #ARRIVAL_LOAD_CHUNK_RADIUS} of the player hold real terrain. */
    private static boolean nearbyChunksLoaded(Minecraft mc) {
        if (mc.level == null || mc.player == null || FLATTEN_TARGET_Y == null) return false;
        int centerX = mc.player.getBlockX() >> 4;
        int centerZ = mc.player.getBlockZ() >> 4;
        for (int dx = -ARRIVAL_LOAD_CHUNK_RADIUS; dx <= ARRIVAL_LOAD_CHUNK_RADIUS; dx++) {
            for (int dz = -ARRIVAL_LOAD_CHUNK_RADIUS; dz <= ARRIVAL_LOAD_CHUNK_RADIUS; dz++) {
                if (!FlattenPlanner.chunkLoadedWithTerrain(mc, IslandSurveyCache.chunkKey(centerX + dx, centerZ + dz), FLATTEN_TARGET_Y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Cache key of the current flatten request, or null when the request is not valid yet. */
    private static String flattenScanKey(Minecraft mc) {
        boolean island = CONFIG.flattenAutoIslandEnabled;
        if (FLATTEN_TARGET_Y == null || mc.level == null || mc.player == null
                || flattenSelectionDimension == null || !flattenSelectionDimension.equals(mc.level.dimension())
                || !island && (FLATTEN_FIRST == null || FLATTEN_SECOND == null)) {
            return null;
        }
        BlockPos seed = flattenIslandSeed == null ? mc.player.blockPosition() : flattenIslandSeed;
        return island ? String.join("|", "ISLAND", currentSelectionScope(mc),
                mc.level.dimension().identifier().toString(),
                Integer.toString(seed.getX()), Integer.toString(seed.getZ()), Integer.toString(FLATTEN_TARGET_Y),
                Boolean.toString(CONFIG.flattenIslandFillInteriorWater),
                Boolean.toString(CONFIG.flattenIslandCutNarrowConnections),
                Integer.toString(CONFIG.flattenIslandShoreInset),
                Integer.toString(CONFIG.flattenIslandMaximumRadius),
                Integer.toString(CONFIG.flattenIslandMaximumColumns),
                Integer.toString(CONFIG.flattenIslandMaximumNeckWidth),
                Boolean.toString(CONFIG.flattenProtectBlockEntities))
                : String.join("|", "RECTANGLE", FLATTEN_FIRST.toShortString(), FLATTEN_SECOND.toShortString(),
                Integer.toString(FLATTEN_TARGET_Y),
                Boolean.toString(CONFIG.flattenProtectBlockEntities));
    }

    public static FlattenPlanner.Result currentFlattenResult(Minecraft mc) {
        boolean island = CONFIG.flattenAutoIslandEnabled;
        if (FLATTEN_TARGET_Y == null || mc.level == null || mc.player == null
                || flattenSelectionDimension == null || !flattenSelectionDimension.equals(mc.level.dimension())
                || !island && (FLATTEN_FIRST == null || FLATTEN_SECOND == null)) {
            return new FlattenPlanner.Result(FlattenPlanner.Status.WORLD_UNAVAILABLE,
                    null, null, 0, !island && (FLATTEN_FIRST == null || FLATTEN_SECOND == null)
                    ? "请先切换到平地机并用苹果设置两个角点"
                    : FLATTEN_TARGET_Y == null ? "请在平地机施工设置中填写目标地面 Y，或站在目标完整方块顶面按" + keyHint(2) + "记录高度"
                    : "选区或目标高度不属于当前维度");
        }
        BlockPos seed = flattenIslandSeed == null ? mc.player.blockPosition() : flattenIslandSeed;
        String key = island ? String.join("|", "ISLAND", currentSelectionScope(mc),
                mc.level.dimension().identifier().toString(),
                Integer.toString(seed.getX()), Integer.toString(seed.getZ()), Integer.toString(FLATTEN_TARGET_Y),
                Boolean.toString(CONFIG.flattenIslandFillInteriorWater),
                Boolean.toString(CONFIG.flattenIslandCutNarrowConnections),
                Integer.toString(CONFIG.flattenIslandShoreInset),
                Integer.toString(CONFIG.flattenIslandMaximumRadius),
                Integer.toString(CONFIG.flattenIslandMaximumColumns),
                Integer.toString(CONFIG.flattenIslandMaximumNeckWidth),
                Boolean.toString(CONFIG.flattenProtectBlockEntities))
                : String.join("|", "RECTANGLE", FLATTEN_FIRST.toShortString(), FLATTEN_SECOND.toShortString(),
                Integer.toString(FLATTEN_TARGET_Y),
                Boolean.toString(CONFIG.flattenProtectBlockEntities));
        if (cachedFlattenResult != null && key.equals(cachedFlattenKey)) {
            return cachedFlattenResult;
        }
        if (island) TerraLevelerDiagnostics.log("PLAN", "island-scan-start seed=" + seed.toShortString()
                + " player=" + mc.player.blockPosition().toShortString()
                + " radius=" + CONFIG.flattenIslandMaximumRadius
                + " maxColumns=" + CONFIG.flattenIslandMaximumColumns
                + " shoreInset=" + CONFIG.flattenIslandShoreInset
                + " cutNarrow=" + CONFIG.flattenIslandCutNarrowConnections
                + " neckWidth=" + CONFIG.flattenIslandMaximumNeckWidth
                + " targetY=" + FLATTEN_TARGET_Y);
        cachedFlattenResult = island
                ? FlattenPlanner.scanIsland(mc, seed, FLATTEN_TARGET_Y, CONFIG.flattenProtectBlockEntities,
                new IslandFlattenAreaDetector.Settings(CONFIG.flattenIslandMaximumRadius,
                        CONFIG.flattenIslandMaximumColumns, CONFIG.flattenIslandShoreInset,
                        CONFIG.flattenIslandFillInteriorWater, CONFIG.flattenIslandCutNarrowConnections,
                        CONFIG.flattenIslandMaximumNeckWidth), islandSurvey(mc))
                : FlattenPlanner.scan(mc, FLATTEN_FIRST, FLATTEN_SECOND,
                FLATTEN_TARGET_Y, CONFIG.flattenProtectBlockEntities);
        if (island) {
            TerraLevelerDiagnostics.log("PLAN", "flatten-island-scan scope=" + currentSelectionScope(mc)
                    + " dimension=" + mc.level.dimension().identifier()
                    + " seed=" + seed.toShortString() + " player=" + mc.player.blockPosition().toShortString()
                    + " targetY=" + FLATTEN_TARGET_Y + " status=" + cachedFlattenResult.status()
                    + " detail=" + cachedFlattenResult.detail()
                    + (cachedFlattenResult.problemPos() == null ? "" : " problem=" + cachedFlattenResult.problemPos().toShortString()));
        }
        cachedFlattenKey = key;
        if (cachedFlattenResult.ready()) {
            FlattenPlan plan = cachedFlattenResult.plan();
            TerraLevelerDiagnostics.log("PLAN", "mode=flatten targetGroundY=" + plan.targetGroundY()
                    + " topY=" + plan.scannedTopY() + " columns=" + plan.columns()
                    + " excavation=" + plan.estimatedBlocks() + " fill=" + plan.estimatedFillBlocks());
        }
        return cachedFlattenResult;
    }

    /** Survey cache for the current server, dimension and target Y; null when that scope is unknown. */
    private static IslandSurveyCache islandSurvey(Minecraft mc) {
        String scope = currentSelectionScope(mc);
        if (scope == null || mc.level == null || FLATTEN_TARGET_Y == null) return null;
        String key = scope + "|" + mc.level.dimension().identifier() + "|" + FLATTEN_TARGET_Y;
        String previous = ISLAND_SURVEY.scope();
        if (!key.equals(previous)) {
            // Keep what was learned in the old scope before switching, then continue
            // with whatever was recorded for the new one in an earlier session.
            if (previous != null && ISLAND_SURVEY.dirty()) ISLAND_SURVEY_STORE.saveAsync(ISLAND_SURVEY);
            if (ISLAND_SURVEY.ensureScope(key)) {
                TerraLevelerDiagnostics.log("PLAN", "island-survey-cleared old=" + previous + " new=" + key);
            }
            int loaded = ISLAND_SURVEY_STORE.load(key, ISLAND_SURVEY);
            TerraLevelerDiagnostics.log("PLAN", "island-survey-loaded scope=" + key + " chunks=" + loaded);
        }
        return ISLAND_SURVEY;
    }

    private static void tickIslandSurvey(Minecraft mc) {
        if (!CONFIG.flattenAutoIslandEnabled || mc.level == null || mc.player == null || FLATTEN_TARGET_Y == null) return;
        ConstructionController.State state = CONTROLLER.state();
        // Construction uses live terrain only; survey while the player is free to walk the island.
        if (state != ConstructionController.State.IDLE && state != ConstructionController.State.COMPLETE
                && state != ConstructionController.State.PAUSED) return;
        IslandSurveyCache survey = islandSurvey(mc);
        if (survey == null) return;
        int before = survey.fullyCapturedChunks();
        FlattenPlanner.surveyLoadedChunks(mc, survey, FLATTEN_TARGET_Y, ISLAND_SURVEY_CHUNKS_PER_TICK,
                ISLAND_SURVEY_CHUNK_RADIUS, ++islandSurveyTick, ISLAND_SURVEY_REFRESH_TICKS);
        int after = survey.fullyCapturedChunks();
        if (after != before && after % 64 == 0) {
            TerraLevelerDiagnostics.log("PLAN", "island-survey-progress chunks=" + after
                    + " player=" + mc.player.blockPosition().toShortString());
        }
        if (islandSurveyTick % ISLAND_SURVEY_SAVE_TICKS == 0 && survey.dirty()) {
            ISLAND_SURVEY_STORE.saveAsync(survey);
        }
    }

    private static void tickIslandRegionJob(Minecraft mc) {
        if (islandJob == null || islandJob.current == null) return;
        ConstructionController.State state = CONTROLLER.state();
        if (state == ConstructionController.State.IDLE) {
            clearIslandJob("controller-idle");
            return;
        }
        if (state != ConstructionController.State.COMPLETE || !CONTROLLER.islandRegionRun()) return;
        IslandRegionPlanner.Region done = islandJob.current;
        islandJob.remaining.remove(done);
        islandJob.current = null;
        islandJob.completed++;
        TerraLevelerDiagnostics.log("PLAN", "island-region-complete region=" + done.id() + " columns=" + done.columns().size()
                + " completed=" + islandJob.completed + "/" + islandJob.regions.size());
        advanceIslandJob(mc);
    }

    private static void startIslandJob(Minecraft mc, IslandFlattenAreaDetector.Result island,
                                       java.util.List<IslandRegionPlanner.Region> regions) {
        clearIslandJob("new-job");
        islandJob = new IslandRegionJob(island, regions, FLATTEN_TARGET_Y, currentSelectionScope(mc), mc.level.dimension());
        TerraLevelerDiagnostics.log("PLAN", "island-region-job-start regions=" + regions.size()
                + " columns=" + island.columns().size() + " regionChunks=" + IslandRegionPlanner.REGION_CHUNKS
                + " bounds=x" + islandJob.bounds[0] + ".." + islandJob.bounds[1]
                + ",z" + islandJob.bounds[2] + ".." + islandJob.bounds[3]);
        advanceIslandJob(mc);
    }

    /** Starts the nearest fully loaded remaining region, or waits with a hint. */
    private static boolean advanceIslandJob(Minecraft mc) {
        IslandRegionJob job = islandJob;
        if (job == null || mc.level == null || mc.player == null) return false;
        if (!job.dimension.equals(mc.level.dimension()) || !Objects.equals(job.scope, currentSelectionScope(mc))
                || !Objects.equals(job.targetY, FLATTEN_TARGET_Y)) {
            clearIslandJob("scope-mismatch");
            mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：服务器、维度或目标高度已变化，岛屿分区施工已取消。"));
            return false;
        }
        if (job.remaining.isEmpty()) {
            TerraLevelerDiagnostics.log("PLAN", "island-region-job-complete regions=" + job.regions.size()
                    + " columns=" + job.island.columns().size());
            TerraLevelerAlerts.finished(mc);
            mc.player.sendSystemMessage(Component.literal("TerraLeveler：岛屿全部 " + job.regions.size()
                    + " 个区域的平地施工已经完成。"));
            islandJob = null;
            return true;
        }
        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        java.util.Optional<IslandRegionPlanner.Region> next = IslandRegionPlanner.nextLoaded(job.remaining, px, pz,
                chunk -> FlattenPlanner.chunkLoadedWithTerrain(mc, chunk, job.targetY));
        if (next.isEmpty()) {
            IslandRegionPlanner.Region nearest = IslandRegionPlanner.nearest(job.remaining, px, pz).orElseThrow();
            int distance = (int) Math.round(Math.sqrt(nearest.distanceSquared(px, pz)));
            job.awaiting = true;
            job.awaitingDetail = "等待下一区域加载：请走向 " + nearest.centerX() + "," + nearest.centerZ()
                    + "（约 " + distance + " 格）后按" + keyHint(0) + "继续";
            TerraLevelerDiagnostics.log("PLAN", "island-region-await-load nearest=" + nearest.id() + " center="
                    + nearest.centerX() + "," + nearest.centerZ() + " distance=" + distance
                    + " remaining=" + job.remaining.size() + " player=" + px + "," + pz);
            mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：已完成 " + job.completed + "/"
                    + job.regions.size() + " 个区域；剩余区域都未完整加载。" + job.awaitingDetail
                    + "。为安全起见不会自动长距离移动。"));
            return false;
        }
        IslandRegionPlanner.Region region = next.get();
        FlattenPlanner.Result scanned = FlattenPlanner.scanIslandRegion(mc, region, job.targetY,
                CONFIG.flattenProtectBlockEntities, job.island);
        if (!scanned.ready()) {
            job.awaiting = true;
            job.awaitingDetail = "区域 " + region.id() + " 规划失败：" + flattenPlanProblem(scanned)
                    + "；处理后按" + keyHint(0) + "重试";
            TerraLevelerDiagnostics.log("PLAN", "island-region-plan-failed region=" + region.id() + " status="
                    + scanned.status() + " detail=" + scanned.detail()
                    + (scanned.problemPos() == null ? "" : " problem=" + scanned.problemPos().toShortString()));
            mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：" + job.awaitingDetail));
            return false;
        }
        FlattenPlan plan = scanned.plan();
        job.current = region;
        job.awaiting = false;
        job.awaitingDetail = "";
        TerraLevelerDiagnostics.log("PLAN", "island-region-start region=" + region.id() + " index="
                + (job.completed + 1) + "/" + job.regions.size() + " columns=" + region.columns().size()
                + " bounds=x" + region.minX() + ".." + region.maxX() + ",z" + region.minZ() + ".." + region.maxZ()
                + " excavation=" + plan.estimatedBlocks() + " fill=" + plan.estimatedFillBlocks()
                + " player=" + px + "," + pz);
        mc.player.sendSystemMessage(Component.literal("TerraLeveler 平地机：开始岛屿区域 " + (job.completed + 1) + "/"
                + job.regions.size() + "（X=" + region.minX() + ".." + region.maxX() + "，Z=" + region.minZ()
                + ".." + region.maxZ() + "，" + region.columns().size() + " 列）。"));
        CONTROLLER.startIslandRegion(plan);
        return true;
    }

    private static void clearIslandJob(String reason) {
        if (islandJob == null) return;
        TerraLevelerDiagnostics.log("PLAN", "island-region-job-cleared reason=" + reason + " completed="
                + islandJob.completed + "/" + islandJob.regions.size());
        islandJob = null;
    }

    private static boolean playerOnJobIsland(Minecraft mc) {
        if (islandJob == null || mc.player == null) return false;
        int x = mc.player.getBlockX();
        int z = mc.player.getBlockZ();
        int margin = 16;
        return x >= islandJob.bounds[0] - margin && x <= islandJob.bounds[1] + margin
                && z >= islandJob.bounds[2] - margin && z <= islandJob.bounds[3] + margin;
    }

    /** {minX, maxX, minZ, maxZ} of the columns. */
    private static int[] columnBounds(java.util.Set<BlockPos> columns) {
        int[] bounds = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (BlockPos column : columns) {
            bounds[0] = Math.min(bounds[0], column.getX());
            bounds[1] = Math.max(bounds[1], column.getX());
            bounds[2] = Math.min(bounds[2], column.getZ());
            bounds[3] = Math.max(bounds[3], column.getZ());
        }
        return bounds;
    }

    static FlattenPlanner.Result cachedFlattenResultForDisplay() {
        return cachedFlattenResult;
    }

    static String flattenPlanProblem(FlattenPlanner.Result result) {
        if (result == null) return "计划尚未生成";
        String position = result.problemPos() == null ? "" : "，位置 " + result.problemPos().toShortString();
        // For unloaded columns and island boundaries the Y is only the target height,
        // not the ground there (teleporting to it ended underground in 0.8.69 testing).
        String column = result.problemPos() == null ? ""
                : "，位置 X=" + result.problemPos().getX() + "，Z=" + result.problemPos().getZ() + "（高度未知）";
        boolean gapSummary = result.island() != null && !result.island().gaps().isEmpty();
        return switch (result.status()) {
            case READY -> "计划已就绪";
            case WORLD_UNAVAILABLE -> result.detail();
            case UNLOADED -> gapSummary ? result.detail() : result.detail() + column;
            case PROTECTED_BLOCK_ENTITY -> result.detail() + position
                    + "；可移出选区、手动处理，或关闭保护开关";
            case ISLAND_SEED_WATER -> result.detail() + column;
            case ISLAND_OPEN_BOUNDARY -> result.detail() + column + "；请提高最大半径或调整窄陆桥截断设置";
            case ISLAND_TOO_LARGE -> result.detail() + column + "；请降低误连通风险或提高最大面积";
            case ISLAND_EMPTY -> result.detail() + column + "；请减少岸线保留宽度";
            case ISLAND_REGIONAL -> result.detail();
        };
    }

    static void invalidatePlanCache() {
        clearIslandJob("plan-cache-invalidated");
        clearPendingFlattenScan("plan-cache-invalidated");
        cachedFlattenResult = null;
        cachedFlattenKey = null;
        confirmedIslandFingerprint = Long.MIN_VALUE;
        flattenIslandSeed = null;
    }

    private static void clearIslandPreview() {
        clearIslandJob("preview-cleared");
        clearPendingFlattenScan("preview-cleared");
        confirmedIslandFingerprint = Long.MIN_VALUE;
        flattenIslandSeed = null;
        cachedFlattenResult = null;
        cachedFlattenKey = null;
    }

    private static void saveSelection(Minecraft mc) {
        String scope = currentSelectionScope(mc);
        if (scope == null) return;
        if (ACTIVE_MODE == WorkMode.FLATTEN) {
            if (storeFlattenSelection(mc)) CONFIG.save();
            return;
        }
        if (lightingSelectionDimension == null) return;
        CONFIG.lastLightingSelectionScope = scope;
        CONFIG.lastLightingSelectionDimension = lightingSelectionDimension.identifier().toString();
        CONFIG.lastLightingSelectionFirst = SelectionPersistence.encode(LIGHTING_FIRST);
        CONFIG.lastLightingSelectionSecond = SelectionPersistence.encode(LIGHTING_SECOND);
        CONFIG.activeWorkMode = ACTIVE_MODE.name();
        CONFIG.save();
    }

    private static boolean storeFlattenSelection(Minecraft mc) {
        String scope = currentSelectionScope(mc);
        if (scope == null || flattenSelectionDimension == null) return false;
        CONFIG.lastFlattenSelectionScope = scope;
        CONFIG.lastFlattenSelectionDimension = flattenSelectionDimension.identifier().toString();
        CONFIG.lastFlattenSelectionFirst = SelectionPersistence.encode(FLATTEN_FIRST);
        CONFIG.lastFlattenSelectionSecond = SelectionPersistence.encode(FLATTEN_SECOND);
        CONFIG.lastFlattenTargetY = FLATTEN_TARGET_Y;
        CONFIG.activeWorkMode = ACTIVE_MODE.name();
        return true;
    }

    private static void restoreSelection(Minecraft mc) {
        if (mc.level == null) return;
        SelectionPersistence.Restored flattenRestored = SelectionPersistence.restore(
                CONFIG.lastFlattenSelectionScope,
                CONFIG.lastFlattenSelectionDimension,
                CONFIG.lastFlattenSelectionFirst,
                CONFIG.lastFlattenSelectionSecond,
                currentSelectionScope(mc),
                mc.level.dimension().identifier().toString());
        boolean flattenContextMatches = SelectionPersistence.matchesContext(
                CONFIG.lastFlattenSelectionScope,
                CONFIG.lastFlattenSelectionDimension,
                currentSelectionScope(mc),
                mc.level.dimension().identifier().toString());
        if (flattenContextMatches && (flattenRestored != null || CONFIG.lastFlattenTargetY != null)) {
            FLATTEN_FIRST = flattenRestored == null ? null : flattenRestored.first().immutable();
            FLATTEN_SECOND = flattenRestored == null || flattenRestored.second() == null
                    ? null : flattenRestored.second().immutable();
            FLATTEN_TARGET_Y = CONFIG.lastFlattenTargetY;
            flattenSelectionDimension = mc.level.dimension();
        }
        SelectionPersistence.Restored lightingRestored = SelectionPersistence.restore(
                CONFIG.lastLightingSelectionScope,
                CONFIG.lastLightingSelectionDimension,
                CONFIG.lastLightingSelectionFirst,
                CONFIG.lastLightingSelectionSecond,
                currentSelectionScope(mc),
                mc.level.dimension().identifier().toString());
        if (lightingRestored != null) {
            LIGHTING_FIRST = lightingRestored.first().immutable();
            LIGHTING_SECOND = lightingRestored.second() == null
                    ? null : lightingRestored.second().immutable();
            lightingSelectionDimension = mc.level.dimension();
        }
        invalidatePlanCache();
        String restoredText = ACTIVE_MODE == WorkMode.LIGHTING
                ? (lightingRestored == null ? null : "已恢复上次的照明区域 " + LIGHTING_FIRST.toShortString()
                        + " → " + (LIGHTING_SECOND == null ? "未设置" : LIGHTING_SECOND.toShortString()))
                : flattenContextMatches && (flattenRestored != null || CONFIG.lastFlattenTargetY != null)
                ? "已恢复上次的" + (CONFIG.flattenAutoIslandEnabled || FLATTEN_FIRST == null ? ""
                        : "选区 " + FLATTEN_FIRST.toShortString() + " → " + (FLATTEN_SECOND == null ? "未设置" : FLATTEN_SECOND.toShortString()) + "，")
                        + "目标地面 Y=" + (FLATTEN_TARGET_Y == null ? "未设置" : FLATTEN_TARGET_Y)
                : null;
        if (restoredText != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("TerraLeveler " + ACTIVE_MODE.label() + "：" + restoredText + "（不会自动开工）"));
        }
    }

    private static String currentSelectionScope(Minecraft mc) {
        if (mc.getCurrentServer() != null) {
            return SelectionPersistence.scopeKey("multiplayer", mc.getCurrentServer().ip);
        }
        if (mc.getSingleplayerServer() != null) {
            return SelectionPersistence.scopeKey("singleplayer",
                    mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return null;
    }
}
