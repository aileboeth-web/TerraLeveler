package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Construction-owned navigation intent. It identifies the task independently
 * from the candidate player poses that can satisfy its movement requirement.
 */
public record NavigationGoal(BlockPos taskKey, List<BlockPos> candidatePoses) {
    public NavigationGoal {
        Objects.requireNonNull(taskKey, "taskKey");
        Objects.requireNonNull(candidatePoses, "candidatePoses");
        taskKey = taskKey.immutable();
        candidatePoses = candidatePoses.stream()
                .map(position -> Objects.requireNonNull(position, "candidate pose").immutable())
                .toList();
    }
}
