package cn.terraleveler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class FlattenCleanupExcursionRules {
   private FlattenCleanupExcursionRules() {
   }

   static boolean mayContinue(BlockPos initialTask, BlockPos nextTask, BlockPos returnAnchor, boolean sameCluster) {
      return initialTask != null
         && nextTask != null
         && returnAnchor != null
         && !initialTask.equals(returnAnchor.below())
         && !nextTask.equals(returnAnchor.below())
         && sameCluster;
   }

   static Optional<BlockPos> firstScaffoldAnchor(
      int routeCreatedScaffolds, SafeRoutePlanner.RouteStep pendingStep, BlockPos confirmedScaffold
   ) {
      if (routeCreatedScaffolds != 0
         || pendingStep == null
         || pendingStep.origin() == null
         || confirmedScaffold == null
         || pendingStep.type() != SafeRoutePlanner.StepType.BRIDGE
            && pendingStep.type() != SafeRoutePlanner.StepType.SCAFFOLD_UP
         || !confirmedScaffold.equals(pendingStep.position().below())) {
         return Optional.empty();
      }
      return Optional.of(pendingStep.origin().immutable());
   }

   static boolean mayContinueAfterTopologyDeferral(boolean deferred, boolean activeExcursion) {
      return deferred && activeExcursion;
   }

   static boolean mayDropOwnedSupport(
      boolean flattening,
      int cleanupPass,
      boolean ownedScaffold,
      boolean carriesPlayer,
      boolean safelyReachable,
      boolean cleanupTransaction
   ) {
      return flattening && cleanupPass >= 1 && ownedScaffold && carriesPlayer && safelyReachable && !cleanupTransaction;
   }

   static boolean shouldRebaseAfterFall(boolean cleanupTransaction, boolean authorizedSupportDrop) {
      return cleanupTransaction || authorizedSupportDrop;
   }

   static boolean didActuallyFall(BlockPos start, BlockPos expectedLanding, BlockPos actualLanding) {
      if (start == null || actualLanding == null) {
         return false;
      }
      if (actualLanding.getY() <= start.getY() - 2) {
         return true;
      }
      return expectedLanding != null
         && actualLanding.getY() == expectedLanding.getY()
         && Math.abs(actualLanding.getX() - expectedLanding.getX()) <= 1
         && Math.abs(actualLanding.getZ() - expectedLanding.getZ()) <= 1;
   }

   static List<BlockPos> remainingPrimaryTargets(
      List<BlockPos> targets, int currentIndex, Set<BlockPos> trackedScaffolds, Set<BlockPos> accessScaffolds
   ) {
      if (targets == null || trackedScaffolds == null || currentIndex >= targets.size()) {
         return List.of();
      }

      Set<BlockPos> access = accessScaffolds == null ? Set.of() : accessScaffolds;
      List<BlockPos> remaining = new ArrayList<>();
      for (int index = Math.max(0, currentIndex); index < targets.size(); index++) {
         BlockPos target = targets.get(index);
         if (target != null && trackedScaffolds.contains(target) && !access.contains(target)) {
            remaining.add(target.immutable());
         }
      }
      return List.copyOf(remaining);
   }
}
