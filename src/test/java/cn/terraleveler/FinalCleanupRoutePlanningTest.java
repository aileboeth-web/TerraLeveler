package cn.terraleveler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalCleanupRoutePlanningTest {
   @Test
   void forbiddenConflictMakesPlannerChooseAnotherBridge() {
      Map<BlockPos, SafeRoutePlanner.CellKind> cells = new HashMap<>();
      SafeRoutePlanner.RouteEnvironment environment = position -> cells.getOrDefault(position, SafeRoutePlanner.CellKind.OPEN);
      BlockPos start = new BlockPos(2, 1, 2);
      BlockPos goal = new BlockPos(5, 1, 2);
      BlockPos forbidden = new BlockPos(3, 0, 2);
      cells.put(start.below(), SafeRoutePlanner.CellKind.SOLID);

      SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(
         environment,
         start,
         List.of(goal),
         openArea(),
         SafeRoutePlanner.RoutePolicy.flatteningFloorWithReversibleBridges(),
         Set.of(forbidden)
      );

      assertEquals(SafeRoutePlanner.PlanStatus.FOUND, result.status());
      assertTrue(
         result.steps()
            .stream()
            .filter(step -> step.type() == SafeRoutePlanner.StepType.BRIDGE || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP)
            .noneMatch(step -> step.position().below().equals(forbidden))
      );
   }

   @Test
   void topologyReplanOnlyAcceptsNewConflictsWithinTheBound() {
      BlockPos conflict = new BlockPos(3, 0, 2);
      assertTrue(ConstructionController.mayScheduleFlattenCleanupTopologyReplan(Set.of(), conflict));
      assertFalse(ConstructionController.mayScheduleFlattenCleanupTopologyReplan(Set.of(conflict), conflict));

      Set<BlockPos> exhausted = new java.util.HashSet<>();
      for (int index = 0; index < 24; index++) {
         exhausted.add(new BlockPos(index, 0, 0));
      }
      assertFalse(ConstructionController.mayScheduleFlattenCleanupTopologyReplan(exhausted, new BlockPos(30, 0, 0)));
   }

   @Test
   void deterministicFailureOnlyBlocksTheUnchangedWorldState() {
      BlockPos start = new BlockPos(2, 1, 2);
      assertTrue(ConstructionController.sameFlattenCleanupTopologyFailureState(start, 113, start, 113));
      assertFalse(ConstructionController.sameFlattenCleanupTopologyFailureState(start, 113, start.east(), 113));
      assertFalse(ConstructionController.sameFlattenCleanupTopologyFailureState(start, 113, start, 112));
   }

   private static WorkArea openArea() {
      return new WorkArea() {
         @Override
         public int minX() {
            return 0;
         }

         @Override
         public int maxX() {
            return 8;
         }

         @Override
         public int minZ() {
            return 0;
         }

         @Override
         public int maxZ() {
            return 4;
         }

         @Override
         public boolean isStrictInteriorXZ(int x, int z) {
            return false;
         }

         @Override
         public boolean permitsScaffoldAt(int x, int z) {
            return true;
         }
      };
   }
}
