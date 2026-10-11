package cn.terraleveler;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class ExcavationCompletionAudit {
    record Result(List<BlockPos> residualTargets, BlockPos unloadedTarget) {
        Result {
            residualTargets = List.copyOf(residualTargets);
            unloadedTarget = unloadedTarget == null ? null : unloadedTarget.immutable();
        }

        boolean ready() {
            return unloadedTarget == null;
        }
    }

    private ExcavationCompletionAudit() { }

    static Result scan(List<BlockPos> plannedTargets,
                       Function<BlockPos, ExcavationPlanner.Operation> operationAt) {
        List<BlockPos> residual = new ArrayList<>();
        for (BlockPos position : plannedTargets) {
            ExcavationPlanner.Operation operation = operationAt.apply(position);
            if (operation == ExcavationPlanner.Operation.WAIT_FOR_CHUNK) {
                return new Result(residual, position);
            }
            if (operation == ExcavationPlanner.Operation.BREAK
                    || operation == ExcavationPlanner.Operation.CONTAIN_FLUID) {
                residual.add(position.immutable());
            }
        }
        return new Result(residual, null);
    }
}
