package cn.terraleveler;

import java.util.Objects;
import java.util.Optional;

/**
 * Planner output before inventory, scaffold-budget, or execution admission.
 * The controller may reject a selected fallback without changing path search.
 */
public record NavigationPlan(NavigationRequest request,
                             SafeRoutePlanner.PlanResult primary,
                             SafeRoutePlanner.PlanResult fallbackAttempt,
                             SafeRoutePlanner.PlanResult selected,
                             SafeRoutePlanner.RoutePolicy selectedPolicy,
                             Selection selection) {
    public enum Selection { PRIMARY, FALLBACK }

    public NavigationPlan {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(selectedPolicy, "selectedPolicy");
        Objects.requireNonNull(selection, "selection");
        if (selection == Selection.PRIMARY && !selected.equals(primary)) {
            throw new IllegalArgumentException("primary selection must select the primary result");
        }
        if (selection == Selection.FALLBACK) {
            if (fallbackAttempt == null || !selected.equals(fallbackAttempt)) {
                throw new IllegalArgumentException("fallback selection must select the fallback result");
            }
            if (request.fallbackPolicy() == null
                    || !request.fallbackPolicy().equals(selectedPolicy)) {
                throw new IllegalArgumentException("fallback selection must use the requested fallback policy");
            }
        }
    }

    public Optional<SafeRoutePlanner.PlanResult> fallbackResult() {
        return Optional.ofNullable(fallbackAttempt);
    }

    public boolean selectedFallback() {
        return selection == Selection.FALLBACK;
    }
}
