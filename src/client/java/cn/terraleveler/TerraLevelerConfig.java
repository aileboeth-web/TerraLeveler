package cn.terraleveler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TerraLevelerConfig {
    static final int MIN_FLATTEN_TARGET_Y = -64;
    static final int MAX_FLATTEN_TARGET_Y = 512;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final int DEFAULT_FLATTEN_ISLAND_MAXIMUM_RADIUS = 384;
    static final int DEFAULT_FLATTEN_ISLAND_MAXIMUM_COLUMNS = 250000;
    static final int MAX_FLATTEN_ISLAND_MAXIMUM_RADIUS = 768;
    static final int MAX_FLATTEN_ISLAND_MAXIMUM_COLUMNS = 800000;
    private static final int LEGACY_FLATTEN_ISLAND_MAXIMUM_RADIUS = 256;
    private static final int LEGACY_FLATTEN_ISLAND_MAXIMUM_COLUMNS = 100000;
    private static final int ISLAND_DETECTION_LIMITS_VERSION = 2;

    public int operationDelayTicks = 0;
    public int fastBreakBlocksPerTick = 20;
    public int durabilityReserve = 32;
    public int minimumHealth = 12;
    public String scaffoldBlock = "minecraft:dirt";
    public int scaffoldBudget = 128;
    public boolean autoDefend = true;
    /** 0.9.09: kill an ordinary animal standing where a surface block goes. */
    public boolean clearBlockingAnimals = true;
    /** 0.9.10: a bell when a pause needs the player, a level-up sound when a job is done. */
    public boolean soundAlerts = true;
    public boolean autoEat = true;
    public int defenseRange = 12;
    public boolean appleSelectionEnabled = true;
    public boolean flattenProtectBlockEntities = true;
    public boolean flattenCleanBoundaryFoliage = false;
    public boolean flattenAutoIslandEnabled = false;
    public boolean flattenIslandFillInteriorWater = false;
    public boolean flattenIslandCutNarrowConnections = true;
    public int flattenIslandShoreInset = 2;
    public int flattenIslandMaximumRadius = DEFAULT_FLATTEN_ISLAND_MAXIMUM_RADIUS;
    public int flattenIslandMaximumColumns = DEFAULT_FLATTEN_ISLAND_MAXIMUM_COLUMNS;
    public int flattenIslandMaximumNeckWidth = 4;
    private int islandDetectionLimitsVersion = ISLAND_DETECTION_LIMITS_VERSION;
    public int lightingBlockLightThreshold = 0;
    public int lightingScanBlocksPerTick = 2048;
    public String startPauseKey = "";
    public String emergencyStopKey = "";
    public String flattenHeightKey = "";
    public String retainItemKey = "";
    public boolean customRetainedItemsEnabled = false;
    public boolean retainEntireInventory = false;
    public boolean discardGoldItems = false;
    public boolean discardIronItems = false;
    public List<String> retainedItemIds = new ArrayList<>();
    public boolean diagnosticsEnabled = true;
    public boolean useToolBoxForTools = false;
    public List<String> toolBoxNames = new ArrayList<>();
    /** Legacy single-item field retained for config migration and old integrations. */
    public String pickupItem = "minecraft:dirt";
    public List<String> pickupItems = new ArrayList<>(List.of("minecraft:dirt"));
    /** Per-item loose inventory allowance after a material box has been filled. */
    public int pickupItemLimit = 64;
    public String activeWorkMode = "FLATTEN";
    public String lastFlattenSelectionScope;
    public String lastFlattenSelectionDimension;
    public String lastFlattenSelectionFirst;
    public String lastFlattenSelectionSecond;
    public Integer lastFlattenTargetY;
    public String lastLightingSelectionScope;
    public String lastLightingSelectionDimension;
    public String lastLightingSelectionFirst;
    public String lastLightingSelectionSecond;
    public Map<String, Map<String, String>> ownedScaffoldLedgers = new LinkedHashMap<>();

    /** The config as it would be written to disk; two equal strings mean nothing changed. */
    String toJson() {
        return GSON.toJson(this);
    }

    public TerraLevelerConfig copy() {
        return GSON.fromJson(GSON.toJson(this), TerraLevelerConfig.class).sanitized();
    }

    public static TerraLevelerConfig load() {
        try {
            Path path = configPath();
            if (Files.isRegularFile(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                TerraLevelerConfig value = GSON.fromJson(json, TerraLevelerConfig.class);
                if (value != null) {
                    // Gson leaves fields absent from an older config at JVM
                    // zero values, so migrate 0.4.x users to the intended 0.5 defaults.
                    if (!json.has("autoDefend")) value.autoDefend = true;
                    if (!json.has("clearBlockingAnimals")) value.clearBlockingAnimals = true;
                    if (!json.has("soundAlerts")) value.soundAlerts = true;
                    if (!json.has("autoEat")) value.autoEat = true;
                    if (!json.has("defenseRange")) value.defenseRange = 12;
                    if (!json.has("flattenProtectBlockEntities")) value.flattenProtectBlockEntities = true;
                    if (!json.has("flattenCleanBoundaryFoliage")) value.flattenCleanBoundaryFoliage = false;
                    if (!json.has("flattenAutoIslandEnabled")) value.flattenAutoIslandEnabled = false;
                    if (!json.has("flattenIslandFillInteriorWater")) value.flattenIslandFillInteriorWater = false;
                    if (!json.has("flattenIslandCutNarrowConnections")) value.flattenIslandCutNarrowConnections = true;
                    if (!json.has("flattenIslandShoreInset")) value.flattenIslandShoreInset = 2;
                    migrateIslandDetectionLimits(json, value);
                    if (!json.has("flattenIslandMaximumNeckWidth")) value.flattenIslandMaximumNeckWidth = 4;
                    if (!json.has("activeWorkMode")) value.activeWorkMode = "FLATTEN";
                    if (!json.has("fastBreakBlocksPerTick")) value.fastBreakBlocksPerTick = 20;
                    if (!json.has("lightingBlockLightThreshold")) value.lightingBlockLightThreshold = 0;
                    if (!json.has("lightingScanBlocksPerTick")) value.lightingScanBlocksPerTick = 2048;
                    if (!json.has("startPauseKey")) value.startPauseKey = "";
                    if (!json.has("emergencyStopKey")) value.emergencyStopKey = "";
                    if (!json.has("flattenHeightKey")) value.flattenHeightKey = "";
                    if (!json.has("retainItemKey")) value.retainItemKey = "";
                    if (!json.has("customRetainedItemsEnabled")) value.customRetainedItemsEnabled = false;
                    if (!json.has("retainEntireInventory")) value.retainEntireInventory = false;
                    if (!json.has("discardGoldItems")) value.discardGoldItems = false;
                    if (!json.has("discardIronItems")) value.discardIronItems = false;
                    if (!json.has("retainedItemIds")) value.retainedItemIds = new ArrayList<>();
                    if (!json.has("diagnosticsEnabled")) value.diagnosticsEnabled = false;
                    if (!json.has("useToolBoxForTools")) value.useToolBoxForTools = false;
                    if (!json.has("toolBoxNames")) value.toolBoxNames = new ArrayList<>();
                    if (!json.has("pickupItem")) value.pickupItem = "minecraft:dirt";
                    if (!json.has("pickupItems")) {
                        String legacy = value.pickupItem;
                        value.pickupItems = legacy == null || legacy.isBlank()
                                ? new ArrayList<>(List.of("minecraft:dirt"))
                                : new ArrayList<>(List.of(legacy));
                    }
                    if (!json.has("pickupItemLimit")) value.pickupItemLimit = 64;
                    TerraLeveler.LOGGER.info("Loaded TerraLeveler config path={} pickupItems={} pickupItemLimit={}",
                            path, value.pickupItems, value.pickupItemLimit);
                    return value.sanitized();
                }
            }
        } catch (Exception exception) {
            // Never let the next save silently replace an unreadable file with
            // defaults: keep it aside so retained items, selections and ledgers
            // can be recovered by hand.
            Path broken = keepBrokenConfig(configPath());
            TerraLeveler.LOGGER.error("Cannot read TerraLeveler config; using defaults, unreadable file kept as {}",
                    broken, exception);
        }
        return new TerraLevelerConfig();
    }

    private static Path keepBrokenConfig(Path path) {
        if (!Files.isRegularFile(path)) return null;
        Path broken = path.resolveSibling(path.getFileName() + ".broken-"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try {
            Files.copy(path, broken, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return broken;
        } catch (IOException exception) {
            TerraLeveler.LOGGER.error("Cannot keep unreadable TerraLeveler config", exception);
            return null;
        }
    }

    public TerraLevelerConfig sanitized() {
        operationDelayTicks = Math.max(0, Math.min(20, operationDelayTicks));
        fastBreakBlocksPerTick = Math.max(0, Math.min(20, fastBreakBlocksPerTick));
        durabilityReserve = Math.max(1, Math.min(1000, durabilityReserve));
        minimumHealth = Math.max(1, Math.min(20, minimumHealth));
        scaffoldBudget = Math.max(0, Math.min(4096, scaffoldBudget));
        defenseRange = Math.max(4, Math.min(24, defenseRange));
        flattenIslandShoreInset = Math.max(0, Math.min(8, flattenIslandShoreInset));
        flattenIslandMaximumRadius = Math.max(32,
                Math.min(MAX_FLATTEN_ISLAND_MAXIMUM_RADIUS, flattenIslandMaximumRadius));
        flattenIslandMaximumColumns = Math.max(1024,
                Math.min(MAX_FLATTEN_ISLAND_MAXIMUM_COLUMNS, flattenIslandMaximumColumns));
        flattenIslandMaximumNeckWidth = Math.max(1, Math.min(12, flattenIslandMaximumNeckWidth));
        lightingBlockLightThreshold = Math.max(0, Math.min(15, lightingBlockLightThreshold));
        lightingScanBlocksPerTick = Math.max(64, Math.min(65536, lightingScanBlocksPerTick));
        pickupItems = sanitizeItemIds(pickupItems);
        if (pickupItems.isEmpty()) pickupItems = new ArrayList<>(List.of("minecraft:dirt"));
        pickupItem = pickupItems.get(0);
        pickupItemLimit = Math.max(0, Math.min(4096, pickupItemLimit));
        // Keep the legacy field aligned for older code and external config readers.
        scaffoldBlock = pickupItems.get(0);
        startPauseKey = sanitizeKeyName(startPauseKey);
        emergencyStopKey = sanitizeKeyName(emergencyStopKey);
        flattenHeightKey = sanitizeKeyName(flattenHeightKey);
        retainItemKey = sanitizeKeyName(retainItemKey);
        retainedItemIds = sanitizeItemIds(retainedItemIds);
        toolBoxNames = sanitizeToolBoxNames(toolBoxNames);
        activeWorkMode = WorkMode.parse(activeWorkMode).name();
        lastFlattenSelectionScope = sanitizeStoredValue(lastFlattenSelectionScope);
        lastFlattenSelectionDimension = sanitizeStoredValue(lastFlattenSelectionDimension);
        lastFlattenSelectionFirst = sanitizeStoredValue(lastFlattenSelectionFirst);
        lastFlattenSelectionSecond = sanitizeStoredValue(lastFlattenSelectionSecond);
        lastLightingSelectionScope = sanitizeStoredValue(lastLightingSelectionScope);
        lastLightingSelectionDimension = sanitizeStoredValue(lastLightingSelectionDimension);
        lastLightingSelectionFirst = sanitizeStoredValue(lastLightingSelectionFirst);
        lastLightingSelectionSecond = sanitizeStoredValue(lastLightingSelectionSecond);
        if (lastFlattenTargetY != null) {
            lastFlattenTargetY = Math.max(MIN_FLATTEN_TARGET_Y,
                    Math.min(MAX_FLATTEN_TARGET_Y, lastFlattenTargetY));
        }
        if (ownedScaffoldLedgers == null) ownedScaffoldLedgers = new LinkedHashMap<>();
        return this;
    }

    static void migrateIslandDetectionLimits(JsonObject json, TerraLevelerConfig value) {
        boolean hasRadius = json.has("flattenIslandMaximumRadius");
        boolean hasColumns = json.has("flattenIslandMaximumColumns");
        int storedVersion = json.has("islandDetectionLimitsVersion")
                ? json.get("islandDetectionLimitsVersion").getAsInt() : 0;
        if (!hasRadius) value.flattenIslandMaximumRadius = DEFAULT_FLATTEN_ISLAND_MAXIMUM_RADIUS;
        if (!hasColumns) value.flattenIslandMaximumColumns = DEFAULT_FLATTEN_ISLAND_MAXIMUM_COLUMNS;
        if (storedVersion < ISLAND_DETECTION_LIMITS_VERSION
                && hasRadius && hasColumns
                && value.flattenIslandMaximumRadius == LEGACY_FLATTEN_ISLAND_MAXIMUM_RADIUS
                && value.flattenIslandMaximumColumns == LEGACY_FLATTEN_ISLAND_MAXIMUM_COLUMNS) {
            value.flattenIslandMaximumRadius = DEFAULT_FLATTEN_ISLAND_MAXIMUM_RADIUS;
            value.flattenIslandMaximumColumns = DEFAULT_FLATTEN_ISLAND_MAXIMUM_COLUMNS;
        }
        value.islandDetectionLimitsVersion = ISLAND_DETECTION_LIMITS_VERSION;
    }

    private static String sanitizeStoredValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sanitizeKeyName(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("key.keyboard.")) return trimmed;
        String[] parts = trimmed.split("\\+");
        if (parts.length < 2 || parts.length > 4) return "";
        for (int index = 0; index < parts.length - 1; index++) {
            if (!parts[index].equals("ctrl") && !parts[index].equals("shift")
                    && !parts[index].equals("alt")) return "";
        }
        return parts[parts.length - 1].startsWith("key.keyboard.") ? trimmed : "";
    }
    static List<String> sanitizeToolBoxNames(List<String> values) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return new ArrayList<>(names);
    }

    static List<String> sanitizeItemIds(List<String> values) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            Identifier parsed = Identifier.tryParse(trimmed);
            if (!trimmed.isEmpty() && parsed != null && BuiltInRegistries.ITEM.containsKey(parsed)) ids.add(trimmed);
        }
        return new ArrayList<>(ids);
    }

    static Integer parseFlattenTargetY(String value) {
        if (value == null || value.isBlank()) return null;
        final int targetY;
        try {
            targetY = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("平地目标地面 Y 必须是整数", exception);
        }
        if (targetY < MIN_FLATTEN_TARGET_Y || targetY > MAX_FLATTEN_TARGET_Y) {
            throw new IllegalArgumentException("平地目标地面 Y 必须在 -64 到 512 之间");
        }
        return targetY;
    }

    public void save() {
        sanitized();
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            // Write a temporary file and swap it in, so a crash mid-write can never
            // leave a truncated terraleveler.json behind.
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            TerraLeveler.LOGGER.debug("Saved TerraLeveler config path={} pickupItems={} pickupItemLimit={}",
                    path, pickupItems, pickupItemLimit);
        } catch (IOException exception) {
            TerraLeveler.LOGGER.error("Cannot save TerraLeveler config", exception);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("terraleveler.json");
    }
}
