package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A stranded player walks down a scaffold stair even when steps next to it were taken away (0.8.86 test run). */
class Improvements0887Test {
    @Test
    void missingStairStepsAreRebuiltOnTheWayDown() {
        // Ground at y<=0; stair blocks at (x, x-2, 5) for x=3..12; the player stands on top at (12, 11, 5).
        Set<BlockPos> solid = new HashSet<>();
        for (int x = 3; x <= 12; x++) solid.add(new BlockPos(x, x - 2, 5));
        // The two steps behind the player were taken down by budget relief.
        solid.remove(new BlockPos(11, 9, 5));
        solid.remove(new BlockPos(10, 8, 5));
        SafeRoutePlanner.RouteEnvironment world = pos -> pos.getY() <= 0 || solid.contains(pos)
                ? SafeRoutePlanner.CellKind.SOLID : SafeRoutePlanner.CellKind.OPEN;
        BlockPos start = new BlockPos(12, 11, 5);
        assertTrue(ClimbBudgetRules.isStranded(start.getY(), 0, true));

        List<BlockPos> goals = ClimbBudgetRules.descentGoals(start, 0, feet ->
                world.cell(feet) == SafeRoutePlanner.CellKind.OPEN
                        && world.cell(feet.above()) == SafeRoutePlanner.CellKind.OPEN
                        && world.cell(feet.below()) == SafeRoutePlanner.CellKind.SOLID);
        assertFalse(goals.isEmpty());

        SafeRoutePlanner.PlanResult walk = SafeRoutePlanner.findRouteWithinVisitLimit(
                world, start, goals, area(), ClimbBudgetRules.descentPolicy(false), ClimbBudgetRules.DESCENT_BRIDGE_VISITS);
        assertFalse(walk.hasRoute() && !walk.steps().isEmpty());

        SafeRoutePlanner.PlanResult rebuild = SafeRoutePlanner.findRouteWithinVisitLimit(
                world, start, goals, area(), ClimbBudgetRules.descentPolicy(true), ClimbBudgetRules.DESCENT_BRIDGE_VISITS);
        assertTrue(rebuild.hasRoute());
        assertTrue(rebuild.steps().getLast().position().getY() <= 1 + ClimbBudgetRules.DESCENT_GROUND_TOLERANCE);
        int maxDrop = 0;
        BlockPos previous = start;
        for (SafeRoutePlanner.RouteStep step : rebuild.steps()) {
            maxDrop = Math.max(maxDrop, previous.getY() - step.position().getY());
            previous = step.position();
        }
        assertTrue(maxDrop <= 3, "drop " + maxDrop);

        // With the stair intact, walking down needs no new block.
        solid.add(new BlockPos(11, 9, 5));
        solid.add(new BlockPos(10, 8, 5));
        SafeRoutePlanner.PlanResult intact = SafeRoutePlanner.findRouteWithinVisitLimit(
                world, start, goals, area(), ClimbBudgetRules.descentPolicy(false), ClimbBudgetRules.DESCENT_BRIDGE_VISITS);
        assertTrue(intact.hasRoute());
    }

    private static WorkArea area() {
        return new WorkArea() {
            @Override public int minX() { return -30; }
            @Override public int maxX() { return 40; }
            @Override public int minZ() { return -30; }
            @Override public int maxZ() { return 40; }
            @Override public boolean isStrictInteriorXZ(int x, int z) { return false; }
            @Override public boolean permitsScaffoldAt(int x, int z) { return true; }
        };
    }
}
