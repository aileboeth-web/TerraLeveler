package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Inherited scaffolds and relief-scan throttling (0.8.82 island test run). */
class Improvements0883Test {
    @Test
    void scaffoldsTakenOverFromTheStuckRunGetTheirOwnAllowance() {
        // 19:37: 143 scaffolds restored with a configured budget of 128.
        assertEquals(271, ScaffoldBudgetRules.withInheritedAllowance(128, 143));
        // The allowance shrinks as inherited scaffolds are taken down.
        assertEquals(128, ScaffoldBudgetRules.withInheritedAllowance(128, 0));
        // A disabled budget stays disabled.
        assertEquals(0, ScaffoldBudgetRules.withInheritedAllowance(0, 143));
    }

    @Test
    void aFruitlessReliefScanRestsUntilSomethingChanges() {
        BlockPos start = new BlockPos(-3510, 103, -9991);
        long exhausted = 1000L;
        assertTrue(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 20, exhausted, 143, 143, start, start));
        assertTrue(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 20, exhausted, 143, 143, start.east(3), start));
        // Time is up, a scaffold came or went, or the player moved away: scan again.
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 600, exhausted, 143, 143, start, start));
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 20, exhausted, 142, 143, start, start));
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 20, exhausted, 143, 143, start.east(8), start));
        assertFalse(ScaffoldBudgetRules.reliefScanCoolingDown(exhausted + 20, Long.MIN_VALUE, 143, 143, start, null));
    }
}
