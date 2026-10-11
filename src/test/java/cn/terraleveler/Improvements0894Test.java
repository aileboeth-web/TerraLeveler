package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Obsolete scaffolds near the player are swept before it walks off (0.8.93 test run left 1156). */
class Improvements0894Test {
    @Test
    void leavingTheAreaTriggersTheSweep() {
        BlockPos feet = new BlockPos(0, 80, 0);
        assertFalse(ObsoleteScaffoldRules.leavingArea(feet, new BlockPos(12, 75, -12)));
        assertTrue(ObsoleteScaffoldRules.leavingArea(feet, new BlockPos(13, 75, 0)));
    }

    @Test
    void sweepCandidatesAreNearReachableTopsNearestFirst() {
        BlockPos feet = new BlockPos(0, 80, 0);
        List<BlockPos> owned = List.of(
                new BlockPos(3, 84, 0),   // near, top of its column
                new BlockPos(3, 83, 0),   // below another scaffold: not a top
                new BlockPos(1, 81, 1),   // nearest
                new BlockPos(10, 88, 0),  // highest still reachable (feet + 8)
                new BlockPos(5, 89, 0),   // too high above the feet
                new BlockPos(2, 79, 0),   // below the feet (floor level)
                new BlockPos(17, 82, 0)); // too far
        List<BlockPos> candidates = ObsoleteScaffoldRules.sweepCandidates(owned, feet, pos -> true);
        assertEquals(List.of(new BlockPos(1, 81, 1), new BlockPos(3, 84, 0), new BlockPos(10, 88, 0)), candidates);
        // Scaffolds that still have work around them are kept.
        assertEquals(List.of(new BlockPos(3, 84, 0)),
                ObsoleteScaffoldRules.sweepCandidates(owned, feet, pos -> pos.getX() == 3));
    }
}
