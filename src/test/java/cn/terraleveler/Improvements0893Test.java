package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Scaffolds left high above the work on a mountain no longer use up the budget (0.8.92 test run). */
class Improvements0893Test {
    @Test
    void scaffoldsTwoBandsAboveTheWorkAreParked() {
        // Region -56,-158: work down at Y=70, scaffolds left at Y=86..104 from the upper bands.
        List<BlockPos> owned = new ArrayList<>();
        for (int i = 0; i < 86; i++) owned.add(new BlockPos(-3560 + i % 20, 86 + i % 19, -10063));
        for (int i = 0; i < 43; i++) owned.add(new BlockPos(-3560 + i % 20, 68 + i % 12, -10090));
        int parked = ScaffoldBudgetRules.parkedScaffolds(owned, Set.of(), 70);
        // Everything at Y >= 82 is parked: all 86 upper ones, none of the 68..79 working ones.
        assertEquals(86, parked);
        assertEquals(128 + 86, ScaffoldBudgetRules.withInheritedAllowance(128, parked));

        // Inherited scaffolds already have their own allowance and are not counted twice.
        Set<BlockPos> inherited = Set.copyOf(owned.subList(0, 10));
        assertEquals(76, ScaffoldBudgetRules.parkedScaffolds(owned, inherited, 70));
        // Nothing is parked before any work level is known to be lower.
        assertEquals(0, ScaffoldBudgetRules.parkedScaffolds(owned, Set.of(), 104));
    }
}
