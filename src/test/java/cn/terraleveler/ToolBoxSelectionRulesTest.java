package cn.terraleveler;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolBoxSelectionRulesTest {
    @Test
    void toolBoxNameMustMatchExactlyAndBeUnique() {
        ToolBoxSelectionRules.BoxSelection found = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(9, true, "dirt", true),
                new ToolBoxSelectionRules.BoxCandidate(12, true, "工具", true),
                new ToolBoxSelectionRules.BoxCandidate(15, false, "工具", true)), List.of("工具", "备用工具"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.FOUND, found.status());
        assertEquals(12, found.inventorySlot());
        assertEquals("工具", found.customName());

        ToolBoxSelectionRules.BoxSelection missing = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(12, true, "tools", true)), List.of("工具"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.MISSING, missing.status());

        ToolBoxSelectionRules.BoxSelection ambiguous = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(12, true, "工具", true),
                new ToolBoxSelectionRules.BoxCandidate(14, true, "工具", true)), List.of("工具"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.AMBIGUOUS, ambiguous.status());
    }

    @Test
    void configuredToolBoxesMustContainAUsableRequestedTool() {
        ToolBoxSelectionRules.BoxSelection unusable = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(12, true, "工具", false)), List.of("工具"));
        assertEquals(ToolBoxSelectionRules.BoxStatus.NO_USABLE_TOOL, unusable.status());

        ToolBoxSelectionRules.BoxSelection disabled = ToolBoxSelectionRules.uniqueToolBox(List.of(
                new ToolBoxSelectionRules.BoxCandidate(12, true, "工具", true)), List.of());
        assertEquals(ToolBoxSelectionRules.BoxStatus.MISSING, disabled.status());
    }

    @Test
    void bestToolRequiresTheCorrectKindAndDurabilityReserve() {
        List<ToolBoxSelectionRules.ToolCandidate> candidates = List.of(
                new ToolBoxSelectionRules.ToolCandidate(0, ToolBoxSelectionRules.ToolKind.PICKAXE, 35.0F, 32),
                new ToolBoxSelectionRules.ToolCandidate(1, ToolBoxSelectionRules.ToolKind.SHOVEL, 40.0F, 900),
                new ToolBoxSelectionRules.ToolCandidate(2, ToolBoxSelectionRules.ToolKind.PICKAXE, 9.0F, 900),
                new ToolBoxSelectionRules.ToolCandidate(3, ToolBoxSelectionRules.ToolKind.PICKAXE, 35.0F, 500),
                new ToolBoxSelectionRules.ToolCandidate(4, ToolBoxSelectionRules.ToolKind.PICKAXE, 35.0F, 800));

        assertEquals(4, ToolBoxSelectionRules.bestToolSlot(
                candidates, ToolBoxSelectionRules.ToolKind.PICKAXE, 32));
        assertEquals(1, ToolBoxSelectionRules.bestToolSlot(
                candidates, ToolBoxSelectionRules.ToolKind.SHOVEL, 32));
    }

    @Test
    void ordinaryHotbarItemsNeverBecomeToolBoxSwapDestinations() {
        List<ToolBoxSelectionRules.HotbarCandidate> candidates = List.of(
                new ToolBoxSelectionRules.HotbarCandidate(0, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE),
                new ToolBoxSelectionRules.HotbarCandidate(1, ToolBoxSelectionRules.ToolKind.PICKAXE, false, 900),
                new ToolBoxSelectionRules.HotbarCandidate(2, ToolBoxSelectionRules.ToolKind.SHOVEL, false, 20),
                new ToolBoxSelectionRules.HotbarCandidate(3, ToolBoxSelectionRules.ToolKind.OTHER, true, 0));

        assertEquals(2, ToolBoxSelectionRules.hotbarDestination(0, candidates, 32));
        assertEquals(2, ToolBoxSelectionRules.hotbarDestination(1, candidates, 32));
        assertEquals(3, ToolBoxSelectionRules.hotbarDestination(0, List.of(
                candidates.get(0), candidates.get(3)), 32));
        assertEquals(-1, ToolBoxSelectionRules.hotbarDestination(0, List.of(candidates.get(0)), 32));
    }

    @Test
    void fullHotbarEvacuatesANonSelectedOrdinaryItemWithoutMovingTheToolBox() {
        List<ToolBoxSelectionRules.HotbarCandidate> candidates = List.of(
                new ToolBoxSelectionRules.HotbarCandidate(0, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE),
                new ToolBoxSelectionRules.HotbarCandidate(1, ToolBoxSelectionRules.ToolKind.PICKAXE, false, 900),
                new ToolBoxSelectionRules.HotbarCandidate(2, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE),
                new ToolBoxSelectionRules.HotbarCandidate(8, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE));

        assertEquals(8, ToolBoxSelectionRules.hotbarEvacuationSlot(0, 2, candidates));
        assertEquals(-1, ToolBoxSelectionRules.hotbarEvacuationSlot(0, 8, List.of(
                new ToolBoxSelectionRules.HotbarCandidate(0, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE),
                new ToolBoxSelectionRules.HotbarCandidate(1, ToolBoxSelectionRules.ToolKind.PICKAXE, false, 900),
                new ToolBoxSelectionRules.HotbarCandidate(8, ToolBoxSelectionRules.ToolKind.OTHER, false, Integer.MAX_VALUE))));
    }

    @Test
    void safeToolsStayInTheHotbarUntilTheyReachTheReserve() {
        assertEquals(4, ToolBoxSelectionRules.hotbarDestination(1, List.of(
                new ToolBoxSelectionRules.HotbarCandidate(1, ToolBoxSelectionRules.ToolKind.SHOVEL, false, 1500),
                new ToolBoxSelectionRules.HotbarCandidate(4, ToolBoxSelectionRules.ToolKind.OTHER, true, 0)), 32));
        assertEquals(1, ToolBoxSelectionRules.hotbarDestination(1, List.of(
                new ToolBoxSelectionRules.HotbarCandidate(1, ToolBoxSelectionRules.ToolKind.SHOVEL, false, 32),
                new ToolBoxSelectionRules.HotbarCandidate(4, ToolBoxSelectionRules.ToolKind.OTHER, true, 0)), 32));
    }

    @Test
    void toolBoxWaitsAfterSubmittingCleanupForAFullHotbar() {
        assertTrue(ConstructionController.shouldRetryToolBoxAfterInventoryCleanup(
                ToolBoxTransaction.BeginResult.NEEDS_HOTBAR_SPACE, true, 2));
        assertTrue(ConstructionController.shouldRetryToolBoxAfterInventoryCleanup(
                ToolBoxTransaction.BeginResult.NEEDS_HOTBAR_SPACE, false, 1));
        assertFalse(ConstructionController.shouldRetryToolBoxAfterInventoryCleanup(
                ToolBoxTransaction.BeginResult.NEEDS_HOTBAR_SPACE, false, 0));
        assertFalse(ConstructionController.shouldRetryToolBoxAfterInventoryCleanup(
                ToolBoxTransaction.BeginResult.FAILED_CONTINUE, true, 2));
    }

    @Test
    void serverConfirmationMustRemainStableForFourTicks() {
        int stable = 0;
        stable = ToolBoxTransaction.nextStableTicks(stable, true);
        stable = ToolBoxTransaction.nextStableTicks(stable, true);
        stable = ToolBoxTransaction.nextStableTicks(stable, false);
        assertEquals(0, stable);
        for (int tick = 0; tick < ToolBoxTransaction.CONFIRM_TICKS; tick++) {
            stable = ToolBoxTransaction.nextStableTicks(stable, true);
        }
        assertTrue(ToolBoxTransaction.stableConfirmed(stable));
    }
}
