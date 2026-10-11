package cn.terraleveler;

import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class NavigationPlanner {
   private NavigationPlanner() {
   }

   public static NavigationPlan plan(Minecraft mc, NavigationRequest request) {
      return plan(mc, request, Set.of());
   }

   static NavigationPlan plan(Minecraft mc, NavigationRequest request, Set<BlockPos> forbiddenScaffoldPlacements) {
      return plan(mc, request, forbiddenScaffoldPlacements, false);
   }

   /** With {@code allowExpandedSearch}, a failed search is retried once with larger bounds. */
   static NavigationPlan plan(Minecraft mc, NavigationRequest request, Set<BlockPos> forbiddenScaffoldPlacements, boolean allowExpandedSearch) {
      Objects.requireNonNull(mc, "mc");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(forbiddenScaffoldPlacements, "forbiddenScaffoldPlacements");
      NavigationPlan plan = planWith(
         request,
         policy -> SafeRoutePlanner.findRoute(
            mc, request.start(), request.goal().candidatePoses(), request.workArea(), policy, forbiddenScaffoldPlacements
         )
      );
      if (!allowExpandedSearch || !shouldExpandSearch(plan)) return plan;
      NavigationPlan expanded = planWith(
         request,
         policy -> SafeRoutePlanner.findRouteExpanded(
            mc, request.start(), request.goal().candidatePoses(), request.workArea(), policy, forbiddenScaffoldPlacements
         )
      );
      logExpandedSearch(request, plan, expanded);
      return expanded;
   }

   public static NavigationPlan plan(SafeRoutePlanner.RouteEnvironment environment, NavigationRequest request) {
      return plan(environment, request, Set.of());
   }

   static NavigationPlan plan(
      SafeRoutePlanner.RouteEnvironment environment, NavigationRequest request, Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      Objects.requireNonNull(environment, "environment");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(forbiddenScaffoldPlacements, "forbiddenScaffoldPlacements");
      NavigationPlan plan = planWith(
         request,
         policy -> SafeRoutePlanner.findRoute(
            environment, request.start(), request.goal().candidatePoses(), request.workArea(), policy, forbiddenScaffoldPlacements
         )
      );
      if (!shouldExpandSearch(plan)) return plan;
      return planWith(
         request,
         policy -> SafeRoutePlanner.findRouteExpanded(
            environment, request.start(), request.goal().candidatePoses(), request.workArea(), policy, forbiddenScaffoldPlacements
         )
      );
   }

   /**
    * The normal 24-block search box is retried once with larger bounds when every policy
    * failed only for lack of search room; other failures (unsafe start, no safe goal) are final.
    */
   static boolean shouldExpandSearch(NavigationPlan plan) {
      if (plan.selected().hasProgressRoute()) return false;
      if (SafeRoutePlanner.expandableFailure(plan.primary().status())) return true;
      return plan.fallbackAttempt() != null && SafeRoutePlanner.expandableFailure(plan.fallbackAttempt().status());
   }

   private static NavigationPlan planWith(NavigationRequest request, NavigationPlanner.RouteCalculation calculation) {
      return select(request, calculation.calculate(request.primaryPolicy()), calculation);
   }

   private static void logExpandedSearch(NavigationRequest request, NavigationPlan normal, NavigationPlan expanded) {
      TerraLevelerDiagnostics.log(
         "ROUTE",
         "search-expanded start=" + request.start().toShortString()
            + " normalPrimary=" + normal.primary().status()
            + " normalFallback=" + normal.fallbackResult().map(SafeRoutePlanner.PlanResult::status).orElse(null)
            + " expandedPrimary=" + expanded.primary().status()
            + " expandedFallback=" + expanded.fallbackResult().map(SafeRoutePlanner.PlanResult::status).orElse(null)
            + " selected=" + expanded.selection()
            + " steps=" + expanded.selected().steps().size()
      );
   }

   private static NavigationPlan select(NavigationRequest request, SafeRoutePlanner.PlanResult primary, NavigationPlanner.RouteCalculation fallbackCalculation) {
      SafeRoutePlanner.RoutePolicy fallbackPolicy = request.fallbackPolicy();
      if (fallbackPolicy != null
         && !request.primaryPolicy().allowBridge()
         && !primary.hasRoute()
         && primary.status() != SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY) {
         SafeRoutePlanner.PlanResult fallback = fallbackCalculation.calculate(fallbackPolicy);
         return RouteExecutionRules.shouldAdoptFallback(primary, fallback)
            ? new NavigationPlan(request, primary, fallback, fallback, fallbackPolicy, NavigationPlan.Selection.FALLBACK)
            : new NavigationPlan(request, primary, fallback, primary, request.primaryPolicy(), NavigationPlan.Selection.PRIMARY);
      } else {
         return new NavigationPlan(request, primary, null, primary, request.primaryPolicy(), NavigationPlan.Selection.PRIMARY);
      }
   }

   @FunctionalInterface
   private interface RouteCalculation {
      SafeRoutePlanner.PlanResult calculate(SafeRoutePlanner.RoutePolicy var1);
   }
}
