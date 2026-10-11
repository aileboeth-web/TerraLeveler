package cn.terraleveler;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerraLevelerConfigTest {
    @Test
    void shortcutsHaveNoDefault() {
        TerraLevelerConfig config = new TerraLevelerConfig();

        assertEquals("", config.startPauseKey);
        assertEquals("", config.emergencyStopKey);
        assertEquals("", config.flattenHeightKey);
        assertEquals("", config.retainItemKey);
    }

    @Test
    void toolBoxNamesAreTrimmedDeduplicatedAndOrdered() {
        assertIterableEquals(List.of("工具", "备用工具"), TerraLevelerConfig.sanitizeToolBoxNames(List.of(
                " 工具 ", "", "备用工具", "工具", "   ")));
    }

    @Test
    void anEmptyToolBoxNameListDisablesAutomaticToolBoxes() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.toolBoxNames = List.of();

        config.sanitized();

        assertEquals(List.of(), config.toolBoxNames);
    }

    @Test
    void preservesTheChineseToolBoxNameTools() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.toolBoxNames = List.of("工具");

        config.sanitized();

        assertEquals(List.of("工具"), config.toolBoxNames);
    }

    @Test
    void pickupMaterialsAreTrimmedDeduplicatedAndLimited() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.pickupItems = List.of(" minecraft:dirt ", "minecraft:stone", "minecraft:dirt", "bad id", "64");
        config.pickupItemLimit = 9000;

        config.sanitized();

        assertEquals(List.of("minecraft:dirt", "minecraft:stone"), config.pickupItems);
        assertEquals(4096, config.pickupItemLimit);
        assertEquals("minecraft:dirt", config.pickupItem);
        assertEquals("minecraft:dirt", config.scaffoldBlock);
    }

    @Test
    void copiedConfigDoesNotShareMutableLists() {
        TerraLevelerConfig original = new TerraLevelerConfig();
        original.pickupItems = new ArrayList<>(List.of("minecraft:dirt"));
        original.toolBoxNames = new ArrayList<>(List.of("工具箱"));
        original.retainedItemIds = new ArrayList<>(List.of("minecraft:shield"));

        TerraLevelerConfig copy = original.copy();
        copy.pickupItems.add("minecraft:stone");
        copy.toolBoxNames.clear();
        copy.retainedItemIds.clear();

        assertEquals(List.of("minecraft:dirt"), original.pickupItems);
        assertEquals(List.of("工具箱"), original.toolBoxNames);
        assertEquals(List.of("minecraft:shield"), original.retainedItemIds);
    }

    @Test
    void toolboxSwitchRemainsEnabledWhenNamesAreEmpty() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.useToolBoxForTools = true;
        config.toolBoxNames = List.of();

        config.sanitized();

        assertTrue(config.useToolBoxForTools);
    }

    @Test
    void migratesThePreviousIslandDetectionDefaults() {
        JsonObject json = islandLimits(256, 100000);
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.flattenIslandMaximumRadius = 256;
        config.flattenIslandMaximumColumns = 100000;

        TerraLevelerConfig.migrateIslandDetectionLimits(json, config);

        assertEquals(384, config.flattenIslandMaximumRadius);
        assertEquals(250000, config.flattenIslandMaximumColumns);
    }

    @Test
    void preservesCustomizedIslandDetectionLimits() {
        JsonObject json = islandLimits(320, 180000);
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.flattenIslandMaximumRadius = 320;
        config.flattenIslandMaximumColumns = 180000;

        TerraLevelerConfig.migrateIslandDetectionLimits(json, config);

        assertEquals(320, config.flattenIslandMaximumRadius);
        assertEquals(180000, config.flattenIslandMaximumColumns);
    }

    @Test
    void preservesLegacySizedLimitsAfterMigrationHasAlreadyRun() {
        JsonObject json = islandLimits(256, 100000);
        json.addProperty("islandDetectionLimitsVersion", 2);
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.flattenIslandMaximumRadius = 256;
        config.flattenIslandMaximumColumns = 100000;

        TerraLevelerConfig.migrateIslandDetectionLimits(json, config);

        assertEquals(256, config.flattenIslandMaximumRadius);
        assertEquals(100000, config.flattenIslandMaximumColumns);
    }

    @Test
    void suppliesExpandedDefaultsForOlderConfigsWithoutIslandLimits() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.flattenIslandMaximumRadius = 0;
        config.flattenIslandMaximumColumns = 0;

        TerraLevelerConfig.migrateIslandDetectionLimits(new JsonObject(), config);

        assertEquals(384, config.flattenIslandMaximumRadius);
        assertEquals(250000, config.flattenIslandMaximumColumns);
    }

    @Test
    void parsesOptionalFlattenTargetHeight() {
        assertNull(TerraLevelerConfig.parseFlattenTargetY("  "));
        assertEquals(62, TerraLevelerConfig.parseFlattenTargetY(" 62 "));
        assertEquals(-64, TerraLevelerConfig.parseFlattenTargetY("-64"));
        assertEquals(512, TerraLevelerConfig.parseFlattenTargetY("512"));
    }

    @Test
    void rejectsInvalidFlattenTargetHeight() {
        assertThrows(IllegalArgumentException.class, () -> TerraLevelerConfig.parseFlattenTargetY("sea level"));
        assertThrows(IllegalArgumentException.class, () -> TerraLevelerConfig.parseFlattenTargetY("-65"));
        assertThrows(IllegalArgumentException.class, () -> TerraLevelerConfig.parseFlattenTargetY("513"));
    }

    private static JsonObject islandLimits(int radius, int columns) {
        JsonObject json = new JsonObject();
        json.addProperty("flattenIslandMaximumRadius", radius);
        json.addProperty("flattenIslandMaximumColumns", columns);
        return json;
    }
}
