package cn.terraleveler;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

final class InventoryRetentionRules {
    private static final Set<Item> GOLD_ITEMS = Set.of(
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            Items.GOLDEN_SWORD, Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL,
            Items.GOLDEN_HOE, Items.GOLD_INGOT, Items.GOLD_NUGGET, Items.GOLDEN_HORSE_ARMOR);
    private static final Set<Item> IRON_ITEMS = Set.of(
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.IRON_SWORD, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE,
            Items.IRON_INGOT, Items.IRON_NUGGET, Items.IRON_HORSE_ARMOR,
            Items.COMPASS, Items.SHEARS, Items.FLINT_AND_STEEL);
    private static final Set<Item> BUCKET_ITEMS = Set.of(
            Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.POWDER_SNOW_BUCKET,
            Items.MILK_BUCKET, Items.COD_BUCKET, Items.SALMON_BUCKET, Items.TROPICAL_FISH_BUCKET,
            Items.PUFFERFISH_BUCKET, Items.AXOLOTL_BUCKET, Items.TADPOLE_BUCKET);

    private InventoryRetentionRules() { }

    static boolean isCustomRetained(Item item, TerraLevelerConfig config) {
        if (item == null || config == null || !config.customRetainedItemsEnabled) return false;
        return config.retainedItemIds.contains(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    static boolean isEnabledMetalDiscard(Item item, TerraLevelerConfig config) {
        if (item == null || config == null || isCustomRetained(item, config)) return false;
        return config.discardGoldItems && GOLD_ITEMS.contains(item)
                || config.discardIronItems && IRON_ITEMS.contains(item);
    }

    static boolean isAlwaysProtected(Item item) {
        if (item == null) return false;
        if (BUCKET_ITEMS.contains(item) || item == Items.SHIELD
                || item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) return true;
        return item != Items.ROTTEN_FLESH && new ItemStack(item).get(DataComponents.FOOD) != null;
    }

    static boolean isProtected(Item item, TerraLevelerConfig config) {
        if (item == null) return false;
        if (isCustomRetained(item, config)) return true;
        if (isEnabledMetalDiscard(item, config)) return false;
        return item.getDefaultMaxStackSize() == 1 || isAlwaysProtected(item);
    }
}
