package cn.terraleveler;

import java.util.List;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MaterialTransactionRulesTest {
    @Test
    void extractionPrefersStableMainInventoryOverAutoRefilledHotbar() {
        assertEquals(12, ConstructionController.materialExtractionDestination(
                2, slot -> slot == 4 || slot == 12));
        assertEquals(12, ConstructionController.materialExtractionDestination(
                2, slot -> slot == 12));
        assertEquals(4, ConstructionController.materialExtractionDestination(
                2, slot -> slot == 4));
        assertEquals(-1, ConstructionController.materialExtractionDestination(
                2, slot -> false));
    }

    @Test
    void cleanupRouteChoosesMaterialThatCanMeetItsDemand() {
        assertEquals(0, ConstructionController.scaffoldMaterialPriority(13, 1728, 13));
        assertEquals(1, ConstructionController.scaffoldMaterialPriority(6, 1728, 13));
        assertEquals(0, ConstructionController.scaffoldMaterialPriority(6, 4, 13));
        assertEquals(2, ConstructionController.scaffoldMaterialPriority(0, 0, 13));
    }

    @Test
    void defaultPickupWhitelistOnlyAllowsDirt() {
        assertTrue(ConstructionController.isManagedPickupMaterial(Items.DIRT));
        assertFalse(ConstructionController.isManagedPickupMaterial(Items.COBBLESTONE));
        assertFalse(ConstructionController.isManagedPickupMaterial(Items.STONE));
        assertFalse(ConstructionController.isManagedPickupMaterial(Items.COAL));
    }

    @Test
    void inventoryDiscardUsesWholeStackOnlyWhenTheWholeStackIsNew() {
        assertEquals(1, ConstructionController.discardButtonForInventoryExcess(0, 64, 64));
        assertEquals(0, ConstructionController.discardButtonForInventoryExcess(0, 64, 7));
        assertEquals(0, ConstructionController.discardButtonForInventoryExcess(7, 7, 7));
        assertEquals(1, ConstructionController.discardButtonForInventoryExcess(28, 64, 36, 36));
        assertEquals(0, ConstructionController.discardButtonForInventoryExcess(28, 64, 64, 36));
    }

    @Test
    void wholeUnexpectedStackCanBeDroppedWhenTheBaselineRemains() {
        assertTrue(ConstructionController.canDiscardWholeInventoryStack(28, 64, 36, 36));
        assertTrue(ConstructionController.canDiscardWholeInventoryStack(28, 64, 36, 1));
        assertTrue(ConstructionController.canDiscardWholeInventoryStack(0, 64, 64, 1));
        assertFalse(ConstructionController.canDiscardWholeInventoryStack(28, 64, 64, 36));
    }

    @Test
    void mixedStackSplitPreservesOnlyTheMissingBaselineAmount() {
        assertEquals(20, ConstructionController.protectedBaselineInStack(20, 24, 0));
        assertEquals(8, ConstructionController.protectedBaselineInStack(20, 24, 12));
        assertEquals(0, ConstructionController.protectedBaselineInStack(20, 24, 20));
        assertEquals(24, ConstructionController.protectedBaselineInStack(40, 24, 0));
    }

    @Test
    void forcedPickupDiscardUsesWholeStackWhenThePendingAmountCoversIt() {
        assertEquals(1, ConstructionController.discardButtonForForcedInventoryPickup(3, 3));
        assertEquals(1, ConstructionController.discardButtonForForcedInventoryPickup(64, 64));
        assertEquals(0, ConstructionController.discardButtonForForcedInventoryPickup(3, 64));
    }

    @Test
    void sameItemBulkDiscardKeepsBaselineStackSafe() {
        assertEquals(1, ConstructionController.discardButtonForInventoryExcess(0, 192, 64, 1));
        assertEquals(1, ConstructionController.discardButtonForInventoryExcess(72, 136, 64, 64));
        assertEquals(0, ConstructionController.discardButtonForInventoryExcess(72, 123, 64, 64));
    }

    @Test
    void configuredPickupOverflowDropsOnlyOneWholeStackAndKeepsBaseline() {
        assertEquals(64, ConstructionController.configuredPickupAllowance(64, 0));
        assertEquals(80, ConstructionController.configuredPickupAllowance(64, 80));
        assertTrue(ConstructionController.mayDropConfiguredPickupStack(128, 0, 64, 64));
        assertTrue(ConstructionController.mayDropConfiguredPickupStack(100, 20, 64, 64));
        assertFalse(ConstructionController.mayDropConfiguredPickupStack(80, 80, 64, 64));
        assertFalse(ConstructionController.mayDropConfiguredPickupStack(100, 50, 64, 64));
    }

    @Test
    void configuredPickupSingleDiscardIsSafeWhenTheWholeStackIsMixed() {
        assertTrue(ConstructionController.mayDiscardConfiguredPickupSingle(65, 64, 64));
        assertTrue(ConstructionController.mayDiscardConfiguredPickupSingle(70, 66, 64));
        assertFalse(ConstructionController.mayDiscardConfiguredPickupSingle(64, 64, 64));
        assertFalse(ConstructionController.mayDiscardConfiguredPickupSingle(65, 65, 64));
    }

    @Test
    void unexpectedPickupPendingAmountCannotCrossTheBaseline() {
        assertEquals(38, ConstructionController.safePendingDiscardAmount(68, 106, 69));
        assertEquals(0, ConstructionController.safePendingDiscardAmount(68, 68, 69));
        assertEquals(0, ConstructionController.safePendingDiscardAmount(68, 37, 69));
        assertEquals(12, ConstructionController.safePendingDiscardAmount(0, 12, 69));
    }


    @Test
    void emptyNamedShulkerBoxesAreNeverClaimedForOverflowStorage() {
        assertTrue(ConstructionController.materialStorageBoxEligible(List.of(), Items.COBBLESTONE, false));
        assertFalse(ConstructionController.materialStorageBoxEligible(List.of(), Items.COBBLESTONE, true));
    }
}
