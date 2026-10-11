package cn.terraleveler;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Builds a cleanup return trip from server-confirmed outbound steps. */
final class CleanupRouteReversal {
   private CleanupRouteReversal() {
   }

   static List<SafeRoutePlanner.RouteStep> reverse(List<SafeRoutePlanner.RouteStep> outbound, BlockPos current, BlockPos anchor) {
      return reverse(outbound, current, anchor, anchor);
   }

   static List<SafeRoutePlanner.RouteStep> reverse(
      List<SafeRoutePlanner.RouteStep> outbound, BlockPos current, BlockPos anchor, BlockPos returnAnchor
   ) {
      if (outbound == null || outbound.isEmpty() || current == null || anchor == null) {
         return List.of();
      }
      if (returnAnchor == null) {
         return List.of();
      }

      List<BlockPos> nodes = new ArrayList<>(outbound.size() + 1);
      SafeRoutePlanner.RouteStep first = outbound.getFirst();
      if (first.origin() == null) {
         return List.of();
      }

      nodes.add(first.origin());
      BlockPos cursor = first.origin();

      for (SafeRoutePlanner.RouteStep step : outbound) {
         if (step.origin() == null || !cursor.equals(step.origin())) {
            return List.of();
         }

         nodes.add(step.position());
         cursor = step.position();
      }

      if (!nodes.getFirst().equals(anchor)) {
         return List.of();
      }

      int currentIndex = -1;

      for (int i = nodes.size() - 1; i >= 0; i--) {
         if (nodes.get(i).equals(current)) {
            currentIndex = i;
            break;
         }
      }

      if (currentIndex < 0) {
         return List.of();
      }

      List<SafeRoutePlanner.RouteStep> reversed = new ArrayList<>(currentIndex);

      for (int i = currentIndex; i > 0; i--) {
         SafeRoutePlanner.RouteStep forward = outbound.get(i - 1);
         if (forward.type() == SafeRoutePlanner.StepType.STEP_DOWN
            && forward.origin().getY() - forward.position().getY() > 1) {
            return List.of();
         }

         BlockPos destination = i == 1 ? returnAnchor : nodes.get(i - 1);
         SafeRoutePlanner.StepType type = i == 1 && !returnAnchor.equals(anchor)
            ? adjustedFinalType(nodes.get(i), destination)
            : reverseType(forward.type());
         if (type == null) {
            return List.of();
         }

         reversed.add(new SafeRoutePlanner.RouteStep(nodes.get(i), destination, type, null));
      }

      return List.copyOf(reversed);
   }

   private static SafeRoutePlanner.StepType adjustedFinalType(BlockPos origin, BlockPos destination) {
      int horizontal = Math.abs(destination.getX() - origin.getX()) + Math.abs(destination.getZ() - origin.getZ());
      int dy = destination.getY() - origin.getY();
      if (horizontal != 1 || dy < -1 || dy > 1) {
         return null;
      }
      if (dy < 0) {
         return SafeRoutePlanner.StepType.STEP_DOWN;
      }
      return dy > 0 ? SafeRoutePlanner.StepType.STEP_UP : SafeRoutePlanner.StepType.WALK;
   }

   private static SafeRoutePlanner.StepType reverseType(SafeRoutePlanner.StepType type) {
      return switch (type) {
         case WALK, BRIDGE -> SafeRoutePlanner.StepType.WALK;
         case STEP_UP, SCAFFOLD_UP -> SafeRoutePlanner.StepType.STEP_DOWN;
         case STEP_DOWN, WATER_BUCKET_FALL -> SafeRoutePlanner.StepType.STEP_UP;
      };
   }
}
