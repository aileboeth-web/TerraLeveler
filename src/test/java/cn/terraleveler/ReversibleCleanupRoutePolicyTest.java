package cn.terraleveler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static cn.terraleveler.SafeRoutePlanner.CellKind.OPEN;
import static cn.terraleveler.SafeRoutePlanner.CellKind.SOLID;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.FOUND;
import static cn.terraleveler.SafeRoutePlanner.PlanStatus.NO_ROUTE;
import static cn.terraleveler.SafeRoutePlanner.StepType.STEP_DOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReversibleCleanupRoutePolicyTest {
   @Test
   void finalCleanupBridgePolicyRejectsAOneWayTwoBlockDrop() {
      FakeEnvironment environment = new FakeEnvironment();
      BlockPos start = new BlockPos(0, 4, 0);
      BlockPos landing = new BlockPos(1, 2, 0);
      environment.addStand(start).addStand(landing);

      SafeRoutePlanner.PlanResult ordinary = SafeRoutePlanner.findRoute(
         environment, start, List.of(landing), trench(), SafeRoutePlanner.RoutePolicy.flatteningFloorWithBridges()
      );
      SafeRoutePlanner.PlanResult reversible = SafeRoutePlanner.findRoute(
         environment, start, List.of(landing), trench(), SafeRoutePlanner.RoutePolicy.flatteningFloorWithReversibleBridges()
      );

      assertEquals(FOUND, ordinary.status());
      assertEquals(STEP_DOWN, ordinary.steps().getFirst().type());
      assertEquals(NO_ROUTE, reversible.status());
   }

   @Test
   void finalCleanupUsesAReversibleStairDetourInsteadOfADeepDropShortcut() {
      FakeEnvironment environment = new FakeEnvironment();
      BlockPos start = new BlockPos(0, 4, 0);
      BlockPos landing = new BlockPos(1, 2, 0);
      environment.addStand(start)
         .addStand(new BlockPos(0, 4, 1))
         .addStand(new BlockPos(1, 3, 1))
         .addStand(landing);

      SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(
         environment, start, List.of(landing), trench(), SafeRoutePlanner.RoutePolicy.flatteningFloorWithReversibleBridges()
      );

      assertEquals(FOUND, result.status());
      assertTrue(result.steps().size() >= 3);
      assertTrue(result.steps().stream().allMatch(step -> step.origin().getY() - step.position().getY() <= 1));
   }

   private static TrenchPlan trench() {
      return new TrenchPlan(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0), 1, 1, 1, 0);
   }

   private static final class FakeEnvironment implements SafeRoutePlanner.RouteEnvironment {
      private final Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();

      private FakeEnvironment addStand(BlockPos feet) {
         this.cells.put(feet.below().immutable(), SOLID);
         return this;
      }

      @Override
      public SafeRoutePlanner.CellKind cell(BlockPos position) {
         return this.cells.getOrDefault(position, OPEN);
      }
   }
}
