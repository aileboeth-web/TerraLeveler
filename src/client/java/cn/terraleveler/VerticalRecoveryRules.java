package cn.terraleveler;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

final class VerticalRecoveryRules {
    private VerticalRecoveryRules() { }

    static boolean protectsInventoryItem(Item item) {
        return item == Items.WATER_BUCKET || item == Items.BUCKET;
    }

    static boolean mayBreakUniqueSupport(boolean excavation,
                                         int supportingBlockCount,
                                         boolean trackedScaffold,
                                         boolean plannedExcavation,
                                         boolean safelyReachable) {
        return excavation
                && supportingBlockCount == 1
                && (trackedScaffold || plannedExcavation)
                && safelyReachable;
    }

    static boolean mayAttemptWaterBucketFall(boolean taskPresent,
                                             boolean startPresent,
                                             boolean stateAllowed,
                                             boolean nether,
                                             int waterBucketCount) {
        return taskPresent && startPresent && stateAllowed && !nether && waterBucketCount > 0;
    }
}
