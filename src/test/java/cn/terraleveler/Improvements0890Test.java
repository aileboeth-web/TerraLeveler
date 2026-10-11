package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** No rolling sky-bridge over a deadly drop; stranded ground goals below the target level (0.8.89 test run). */
class Improvements0890Test {
    private static SafeRoutePlanner.RouteStep bridge(int x, int feetY, int z) {
        return new SafeRoutePlanner.RouteStep(new BlockPos(x + 1, feetY, z), new BlockPos(x, feetY, z), SafeRoutePlanner.StepType.BRIDGE, null);
    }

    @Test
    void aBridgeHighOverTheFlattenedGroundIsMeasured() {
        // Flattened ground with its top at Y=61; the bridge carries the player's feet at Y=96.
        SafeRoutePlanner.RouteEnvironment world = pos -> pos.getY() <= 61
                ? SafeRoutePlanner.CellKind.SOLID : SafeRoutePlanner.CellKind.OPEN;
        List<SafeRoutePlanner.RouteStep> steps = List.of(
                new SafeRoutePlanner.RouteStep(new BlockPos(-3562, 96, -10012), new BlockPos(-3563, 96, -10012), SafeRoutePlanner.StepType.WALK, null),
                bridge(-3564, 96, -10012), bridge(-3565, 96, -10012));
        assertEquals(33, ClimbBudgetRules.maximumBridgeDrop(steps, world));

        // Budget used up (headroom 0) and 16 survivable at full health: refused.
        assertTrue(ClimbBudgetRules.unsafeRollingBridge(64, 0, 33, 16));
        // Enough headroom (nothing retired behind the player) or a shallow terrace gap: allowed.
        assertFalse(ClimbBudgetRules.unsafeRollingBridge(64, 64, 33, 16));
        assertFalse(ClimbBudgetRules.unsafeRollingBridge(64, 0, 5, 16));
    }

    @Test
    void groundJustBelowTheTargetLevelCountsForTheWalkDown() {
        // Target ground 62, but the real ground here is one lower (floor 61, feet 62).
        BlockPos start = new BlockPos(-3563, 96, -10012);
        List<BlockPos> goals = ClimbBudgetRules.descentGoals(start, 62, feet -> feet.getY() == 62);
        assertFalse(goals.isEmpty());
        assertEquals(new BlockPos(-3563, 62, -10012), goals.getFirst());
    }
}
