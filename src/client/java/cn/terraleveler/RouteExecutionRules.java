package cn.terraleveler;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class RouteExecutionRules {
   private RouteExecutionRules() {
   }

   static RouteExecutionRules.CompletionDecision completionDecision(boolean taskNoLongerNeedsTravel, boolean hasStableRouteAnchor) {
      return completionDecision(taskNoLongerNeedsTravel, hasStableRouteAnchor, false);
   }

   static RouteExecutionRules.CompletionDecision completionDecision(
      boolean taskNoLongerNeedsTravel, boolean hasStableRouteAnchor, boolean continueCommittedRoute
   ) {
      if (!taskNoLongerNeedsTravel) {
         return RouteExecutionRules.CompletionDecision.CONTINUE;
      } else if (continueCommittedRoute) {
         return RouteExecutionRules.CompletionDecision.CONTINUE;
      } else {
         return hasStableRouteAnchor ? RouteExecutionRules.CompletionDecision.FINISH : RouteExecutionRules.CompletionDecision.DEFER_UNTIL_STABLE;
      }
   }

   static boolean shouldFinishRouteEarly(boolean taskResolved, boolean taskReadyOrResolved, boolean pendingScaffoldSteps) {
      return taskResolved || taskReadyOrResolved && !pendingScaffoldSteps;
   }

   static boolean allowsScaffoldFallback(boolean combatJourney, boolean cleanupJourney) {
      return allowsScaffoldFallback(combatJourney, cleanupJourney, false);
   }

   static boolean allowsScaffoldFallback(boolean combatJourney, boolean cleanupJourney, boolean flattenFinalCleanupJourney) {
      return !combatJourney && (!cleanupJourney || flattenFinalCleanupJourney);
   }

   static boolean allowsFlattenFinalCleanupBridge(int cleanupPass) {
      return cleanupPass == 1;
   }

   static boolean allowsRollingRetirement(boolean committedCleanupRoundTrip) {
      return !committedCleanupRoundTrip;
   }

   static int cleanupReservedPeak(int deployedScaffoldDebt, int routeScaffoldReserve) {
      if (deployedScaffoldDebt < 0 || routeScaffoldReserve <= 0) {
         return -1;
      }
      long peak = (long)deployedScaffoldDebt + routeScaffoldReserve;
      return peak <= Integer.MAX_VALUE ? (int)peak : -1;
   }

   static int effectiveScaffoldLimit(int normalBudget, int cleanupReservedPeak, boolean committedCleanupRoute) {
      int safeNormalBudget = Math.max(0, normalBudget);
      return committedCleanupRoute ? Math.max(safeNormalBudget, cleanupReservedPeak) : safeNormalBudget;
   }

   static boolean mayPlaceScaffold(int activeScaffolds, int effectiveLimit) {
      return activeScaffolds >= 0 && activeScaffolds < effectiveLimit;
   }

   static boolean hasCleanupRouteMaterial(int plannedScaffolds, int looseScaffolds, boolean materialBoxAvailable) {
      return plannedScaffolds >= 0
         && looseScaffolds >= 0
         && (looseScaffolds >= plannedScaffolds || materialBoxAvailable);
   }

   static boolean mayRetireFlattenCleanupRouteScaffold(
      int cleanupPass, boolean stablePlayerStand, boolean scaffoldStillTracked, boolean carriesPlayer, boolean neededByRemainingRoute, boolean safelyReachable
   ) {
      return allowsFlattenFinalCleanupBridge(cleanupPass)
         && stablePlayerStand
         && scaffoldStillTracked
         && !carriesPlayer
         && !neededByRemainingRoute
         && safelyReachable;
   }

   static boolean shouldBeginFlattenCleanupRouteReturn(int cleanupPass, int routeCreatedScaffolds, boolean cleanupTargetResolved) {
      return cleanupTargetResolved && allowsFlattenFinalCleanupBridge(cleanupPass) && routeCreatedScaffolds > 0;
   }

   static boolean shouldAdoptFallback(SafeRoutePlanner.PlanResult primary, SafeRoutePlanner.PlanResult fallback) {
      return fallback.hasProgressRoute() && !primary.hasRoute() ? fallback.hasRoute() || !primary.hasProgressRoute() : false;
   }

   static boolean mayUseRollingScaffoldReserve(int activeScaffolds, int scaffoldBudget, boolean hasOwnedPlayerSupport, boolean retirementPending) {
      return scaffoldBudget > 0 && activeScaffolds == scaffoldBudget && hasOwnedPlayerSupport && !retirementPending;
   }

   static boolean mayBootstrapRollingScaffoldOverflow(
      int activeScaffolds, int scaffoldBudget, boolean hasStableNaturalSupport, boolean overflowActive, boolean retirementPending
   ) {
      return scaffoldBudget > 0 && activeScaffolds == scaffoldBudget && hasStableNaturalSupport && !overflowActive && !retirementPending;
   }

   static boolean mayAdvanceRollingScaffoldOverflow(
      int activeScaffolds, int scaffoldBudget, boolean supportedByOverflowScaffold, boolean overflowActive, boolean retirementPending
   ) {
      return scaffoldBudget > 0 && activeScaffolds == scaffoldBudget + 1 && supportedByOverflowScaffold && overflowActive && !retirementPending;
   }

   static boolean shouldCleanScaffoldBeforeBridge(boolean rollingSwapAvailable) {
      return !rollingSwapAvailable;
   }

   static boolean mayRetireRollingScaffold(boolean stablePlayerStand, boolean oldSupportStillCarriesPlayer, boolean replacementCarriesPlayer) {
      return stablePlayerStand && !oldSupportStillCarriesPlayer && replacementCarriesPlayer;
   }

   static boolean mayReleaseRollingScaffoldOverflow(boolean stablePlayerStand, boolean overflowWasPlayerSupport, boolean overflowStillCarriesPlayer) {
      return stablePlayerStand && overflowWasPlayerSupport && !overflowStillCarriesPlayer;
   }

   static boolean mayRunOpportunisticInventory(boolean validatedWorkRouteActive, boolean reachedWorkAwaitingAction) {
      return !validatedWorkRouteActive && !reachedWorkAwaitingAction;
   }

   static boolean mayRunOpportunisticInventory(
      boolean validatedWorkRouteActive,
      boolean reachedWorkAwaitingAction,
      boolean actionIdle,
      boolean scaffoldPlacementPending
   ) {
      return actionIdle
         && !scaffoldPlacementPending
         && mayRunOpportunisticInventory(validatedWorkRouteActive, reachedWorkAwaitingAction);
   }

   static int boundedTemporaryRouteLimit(
      int activeScaffolds, int remainingPlacements, int currentLimit, int maximumLimit
   ) {
      if (activeScaffolds < 0 || remainingPlacements <= 0 || currentLimit < 0 || maximumLimit < currentLimit) {
         return -1;
      }
      long requiredLimit = (long)activeScaffolds + remainingPlacements;
      if (requiredLimit > maximumLimit) {
         return -1;
      }
      return Math.max(currentLimit, (int)requiredLimit);
   }

   static boolean repeatedWorkRouteArrivalsExceeded(int arrivals, int maximumArrivals) {
      return maximumArrivals > 0 && arrivals >= maximumArrivals;
   }

   static boolean mayRecoverWithoutScaffold(boolean flatteningJourney, boolean wallJourney) {
      return flatteningJourney && wallJourney;
   }

   static boolean canRecenterForScaffold(boolean hasCurrentStep, boolean safeOrigin, int recenterTicks, int maximumTicks) {
      return hasCurrentStep && safeOrigin && recenterTicks < maximumTicks;
   }

   static Set<BlockPos> protectedMiningSupports(List<SafeRoutePlanner.RouteStep> route, int routeIndex) {
      Set<BlockPos> supports = new HashSet<>();
      if (route == null) {
         return supports;
      }

      int first = Math.max(0, routeIndex);

      for (int index = first; index < route.size(); index++) {
         SafeRoutePlanner.RouteStep step = route.get(index);
         BlockPos scaffold = step.position().below();
         supports.add(scaffold.immutable());
         if (step.type() == SafeRoutePlanner.StepType.BRIDGE || step.type() == SafeRoutePlanner.StepType.SCAFFOLD_UP) {
            for (Direction direction : Direction.values()) {
               supports.add(scaffold.relative(direction).immutable());
            }
         }
      }

      return supports;
   }

   static boolean futurePlacementDependsOnSupport(
      List<SafeRoutePlanner.RouteStep> route, int routeIndex, BlockPos support
   ) {
      return support != null
         && route != null
         && routeIndex >= 0
         && routeIndex < route.size()
         && protectedMiningSupports(route, routeIndex + 1).contains(support);
   }

   static RouteExecutionRules.ScaffoldBudgetAssessment assessScaffoldRouteBudget(
      List<SafeRoutePlanner.RouteStep> route,
      Set<BlockPos> requiredPlacements,
      Set<BlockPos> ownedScaffolds,
      int activeScaffolds,
      int scaffoldBudget,
      BlockPos overflowTarget
   ) {
      if (route == null || requiredPlacements == null || ownedScaffolds == null || activeScaffolds < 0 || scaffoldBudget < 0) {
         return new RouteExecutionRules.ScaffoldBudgetAssessment(false, null, null, -1, activeScaffolds);
      }

      Set<BlockPos> simulatedOwned = new LinkedHashSet<>();
      for (BlockPos owned : ownedScaffolds) {
         if (owned != null) {
            simulatedOwned.add(owned.immutable());
         }
      }

      BlockPos simulatedOverflow = overflowTarget == null ? null : overflowTarget.immutable();
      int simulatedActive = activeScaffolds;
      int peakActive = activeScaffolds;

      for (int index = 0; index < route.size(); index++) {
         SafeRoutePlanner.RouteStep step = route.get(index);
         BlockPos placement = step.position().below().immutable();
         if (requiredPlacements.contains(placement)) {
            if (simulatedActive < scaffoldBudget) {
               simulatedOwned.add(placement);
               simulatedActive++;
               peakActive = Math.max(peakActive, simulatedActive);
            } else {
               BlockPos originSupport = step.origin() == null ? null : step.origin().below();
               BlockPos oldSupport = originSupport != null && simulatedOwned.contains(originSupport) ? originSupport.immutable() : null;
               boolean regularRolling = simulatedActive == scaffoldBudget && oldSupport != null;
               boolean bootstrapOverflow = simulatedActive == scaffoldBudget && oldSupport == null && simulatedOverflow == null;
               boolean advanceOverflow = simulatedActive == scaffoldBudget + 1
                  && oldSupport != null
                  && oldSupport.equals(simulatedOverflow);
               if (!regularRolling && !bootstrapOverflow && !advanceOverflow) {
                  return new RouteExecutionRules.ScaffoldBudgetAssessment(
                     false, oldSupport, placement, index, peakActive
                  );
               }

               if (oldSupport != null && futureRequiredPlacementDependsOnSupport(route, index, oldSupport, requiredPlacements)) {
                  return new RouteExecutionRules.ScaffoldBudgetAssessment(
                     false, oldSupport, placement, index, peakActive
                  );
               }

               simulatedOwned.add(placement);
               simulatedActive++;
               peakActive = Math.max(peakActive, simulatedActive);
               if (oldSupport == null) {
                  simulatedOverflow = placement;
               } else {
                  simulatedOwned.remove(oldSupport);
                  simulatedActive--;
                  if (oldSupport.equals(simulatedOverflow)) {
                     simulatedOverflow = placement;
                  }
               }
            }
         }

         BlockPos landingSupport = step.position().below();
         if (simulatedOverflow != null && !simulatedOverflow.equals(landingSupport)) {
            simulatedOwned.remove(simulatedOverflow);
            simulatedActive--;
            simulatedOverflow = null;
         }
      }

      return new RouteExecutionRules.ScaffoldBudgetAssessment(true, null, null, -1, peakActive);
   }

   static int minimumExecutableScaffoldBudget(
      List<SafeRoutePlanner.RouteStep> route,
      Set<BlockPos> requiredPlacements,
      Set<BlockPos> ownedScaffolds,
      int activeScaffolds,
      int minimumBudget,
      int maximumBudget,
      BlockPos overflowTarget
   ) {
      if (minimumBudget < 0 || maximumBudget < minimumBudget) {
         return -1;
      }

      for (int budget = minimumBudget; budget <= maximumBudget; budget++) {
         RouteExecutionRules.ScaffoldBudgetAssessment assessment = assessScaffoldRouteBudget(
            route, requiredPlacements, ownedScaffolds, activeScaffolds, budget, overflowTarget
         );
         if (assessment.executable() && assessment.peakActive() <= maximumBudget) {
            return budget;
         }
         if (budget == Integer.MAX_VALUE) {
            break;
         }
      }

      return -1;
   }

   static boolean futureRequiredPlacementDependsOnSupport(
      List<SafeRoutePlanner.RouteStep> route,
      int routeIndex,
      BlockPos support,
      Set<BlockPos> requiredPlacements
   ) {
      for (int index = routeIndex + 1; index < route.size(); index++) {
         BlockPos floor = route.get(index).position().below();
         if (floor.equals(support)) {
            return true;
         }
         if (requiredPlacements.contains(floor) && adjacent(floor, support)) {
            return true;
         }
      }
      return false;
   }

   private static boolean adjacent(BlockPos first, BlockPos second) {
      return Math.abs(first.getX() - second.getX())
         + Math.abs(first.getY() - second.getY())
         + Math.abs(first.getZ() - second.getZ()) == 1;
   }

   record ScaffoldBudgetAssessment(
      boolean executable,
      BlockPos blockedSupport,
      BlockPos blockedPlacement,
      int blockedStep,
      int peakActive
   ) {
   }

   enum CompletionDecision {
      CONTINUE,
      DEFER_UNTIL_STABLE,
      FINISH;
   }
}
