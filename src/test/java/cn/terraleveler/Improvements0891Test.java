package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A complete bridge onto natural ground is the way off a high tower, not a trap (0.8.90 test run). */
class Improvements0891Test {
    private static SafeRoutePlanner.RouteStep step(int x, int y, int z, SafeRoutePlanner.StepType type) {
        return new SafeRoutePlanner.RouteStep(new BlockPos(x + 1, y, z), new BlockPos(x, y, z), type, null);
    }

    @Test
    void onlyACompleteBridgeEndingOnTerrainIsAllowed() {
        // Mountain (natural) at x <= -3600 up to Y=108; flattened ground elsewhere up to Y=61.
        SafeRoutePlanner.RouteEnvironment world = pos -> pos.getY() <= (pos.getX() <= -3600 ? 108 : 61)
                ? SafeRoutePlanner.CellKind.SOLID : SafeRoutePlanner.CellKind.OPEN;
        List<SafeRoutePlanner.RouteStep> toMountain = List.of(
                step(-3598, 109, -10026, SafeRoutePlanner.StepType.BRIDGE),
                step(-3599, 109, -10026, SafeRoutePlanner.StepType.BRIDGE),
                step(-3600, 109, -10026, SafeRoutePlanner.StepType.WALK),
                step(-3601, 109, -10026, SafeRoutePlanner.StepType.WALK));
        assertTrue(ClimbBudgetRules.completeToNaturalGround(true, toMountain, world, pos -> false));

        // The same steps as a PARTIAL route, or ending on an own scaffold, or on a bridge block: refused.
        assertFalse(ClimbBudgetRules.completeToNaturalGround(false, toMountain, world, pos -> false));
        Set<BlockPos> owned = Set.of(new BlockPos(-3601, 108, -10026));
        assertFalse(ClimbBudgetRules.completeToNaturalGround(true, toMountain, world, owned::contains));
        List<SafeRoutePlanner.RouteStep> midAir = List.of(
                step(-3570, 109, -10026, SafeRoutePlanner.StepType.BRIDGE),
                step(-3571, 109, -10026, SafeRoutePlanner.StepType.BRIDGE));
        assertFalse(ClimbBudgetRules.completeToNaturalGround(true, midAir, world, pos -> false));
    }
}
