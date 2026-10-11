package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Immutable input to one path calculation, with no player-control side effects. */
public record NavigationRequest(BlockPos start,
                                NavigationGoal goal,
                                WorkArea workArea,
                                SafeRoutePlanner.RoutePolicy primaryPolicy,
                                SafeRoutePlanner.RoutePolicy fallbackPolicy) {
    public NavigationRequest {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(workArea, "workArea");
        Objects.requireNonNull(primaryPolicy, "primaryPolicy");
        start = start.immutable();
    }

}
