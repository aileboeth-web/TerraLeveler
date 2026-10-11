package cn.terraleveler;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BatchBreakExecutorTest {
    @Test
    void fastBatchIncludesTwoTickAndInstantBlocksButRejectsSlowerBlocks() {
        assertFalse(BatchBreakExecutor.isBatchProgress(Math.nextDown(.5F)));
        assertTrue(BatchBreakExecutor.isBatchProgress(.5F));
        assertTrue(BatchBreakExecutor.isBatchProgress(1.0F));
        assertTrue(BatchBreakExecutor.isBatchProgress(Float.POSITIVE_INFINITY));
    }

    @Test
    void deterministicProgressRequiresARealFastToolInsteadOfStalePlayerAttributes() {
        assertEquals(.075F, BatchBreakExecutor.deterministicBreakProgress(1.5F, .2F, false), .0001F);
        assertFalse(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(1.5F, .2F, false)));
        assertTrue(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(35.0F, 1.5F, true)));
        assertEquals(0.0F, BatchBreakExecutor.deterministicBreakProgress(35.0F, -1.0F, true));
    }

    @Test
    void printerStyleThresholdDependsOnHardnessNotBlockIdentity() {
        float efficiencyFiveNetheriteScore = 35.0F;

        assertTrue(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(efficiencyFiveNetheriteScore, 1.5F, true)));
        assertTrue(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(efficiencyFiveNetheriteScore, 2.0F, true)));
        assertFalse(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(efficiencyFiveNetheriteScore, 3.0F, true)));
        assertFalse(BatchBreakExecutor.isBatchProgress(
                BatchBreakExecutor.deterministicBreakProgress(efficiencyFiveNetheriteScore, 4.5F, true)));
    }

    @Test
    void sendBudgetAppliesBothPerTickAndPendingBackpressureLimits() {
        assertEquals(20, BatchBreakExecutor.availableSendBudget(20, 0, 40));
        assertEquals(5, BatchBreakExecutor.availableSendBudget(20, 35, 40));
        assertEquals(0, BatchBreakExecutor.availableSendBudget(20, 40, 40));
        assertEquals(0, BatchBreakExecutor.availableSendBudget(0, 0, 40));
    }

    @Test
    @Disabled("Requires the fully bound Minecraft item component registry")
    void batchCannotSpendDurabilityReservedForToolProtection() {
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setDamageValue(pickaxe.getMaxDamage() - 37);

        assertEquals(5, BatchBreakExecutor.safeToolBreakBudget(pickaxe, 32));
        assertEquals(0, BatchBreakExecutor.safeToolBreakBudget(pickaxe, 37));
        assertEquals(Integer.MAX_VALUE,
                BatchBreakExecutor.safeToolBreakBudget(new ItemStack(Items.APPLE), 32));
        assertEquals(0, BatchBreakExecutor.safeToolBreakBudget(ItemStack.EMPTY, 32));
    }

    @Test
    void repeatedBatchTimeoutsFallBackToTheSingleTargetExecutor() {
        assertTrue(BatchBreakExecutor.mayBatchAttempt(0, 2));
        assertTrue(BatchBreakExecutor.mayBatchAttempt(1, 2));
        assertFalse(BatchBreakExecutor.mayBatchAttempt(2, 2));
    }
}
