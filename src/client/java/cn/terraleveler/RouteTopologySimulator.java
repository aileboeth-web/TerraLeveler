package cn.terraleveler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Proves a temporary cleanup route remains traversable after its own world edits. */
final class RouteTopologySimulator {
   enum Status {
      VALID,
      DISCONTINUOUS,
      PLACEMENT_OCCUPIES_RETURN_NODE,
      PLACEMENT_CHANGES_RETURN_PATH,
      FORWARD_INVALID,
      RETURN_INVALID
   }

   record Result(
      Status status,
      BlockPos conflict,
      SafeRoutePlanner.StepStatus stepStatus,
      SafeRoutePlanner.CellKind conflictCell,
      Set<BlockPos> conflicts
   ) {
      Result {
         conflicts = conflicts == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(conflicts));
      }

      boolean valid() {
         return this.status == Status.VALID;
      }
   }

   private RouteTopologySimulator() {
   }

   static Result validate(
      SafeRoutePlanner.RouteEnvironment world,
      WorkArea area,
      BlockPos anchor,
      List<SafeRoutePlanner.RouteStep> committed,
      PathExecutionPlan candidate,
      SafeRoutePlanner.RoutePolicy returnPolicy
   ) {
      return validate(world, area, anchor, committed, candidate, returnPolicy, false);
   }

   static Result validate(
      SafeRoutePlanner.RouteEnvironment world,
      WorkArea area,
      BlockPos anchor,
      List<SafeRoutePlanner.RouteStep> committed,
      PathExecutionPlan candidate,
      SafeRoutePlanner.RoutePolicy returnPolicy,
      boolean taskRemovesBlock
   ) {
      if (world == null || area == null || anchor == null || candidate == null) {
         return invalid(Status.DISCONTINUOUS, null, null, null);
      }

      List<SafeRoutePlanner.RouteStep> full = new ArrayList<>(committed);
      full.addAll(candidate.steps());
      if (candidate.steps().isEmpty() || !continuous(anchor, full)) {
         return invalid(Status.DISCONTINUOUS, null, null, null);
      }

      Set<BlockPos> occupiedReturnCells = returnCells(anchor, full);
      Map<BlockPos, SafeRoutePlanner.CellKind> overlay = new HashMap<>();
      Set<BlockPos> candidatePlacements = new LinkedHashSet<>();
      Set<BlockPos> occupiedPlacementConflicts = new LinkedHashSet<>();
      SafeRoutePlanner.RouteEnvironment simulated = position -> overlay.getOrDefault(position, world.cell(position));

      for (SafeRoutePlanner.RouteStep step : candidate.steps()) {
         SafeRoutePlanner.StepValidation before = SafeRoutePlanner.validateStep(simulated, step, area, candidate.policy());
         if (before.requiresBridgePlacement()) {
            BlockPos placement = step.position().below().immutable();
            if (occupiedReturnCells.contains(placement)) {
               occupiedPlacementConflicts.add(placement);
            }
            overlay.put(placement, SafeRoutePlanner.CellKind.SOLID);
            candidatePlacements.add(placement);
            SafeRoutePlanner.StepValidation after = SafeRoutePlanner.validateStep(simulated, step, area, candidate.policy());
            if (!after.canMoveNow()) {
               if (!occupiedPlacementConflicts.isEmpty()) {
                  return occupiedPlacementConflicts(occupiedPlacementConflicts, simulated);
               }
               return invalid(Status.FORWARD_INVALID, step.position(), after.status(), simulated.cell(step.position()));
            }
         } else if (!before.canMoveNow()) {
            if (!occupiedPlacementConflicts.isEmpty()) {
               return occupiedPlacementConflicts(occupiedPlacementConflicts, simulated);
            }
            return invalid(Status.FORWARD_INVALID, step.position(), before.status(), simulated.cell(step.position()));
         }
      }

      if (!occupiedPlacementConflicts.isEmpty()) {
         return occupiedPlacementConflicts(occupiedPlacementConflicts, simulated);
      }

      BlockPos task = candidate.goal().taskKey();
      if (taskRemovesBlock) {
         overlay.put(task, SafeRoutePlanner.CellKind.OPEN);
      }

      return validateReturn(
         world,
         area,
         anchor,
         full,
         task,
         returnPolicy,
         taskRemovesBlock,
         overlay,
         candidatePlacements,
         simulated
      );
   }

   static Result validateRemoval(
      SafeRoutePlanner.RouteEnvironment world,
      WorkArea area,
      BlockPos anchor,
      List<SafeRoutePlanner.RouteStep> committed,
      BlockPos task,
      SafeRoutePlanner.RoutePolicy returnPolicy
   ) {
      if (world == null
         || area == null
         || anchor == null
         || committed == null
         || committed.isEmpty()
         || task == null
         || returnPolicy == null
         || !continuous(anchor, committed)) {
         return invalid(Status.DISCONTINUOUS, null, null, null);
      }

      Map<BlockPos, SafeRoutePlanner.CellKind> overlay = new HashMap<>();
      overlay.put(task, SafeRoutePlanner.CellKind.OPEN);
      SafeRoutePlanner.RouteEnvironment simulated = position -> overlay.getOrDefault(position, world.cell(position));
      return validateReturn(
         world,
         area,
         anchor,
         List.copyOf(committed),
         task,
         returnPolicy,
         true,
         overlay,
         Set.of(),
         simulated
      );
   }

   private static Result validateReturn(
      SafeRoutePlanner.RouteEnvironment world,
      WorkArea area,
      BlockPos anchor,
      List<SafeRoutePlanner.RouteStep> full,
      BlockPos task,
      SafeRoutePlanner.RoutePolicy returnPolicy,
      boolean taskRemovesBlock,
      Map<BlockPos, SafeRoutePlanner.CellKind> overlay,
      Set<BlockPos> candidatePlacements,
      SafeRoutePlanner.RouteEnvironment simulated
   ) {
      BlockPos endpoint = full.getLast().position();
      boolean lowersReturnAnchor = taskRemovesBlock && task.equals(anchor.below());
      BlockPos returnAnchor = lowersReturnAnchor ? anchor.below() : anchor;
      if (lowersReturnAnchor && world.cell(returnAnchor.below()) != SafeRoutePlanner.CellKind.SOLID) {
         return invalid(
            Status.RETURN_INVALID,
            returnAnchor,
            SafeRoutePlanner.StepStatus.DESTINATION_MISSING_FLOOR,
            world.cell(returnAnchor.below())
         );
      }

      List<SafeRoutePlanner.RouteStep> reverse = CleanupRouteReversal.reverse(full, endpoint, anchor, returnAnchor);
      if (reverse.isEmpty()) {
         return invalid(Status.RETURN_INVALID, endpoint, null, simulated.cell(endpoint));
      }

      for (SafeRoutePlanner.RouteStep step : reverse) {
         SafeRoutePlanner.StepValidation validation = SafeRoutePlanner.validateStep(simulated, step, area, returnPolicy);
         if (!validation.canMoveNow()) {
            BlockPos blockingPlacement = blockingPlacement(world, overlay, candidatePlacements, step, area, returnPolicy);
            if (blockingPlacement != null) {
               return invalid(
                  Status.PLACEMENT_CHANGES_RETURN_PATH, blockingPlacement, validation.status(), simulated.cell(blockingPlacement)
               );
            }
            return invalid(Status.RETURN_INVALID, step.position(), validation.status(), simulated.cell(step.position()));
         }
      }

      return new Result(Status.VALID, null, null, null, Set.of());
   }

   private static boolean continuous(BlockPos anchor, List<SafeRoutePlanner.RouteStep> steps) {
      BlockPos cursor = anchor;
      for (SafeRoutePlanner.RouteStep step : steps) {
         if (step.origin() == null || !cursor.equals(step.origin())) {
            return false;
         }
         cursor = step.position();
      }
      return true;
   }

   private static BlockPos blockingPlacement(
      SafeRoutePlanner.RouteEnvironment world,
      Map<BlockPos, SafeRoutePlanner.CellKind> overlay,
      Set<BlockPos> candidatePlacements,
      SafeRoutePlanner.RouteStep returnStep,
      WorkArea area,
      SafeRoutePlanner.RoutePolicy returnPolicy
   ) {
      for (BlockPos placement : candidatePlacements) {
         SafeRoutePlanner.RouteEnvironment withoutPlacement = position -> position.equals(placement)
            ? world.cell(position)
            : overlay.getOrDefault(position, world.cell(position));
         if (SafeRoutePlanner.validateStep(withoutPlacement, returnStep, area, returnPolicy).canMoveNow()) {
            return placement;
         }
      }
      return null;
   }

   private static Set<BlockPos> returnCells(BlockPos anchor, List<SafeRoutePlanner.RouteStep> steps) {
      Set<BlockPos> cells = new HashSet<>();
      cells.add(anchor.immutable());
      cells.add(anchor.above().immutable());
      for (SafeRoutePlanner.RouteStep step : steps) {
         cells.add(step.position().immutable());
         cells.add(step.position().above().immutable());
      }
      return cells;
   }

   private static Result invalid(
      Status status, BlockPos conflict, SafeRoutePlanner.StepStatus stepStatus, SafeRoutePlanner.CellKind conflictCell
   ) {
      return new Result(status, conflict, stepStatus, conflictCell, conflict == null ? Set.of() : Set.of(conflict));
   }

   private static Result occupiedPlacementConflicts(
      Set<BlockPos> conflicts, SafeRoutePlanner.RouteEnvironment simulated
   ) {
      BlockPos first = conflicts.iterator().next();
      return new Result(
         Status.PLACEMENT_OCCUPIES_RETURN_NODE, first, null, simulated.cell(first), conflicts
      );
   }
}
