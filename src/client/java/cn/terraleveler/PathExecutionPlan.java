package cn.terraleveler;

import java.util.List;
import java.util.Objects;

/**
 * Immutable admission contract between path calculation and tick execution.
 * Resource and safety gates must approve a navigation result before creating it.
 */
public record PathExecutionPlan(NavigationGoal goal,
                                List<SafeRoutePlanner.RouteStep> steps,
                                SafeRoutePlanner.RoutePolicy policy,
                                boolean requiresTaskValidation,
                                boolean requiresContinuation) {
    public PathExecutionPlan {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(policy, "policy");
        steps = List.copyOf(steps);
    }

    public static PathExecutionPlan admit(NavigationGoal goal,
                                          SafeRoutePlanner.PlanResult result,
                                          SafeRoutePlanner.RoutePolicy policy,
                                          boolean requiresTaskValidation) {
        Objects.requireNonNull(result, "result");
        if (!result.hasProgressRoute()) {
            throw new IllegalArgumentException("only an executable route may be admitted");
        }
        return new PathExecutionPlan(goal, result.steps(), policy, requiresTaskValidation,
                result.status() == SafeRoutePlanner.PlanStatus.PARTIAL);
    }
}
