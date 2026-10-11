package cn.terraleveler;

import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VerticalRecoveryRulesTest {
    @Test
    void bucketLifecycleItemsAreNeverDiscardedAsIncidentalPickup() {
        assertTrue(VerticalRecoveryRules.protectsInventoryItem(Items.WATER_BUCKET));
        assertTrue(VerticalRecoveryRules.protectsInventoryItem(Items.BUCKET));
        assertFalse(VerticalRecoveryRules.protectsInventoryItem(Items.DIORITE));
    }

    @Test
    void directDropRequiresTheUniqueOwnedOrPlannedSupport() {
        assertTrue(VerticalRecoveryRules.mayBreakUniqueSupport(true, 1, false, true, true));
        assertTrue(VerticalRecoveryRules.mayBreakUniqueSupport(true, 1, true, false, true));
        assertFalse(VerticalRecoveryRules.mayBreakUniqueSupport(true, 2, false, true, true));
        assertFalse(VerticalRecoveryRules.mayBreakUniqueSupport(true, 1, false, false, true));
        assertFalse(VerticalRecoveryRules.mayBreakUniqueSupport(false, 1, true, true, true));
        assertFalse(VerticalRecoveryRules.mayBreakUniqueSupport(true, 1, true, true, false));
    }

    @Test
    void waterFallbackDoesNotDependOnTheCurrentTargetsHeight() {
        assertTrue(VerticalRecoveryRules.mayAttemptWaterBucketFall(true, true, true, false, 1));
        assertFalse(VerticalRecoveryRules.mayAttemptWaterBucketFall(true, true, true, true, 1));
        assertFalse(VerticalRecoveryRules.mayAttemptWaterBucketFall(true, true, true, false, 0));
    }
}
