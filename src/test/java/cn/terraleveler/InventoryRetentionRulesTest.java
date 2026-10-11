package cn.terraleveler;

import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryRetentionRulesTest {
    @Test
    void goldSwitchUsesExactVanillaScopeButNeverDropsGoldenApples() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.discardGoldItems = true;

        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLDEN_SWORD, config));
        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLD_INGOT, config));
        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLDEN_HORSE_ARMOR, config));
        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLDEN_APPLE, config));
        assertTrue(InventoryRetentionRules.isAlwaysProtected(Items.GOLDEN_APPLE));
        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLD_BLOCK, config));
    }

    @Test
    void ironSwitchKeepsBucketsAndShieldButIncludesAgreedUtilityItems() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.discardIronItems = true;

        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.IRON_PICKAXE, config));
        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.COMPASS, config));
        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.SHEARS, config));
        assertTrue(InventoryRetentionRules.isEnabledMetalDiscard(Items.FLINT_AND_STEEL, config));
        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.IRON_BLOCK, config));
        assertTrue(InventoryRetentionRules.isAlwaysProtected(Items.BUCKET));
        assertTrue(InventoryRetentionRules.isAlwaysProtected(Items.WATER_BUCKET));
        assertTrue(InventoryRetentionRules.isAlwaysProtected(Items.LAVA_BUCKET));
        assertTrue(InventoryRetentionRules.isAlwaysProtected(Items.SHIELD));
    }

    @Test
    void customRetentionOverridesMetalDiscard() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        config.customRetainedItemsEnabled = true;
        config.discardIronItems = true;
        config.retainedItemIds = new ArrayList<>(List.of("minecraft:iron_sword"));

        assertTrue(InventoryRetentionRules.isCustomRetained(Items.IRON_SWORD, config));
        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.IRON_SWORD, config));
        assertTrue(InventoryRetentionRules.isProtected(Items.IRON_SWORD, config));
    }

    @Test
    void metalToolsRemainProtectedWhenTheirDiscardSwitchIsOff() {
        TerraLevelerConfig config = new TerraLevelerConfig();

        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.GOLDEN_AXE, config));
        assertFalse(InventoryRetentionRules.isEnabledMetalDiscard(Items.IRON_CHESTPLATE, config));
    }

    @Test
    void rottenFleshIsDiscardableUnlessExplicitlyCustomRetained() {
        TerraLevelerConfig config = new TerraLevelerConfig();

        assertFalse(InventoryRetentionRules.isAlwaysProtected(Items.ROTTEN_FLESH));

        config.customRetainedItemsEnabled = true;
        config.retainedItemIds = new ArrayList<>(List.of("minecraft:rotten_flesh"));
        assertTrue(InventoryRetentionRules.isProtected(Items.ROTTEN_FLESH, config));
    }
}
