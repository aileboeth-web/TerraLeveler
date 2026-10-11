package cn.terraleveler;

import java.util.Optional;
import net.minecraft.core.BlockPos;

final class FlattenCleanupCheckpointRules {
   private FlattenCleanupCheckpointRules() {
   }

   static boolean matchesPendingStep(
      BlockPos committedEnd, BlockPos current, SafeRoutePlanner.RouteStep pending
   ) {
      return committedEnd != null
         && current != null
         && pending != null
         && committedEnd.equals(pending.origin())
         && current.equals(pending.position());
   }

   static Optional<SafeRoutePlanner.RouteStep> adjacentStep(BlockPos committedEnd, BlockPos current) {
      if (committedEnd == null || current == null || committedEnd.equals(current)) {
         return Optional.empty();
      }

      int horizontal = Math.abs(current.getX() - committedEnd.getX())
         + Math.abs(current.getZ() - committedEnd.getZ());
      int dy = current.getY() - committedEnd.getY();
      if (horizontal != 1 || dy < -1 || dy > 1) {
         return Optional.empty();
      }

      SafeRoutePlanner.StepType type = dy < 0
         ? SafeRoutePlanner.StepType.STEP_DOWN
         : dy > 0 ? SafeRoutePlanner.StepType.STEP_UP : SafeRoutePlanner.StepType.WALK;
      return Optional.of(new SafeRoutePlanner.RouteStep(committedEnd, current, type, null));
   }

   static boolean shouldAbandonRecordedRoute(boolean activeExcursion, boolean checkpointAccepted) {
      return activeExcursion && !checkpointAccepted;
   }

   static boolean shouldAbandonExactReverse(boolean startEqualsGoal, boolean reversedRouteEmpty) {
      return !startEqualsGoal && reversedRouteEmpty;
   }
}
