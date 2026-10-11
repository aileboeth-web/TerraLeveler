package cn.terraleveler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public final class SafeRoutePlanner {
   private static final Direction[] HORIZONTAL = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
   private static final int MAX_VISITED = 40000;
   private static final int MAX_PARTIAL_STEPS = 64;
   private static final int HORIZONTAL_SEARCH_MARGIN = 24;
   private static final int VERTICAL_SEARCH_MARGIN = 12;
   /**
    * Retry bounds used only after a normal search failed. In the 0.8.71 run the only
    * way back down from a ridge outside the area led 90 blocks along it, beyond the
    * 24-block box, and the job paused twice for 30 seconds.
    */
   static final int EXPANDED_HORIZONTAL_SEARCH_MARGIN = 96;
   static final int EXPANDED_VERTICAL_SEARCH_MARGIN = 40;
   static final int EXPANDED_MAX_VISITED = 160000;

   private SafeRoutePlanner() {
   }

   public static List<SafeRoutePlanner.RouteStep> plan(Minecraft mc, BlockPos requestedStart, List<BlockPos> goals, WorkArea trench, boolean allowBridge) {
      SafeRoutePlanner.RoutePolicy policy = new SafeRoutePlanner.RoutePolicy(allowBridge, SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL);
      SafeRoutePlanner.PlanResult result = findRoute(mc, requestedStart, goals, trench, policy);
      return result.hasRoute() ? result.steps() : null;
   }

   public static SafeRoutePlanner.PlanResult findRoute(
      Minecraft mc, BlockPos requestedStart, List<BlockPos> goals, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      return findRoute(mc, requestedStart, goals, trench, policy, Set.of());
   }

   static SafeRoutePlanner.PlanResult findRoute(
      Minecraft mc,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      return mc.level == null
         ? new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.WORLD_UNAVAILABLE, List.of())
         : findRoute(minecraftEnvironment(mc), requestedStart, goals, trench, policy, MAX_VISITED, forbiddenScaffoldPlacements);
   }

   /** Same search with the expanded bounds and visit limit; for a retry after NO_ROUTE or SEARCH_LIMIT. */
   static SafeRoutePlanner.PlanResult findRouteExpanded(
      Minecraft mc,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      return mc.level == null
         ? new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.WORLD_UNAVAILABLE, List.of())
         : findRoute(minecraftEnvironment(mc), requestedStart, goals, trench, policy, EXPANDED_MAX_VISITED, forbiddenScaffoldPlacements,
            EXPANDED_HORIZONTAL_SEARCH_MARGIN, EXPANDED_VERTICAL_SEARCH_MARGIN);
   }

   static SafeRoutePlanner.PlanResult findRouteExpanded(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      return findRoute(environment, requestedStart, goals, trench, policy, EXPANDED_MAX_VISITED, forbiddenScaffoldPlacements,
         EXPANDED_HORIZONTAL_SEARCH_MARGIN, EXPANDED_VERTICAL_SEARCH_MARGIN);
   }

   /** True for failures that a larger search box or visit budget can change. */
   static boolean expandableFailure(SafeRoutePlanner.PlanStatus status) {
      return status == SafeRoutePlanner.PlanStatus.NO_ROUTE || status == SafeRoutePlanner.PlanStatus.SEARCH_LIMIT;
   }

   static SafeRoutePlanner.PlanResult findRouteWithinVisitLimit(
      Minecraft mc, BlockPos requestedStart, List<BlockPos> goals, WorkArea trench, SafeRoutePlanner.RoutePolicy policy, int maxVisited
   ) {
      return mc.level == null
         ? new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.WORLD_UNAVAILABLE, List.of())
         : findRoute(minecraftEnvironment(mc), requestedStart, goals, trench, policy, maxVisited, Set.of());
   }

   public static SafeRoutePlanner.PlanResult findRoute(
      SafeRoutePlanner.RouteEnvironment environment, BlockPos requestedStart, List<BlockPos> goals, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      return findRoute(environment, requestedStart, goals, trench, policy, MAX_VISITED, Set.of());
   }

   static SafeRoutePlanner.PlanResult findRoute(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      return findRoute(environment, requestedStart, goals, trench, policy, MAX_VISITED, forbiddenScaffoldPlacements);
   }

   static SafeRoutePlanner.PlanResult findRouteWithinVisitLimit(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      int maxVisited
   ) {
      return findRoute(environment, requestedStart, goals, trench, policy, maxVisited, Set.of());
   }

   private static SafeRoutePlanner.PlanResult findRoute(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      int maxVisited,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      return findRoute(environment, requestedStart, goals, trench, policy, maxVisited, forbiddenScaffoldPlacements,
         HORIZONTAL_SEARCH_MARGIN, VERTICAL_SEARCH_MARGIN);
   }

   private static SafeRoutePlanner.PlanResult findRoute(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos requestedStart,
      List<BlockPos> goals,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      int maxVisited,
      Set<BlockPos> forbiddenScaffoldPlacements,
      int horizontalMargin,
      int verticalMargin
   ) {
      Objects.requireNonNull(environment, "environment");
      Objects.requireNonNull(requestedStart, "requestedStart");
      Objects.requireNonNull(goals, "goals");
      Objects.requireNonNull(trench, "trench");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(forbiddenScaffoldPlacements, "forbiddenScaffoldPlacements");
      if (maxVisited <= 0) {
         throw new IllegalArgumentException("maxVisited must be positive");
      }

      if (goals.isEmpty()) {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.NO_GOALS, List.of());
      }

      // One search is an instantaneous snapshot: each cell is classified once
      // instead of once per neighbouring node and transition.
      environment = memoized(environment);
      BlockPos start = normalizeStart(environment, requestedStart);
      if (start == null) {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.START_REQUIRES_RECOVERY, List.of());
      }

      if (trench.isStrictInteriorXZ(start.getX(), start.getZ()) && policy.interiorPolicy() == SafeRoutePlanner.InteriorPolicy.FORBID) {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.START_INTERIOR_FORBIDDEN, List.of());
      }

      Set<BlockPos> goalSet = new HashSet<>();

      for (BlockPos goal : goals) {
         SafeRoutePlanner.StandStatus status = inspectStandPosition(environment, goal);
         if (status == SafeRoutePlanner.StandStatus.SAFE || status == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR && bridgeAllowedAt(policy, trench, goal)) {
            goalSet.add(goal.immutable());
         }
      }

      if (goalSet.isEmpty()) {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.NO_SAFE_GOAL, List.of());
      }

      if (goalSet.contains(start)) {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.ALREADY_AT_GOAL, List.of());
      }

      int minX = start.getX();
      int maxX = start.getX();
      int minY = start.getY();
      int maxY = start.getY();
      int minZ = start.getZ();
      int maxZ = start.getZ();

      for (BlockPos goal : goalSet) {
         minX = Math.min(minX, goal.getX());
         maxX = Math.max(maxX, goal.getX());
         minY = Math.min(minY, goal.getY());
         maxY = Math.max(maxY, goal.getY());
         minZ = Math.min(minZ, goal.getZ());
         maxZ = Math.max(maxZ, goal.getZ());
      }

      minX -= horizontalMargin;
      maxX += horizontalMargin;
      minY -= verticalMargin;
      maxY += verticalMargin;
      minZ -= horizontalMargin;
      maxZ += horizontalMargin;
      int boundMinX = minX;
      int boundMaxX = maxX;
      int boundMinY = minY;
      int boundMaxY = maxY;
      int boundMinZ = minZ;
      int boundMaxZ = maxZ;
      SafeRoutePlanner.SearchNode startNode = new SafeRoutePlanner.SearchNode(start, null);
      PriorityQueue<SafeRoutePlanner.QueueNode> open = new PriorityQueue<>(Comparator.comparingDouble(SafeRoutePlanner.QueueNode::score));
      Map<SafeRoutePlanner.SearchNode, Double> cost = new HashMap<>();
      Map<SafeRoutePlanner.SearchNode, SafeRoutePlanner.SearchNode> previous = new HashMap<>();
      Map<SafeRoutePlanner.SearchNode, SafeRoutePlanner.StepType> previousStep = new HashMap<>();
      cost.put(startNode, 0.0);
      GoalHeuristic goalHeuristic = new GoalHeuristic(goalSet);
      double startHeuristic = goalHeuristic.at(start);
      open.add(new SafeRoutePlanner.QueueNode(startNode, startHeuristic, 0.0));
      SafeRoutePlanner.SearchNode bestProgressNode = startNode;
      double bestProgressHeuristic = startHeuristic;
      double bestProgressCost = 0.0;
      int visited = 0;

      while (!open.isEmpty() && visited < maxVisited) {
         SafeRoutePlanner.QueueNode queued = open.remove();
         SafeRoutePlanner.SearchNode current = queued.node();
         double currentCost = cost.getOrDefault(current, Double.POSITIVE_INFINITY);
         if (!(queued.pathCost() > currentCost + 1.0E-6)) {
            visited++;
            double currentHeuristic = goalHeuristic.at(current.pos());
            if (betterProgress(currentHeuristic, currentCost, bestProgressHeuristic, bestProgressCost)) {
               bestProgressNode = current;
               bestProgressHeuristic = currentHeuristic;
               bestProgressCost = currentCost;
            }

            if (goalSet.contains(current.pos())
               && (
                  policy.interiorPolicy() == SafeRoutePlanner.InteriorPolicy.ALLOW
                     || !trench.isStrictInteriorXZ(current.pos().getX(), current.pos().getZ())
                     || current.interiorApproachGoal() != null
               )) {
               return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.FOUND, reconstruct(startNode, current, previous, previousStep));
            }

            for (SafeRoutePlanner.Transition transition : transitions(environment, current.pos(), trench, policy, forbiddenScaffoldPlacements)) {
               BlockPos nextPos = transition.pos();
               if (nextPos.getY() >= policy.minimumFeetY()
                  && nextPos.getX() >= boundMinX
                  && nextPos.getX() <= boundMaxX
                  && nextPos.getY() >= boundMinY
                  && nextPos.getY() <= boundMaxY
                  && nextPos.getZ() >= boundMinZ
                  && nextPos.getZ() <= boundMaxZ) {
                  for (SafeRoutePlanner.SearchNode next : regionTransitions(current, nextPos, goalSet, trench, policy.interiorPolicy())) {
                     double candidate = currentCost + transition.cost();
                     if (!(candidate + 1.0E-6 >= cost.getOrDefault(next, Double.POSITIVE_INFINITY))) {
                        cost.put(next, candidate);
                        previous.put(next, current);
                        previousStep.put(next, transition.type());
                        double nextHeuristic = goalHeuristic.at(next.pos());
                        if (betterProgress(nextHeuristic, candidate, bestProgressHeuristic, bestProgressCost)) {
                           bestProgressNode = next;
                           bestProgressHeuristic = nextHeuristic;
                           bestProgressCost = candidate;
                        }

                        open.add(new SafeRoutePlanner.QueueNode(next, candidate + nextHeuristic, candidate));
                     }
                  }
               }
            }
         }
      }

      if (!open.isEmpty()) {
         SafeRoutePlanner.SearchNode partialEnd = bestProgressNode;
         if (partialEnd.equals(startNode)) {
            partialEnd = open.stream()
               .filter(candidatex -> candidatex.pathCost() <= cost.getOrDefault(candidatex.node(), Double.POSITIVE_INFINITY) + 1.0E-6)
               .min(Comparator.comparingDouble(SafeRoutePlanner.QueueNode::score).thenComparingDouble(SafeRoutePlanner.QueueNode::pathCost))
               .map(SafeRoutePlanner.QueueNode::node)
               .orElse(startNode);
         }

         List<SafeRoutePlanner.RouteStep> partial = reconstruct(startNode, partialEnd, previous, previousStep);
         if (!partial.isEmpty()) {
            if (partial.size() > 64) {
               partial = List.copyOf(partial.subList(0, 64));
            }

            BlockPos partialDestination = partial.getLast().position();
            return goalHeuristic.at(partialDestination) + 1.0E-6 < startHeuristic
               ? new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.PARTIAL, partial)
               : new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.SEARCH_LIMIT, List.of());
         } else {
            return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.SEARCH_LIMIT, List.of());
         }
      } else {
         return new SafeRoutePlanner.PlanResult(SafeRoutePlanner.PlanStatus.NO_ROUTE, List.of());
      }
   }

   private static boolean betterProgress(double candidateHeuristic, double candidateCost, double bestHeuristic, double bestCost) {
      return candidateHeuristic + 1.0E-6 < bestHeuristic || Math.abs(candidateHeuristic - bestHeuristic) <= 1.0E-6 && candidateCost + 1.0E-6 < bestCost;
   }

   public static boolean canOccupy(Minecraft mc, BlockPos feet) {
      if (mc.level == null) {
         return false;
      }

      SafeRoutePlanner.StandStatus status = inspectStandPosition(minecraftEnvironment(mc), feet);
      return status == SafeRoutePlanner.StandStatus.SAFE || status == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR;
   }

   public static boolean hasFloor(Minecraft mc, BlockPos feet) {
      if (mc.level == null) {
         return false;
      }
      SafeRoutePlanner.CellKind floor = minecraftEnvironment(mc).cell(feet.below());
      // Decaying leaves still carry the player right now; they are only excluded from planned routes.
      return floor == SafeRoutePlanner.CellKind.SOLID || floor == SafeRoutePlanner.CellKind.UNSTABLE;
   }

   public static SafeRoutePlanner.StandStatus inspectStandPosition(SafeRoutePlanner.RouteEnvironment environment, BlockPos feet) {
      SafeRoutePlanner.CellKind feetCell = environment.cell(feet);
      SafeRoutePlanner.CellKind headCell = environment.cell(feet.above());
      if (feetCell == SafeRoutePlanner.CellKind.UNLOADED || headCell == SafeRoutePlanner.CellKind.UNLOADED) {
         return SafeRoutePlanner.StandStatus.UNLOADED;
      }

      if (feetCell == SafeRoutePlanner.CellKind.FLUID || headCell == SafeRoutePlanner.CellKind.FLUID) {
         return SafeRoutePlanner.StandStatus.FLUID;
      }

      // Fire, berry bushes, cobwebs and powder snow have no collision but hurt or trap the body.
      if (feetCell == SafeRoutePlanner.CellKind.HAZARD || headCell == SafeRoutePlanner.CellKind.HAZARD) {
         return SafeRoutePlanner.StandStatus.BLOCKED;
      }

      if (!blocksBody(feetCell) && !blocksBody(headCell)) {
         SafeRoutePlanner.CellKind floorCell = environment.cell(feet.below());
         if (floorCell == SafeRoutePlanner.CellKind.UNLOADED) {
            return SafeRoutePlanner.StandStatus.UNLOADED;
         } else if (floorCell == SafeRoutePlanner.CellKind.FLUID) {
            return SafeRoutePlanner.StandStatus.FLUID;
         } else if (floorCell == SafeRoutePlanner.CellKind.UNSTABLE) {
            // Leaves cut off from their logs decay within moments: never plan a step onto them.
            return SafeRoutePlanner.StandStatus.BLOCKED;
         } else if (floorCell == SafeRoutePlanner.CellKind.HAZARD) {
            // Magma, campfires, cactus or dripstone: never a stand cell and never a bridge slot.
            return SafeRoutePlanner.StandStatus.BLOCKED;
         } else {
            return floorCell == SafeRoutePlanner.CellKind.SOLID ? SafeRoutePlanner.StandStatus.SAFE : SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR;
         }
      } else {
         return SafeRoutePlanner.StandStatus.BLOCKED;
      }
   }

   public static SafeRoutePlanner.StepValidation validateStep(
      Minecraft mc, BlockPos from, SafeRoutePlanner.RouteStep step, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      return mc.level == null
         ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.DESTINATION_UNLOADED, SafeRoutePlanner.StandStatus.UNLOADED)
         : validateStep(minecraftEnvironment(mc), from, step, trench, policy);
   }

   public static SafeRoutePlanner.StepValidation validateStep(
      Minecraft mc, SafeRoutePlanner.RouteStep step, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      return mc.level == null
         ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.DESTINATION_UNLOADED, SafeRoutePlanner.StandStatus.UNLOADED)
         : validateStep(minecraftEnvironment(mc), step, trench, policy);
   }

   public static SafeRoutePlanner.StepValidation validateStep(
      SafeRoutePlanner.RouteEnvironment environment, SafeRoutePlanner.RouteStep step, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      Objects.requireNonNull(environment, "environment");
      Objects.requireNonNull(step, "step");
      if (step.origin() == null) {
         SafeRoutePlanner.StandStatus destination = inspectStandPosition(environment, step.position());
         return new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.INVALID_GEOMETRY, destination);
      } else {
         return validateStep(environment, step.origin(), step, trench, policy);
      }
   }

   public static SafeRoutePlanner.StepValidation validateStep(
      SafeRoutePlanner.RouteEnvironment environment, BlockPos from, SafeRoutePlanner.RouteStep step, WorkArea trench, SafeRoutePlanner.RoutePolicy policy
   ) {
      Objects.requireNonNull(environment, "environment");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(step, "step");
      Objects.requireNonNull(trench, "trench");
      Objects.requireNonNull(policy, "policy");
      SafeRoutePlanner.StandStatus destination = inspectStandPosition(environment, step.position());
      if (!standableOrigin(environment, from)) {
         return new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.SOURCE_UNSAFE, destination);
      }

      int dx = step.position().getX() - from.getX();
      int dy = step.position().getY() - from.getY();
      int dz = step.position().getZ() - from.getZ();
      if (Math.abs(dx) + Math.abs(dz) != 1 || !validVerticalRelation(step.type(), dy, policy.maximumDrop())) {
         return new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.INVALID_GEOMETRY, destination);
      }

      if (!regionTransitionAllowed(from, step, trench, policy.interiorPolicy())) {
         return new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.INTERIOR_FORBIDDEN, destination);
      }

      return switch (step.type()) {
         case WALK -> requireSafeDestination(destination);
         case STEP_UP -> environment.cell(from.above(2)) != SafeRoutePlanner.CellKind.OPEN
            ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.SOURCE_CLEARANCE_BLOCKED, destination)
            : requireSafeDestination(destination);
         case STEP_DOWN -> {
            Direction direction = horizontalDirection(dx, dz);
            BlockPos landing = firstSafeDrop(environment, from.relative(direction), policy.maximumDrop());
            yield landing == null && destination != SafeRoutePlanner.StandStatus.SAFE
               ? requireSafeDestination(destination)
               : (
                  landing != null && landing.equals(step.position())
                     ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.VALID, destination)
                     : new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.DROP_PATH_CHANGED, destination)
               );
         }
         case WATER_BUCKET_FALL -> {
            Direction direction = horizontalDirection(dx, dz);
            BlockPos landing = firstSafeDrop(environment, from.relative(direction), policy.maximumDrop());
            yield landing != null && landing.equals(step.position())
               ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.VALID, destination)
               : new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.DROP_PATH_CHANGED, destination);
         }
         case BRIDGE -> !policy.allowBridge()
            ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_FORBIDDEN, destination)
            : (
               !bridgeAllowedAt(policy, trench, step.position())
                  ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_OFF_BOUNDARY, destination)
                  : (
                     destination == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR
                        ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_REQUIRED, destination)
                        : requireSafeDestination(destination)
                  )
            );
         case SCAFFOLD_UP -> {
            if (environment.cell(from.above(2)) != SafeRoutePlanner.CellKind.OPEN) {
               yield new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.SOURCE_CLEARANCE_BLOCKED, destination);
            } else if (!policy.allowBridge()) {
               yield new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_FORBIDDEN, destination);
            } else if (!bridgeAllowedAt(policy, trench, step.position())) {
               yield new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_OFF_BOUNDARY, destination);
            } else if (destination == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR) {
               BlockPos scaffold = step.position().below();
               yield !hasStaticPlacementSupport(environment, scaffold)
                  ? new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.SCAFFOLD_SUPPORT_MISSING, destination)
                  : new SafeRoutePlanner.StepValidation(SafeRoutePlanner.StepStatus.BRIDGE_REQUIRED, destination);
            } else {
               yield requireSafeDestination(destination);
            }
         }
      };
   }

   private static SafeRoutePlanner.RouteEnvironment minecraftEnvironment(Minecraft mc) {
      return position -> {
         if (mc.level == null || !mc.level.hasChunkAt(position)) {
            return SafeRoutePlanner.CellKind.UNLOADED;
         }

         if (!mc.level.getFluidState(position).isEmpty()) {
            return SafeRoutePlanner.CellKind.FLUID;
         }

         BlockState state = mc.level.getBlockState(position);
         if (HazardousBlocks.isHazard(state)) {
            return SafeRoutePlanner.CellKind.HAZARD;
         }
         if (HazardousBlocks.isDecayingLeaves(state)) {
            return SafeRoutePlanner.CellKind.UNSTABLE;
         }
         return state.getCollisionShape(mc.level, position).isEmpty() ? SafeRoutePlanner.CellKind.OPEN : SafeRoutePlanner.CellKind.SOLID;
      };
   }

   static SafeRoutePlanner.RouteEnvironment routeEnvironment(Minecraft mc) {
      return minecraftEnvironment(mc);
   }

   private static BlockPos normalizeStart(SafeRoutePlanner.RouteEnvironment environment, BlockPos requested) {
      return standableOrigin(environment, requested) ? requested.immutable() : null;
   }

   /** A position the player may leave from: safe, or already standing on decaying leaves. */
   static boolean standableOrigin(SafeRoutePlanner.RouteEnvironment environment, BlockPos feet) {
      SafeRoutePlanner.StandStatus status = inspectStandPosition(environment, feet);
      if (status == SafeRoutePlanner.StandStatus.SAFE) {
         return true;
      }
      return status == SafeRoutePlanner.StandStatus.BLOCKED
         && environment.cell(feet) == SafeRoutePlanner.CellKind.OPEN
         && environment.cell(feet.above()) == SafeRoutePlanner.CellKind.OPEN
         && environment.cell(feet.below()) == SafeRoutePlanner.CellKind.UNSTABLE;
   }

   private static boolean blocksBody(SafeRoutePlanner.CellKind cell) {
      return cell == SafeRoutePlanner.CellKind.SOLID || cell == SafeRoutePlanner.CellKind.UNSTABLE;
   }

   private static List<SafeRoutePlanner.Transition> transitions(
      SafeRoutePlanner.RouteEnvironment environment,
      BlockPos current,
      WorkArea trench,
      SafeRoutePlanner.RoutePolicy policy,
      Set<BlockPos> forbiddenScaffoldPlacements
   ) {
      List<SafeRoutePlanner.Transition> result = new ArrayList<>(12);

      for (Direction direction : HORIZONTAL) {
         BlockPos flat = current.relative(direction);
         SafeRoutePlanner.StandStatus flatStatus = inspectStandPosition(environment, flat);
         if (flatStatus == SafeRoutePlanner.StandStatus.SAFE) {
            result.add(new SafeRoutePlanner.Transition(flat.immutable(), SafeRoutePlanner.StepType.WALK, 1.0));
         } else if (flatStatus == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR
            && bridgeAllowedAt(policy, trench, flat)
            && !forbiddenScaffoldPlacements.contains(flat.below())) {
            result.add(new SafeRoutePlanner.Transition(flat.immutable(), SafeRoutePlanner.StepType.BRIDGE, 7.0));
         }

         BlockPos up = flat.above();
         SafeRoutePlanner.StandStatus upStatus = inspectStandPosition(environment, up);
         if (upStatus == SafeRoutePlanner.StandStatus.SAFE && environment.cell(current.above(2)) == SafeRoutePlanner.CellKind.OPEN) {
            result.add(new SafeRoutePlanner.Transition(up.immutable(), SafeRoutePlanner.StepType.STEP_UP, 1.8));
         } else if (upStatus == SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR
            && policy.allowBridge()
            && environment.cell(current.above(2)) == SafeRoutePlanner.CellKind.OPEN
            && bridgeAllowedAt(policy, trench, up)
            && hasStaticPlacementSupport(environment, up.below())
            && !forbiddenScaffoldPlacements.contains(up.below())) {
            result.add(new SafeRoutePlanner.Transition(up.immutable(), SafeRoutePlanner.StepType.SCAFFOLD_UP, 8.5));
         }

         BlockPos down = firstSafeDrop(environment, flat, policy.maximumDrop());
         if (down != null) {
            int drop = current.getY() - down.getY();
            result.add(new SafeRoutePlanner.Transition(down, SafeRoutePlanner.StepType.STEP_DOWN, 1.0 + drop * 0.4));
         }
      }

      return result;
   }

   private static boolean bridgeAllowedAt(SafeRoutePlanner.RoutePolicy policy, WorkArea area, BlockPos position) {
      return policy.allowBridge()
         && isScaffoldLane(area, position.getX(), position.getZ());
   }

   private static BlockPos firstSafeDrop(SafeRoutePlanner.RouteEnvironment environment, BlockPos flat, int maxDrop) {
      if (inspectStandPosition(environment, flat) != SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR) {
         return null;
      }

      for (int drop = 1; drop <= maxDrop; drop++) {
         BlockPos candidate = flat.below(drop);
         SafeRoutePlanner.StandStatus status = inspectStandPosition(environment, candidate);
         if (status == SafeRoutePlanner.StandStatus.SAFE) {
            return candidate.immutable();
         }

         if (status != SafeRoutePlanner.StandStatus.OPEN_NO_FLOOR) {
            return null;
         }
      }

      return null;
   }

   private static List<SafeRoutePlanner.SearchNode> regionTransitions(
      SafeRoutePlanner.SearchNode current, BlockPos next, Set<BlockPos> goals, WorkArea trench, SafeRoutePlanner.InteriorPolicy policy
   ) {
      if (policy == SafeRoutePlanner.InteriorPolicy.ALLOW) {
         return List.of(new SafeRoutePlanner.SearchNode(next, null));
      }

      boolean nextInterior = trench.isStrictInteriorXZ(next.getX(), next.getZ());
      boolean currentInterior = trench.isStrictInteriorXZ(current.pos().getX(), current.pos().getZ());
      if (current.interiorApproachGoal() != null) {
         return List.of();
      }

      if (!nextInterior) {
         return List.of(new SafeRoutePlanner.SearchNode(next, null));
      }

      if (currentInterior && policy != SafeRoutePlanner.InteriorPolicy.FORBID) {
         return interiorDepth(next, trench) < interiorDepth(current.pos(), trench) ? List.of(new SafeRoutePlanner.SearchNode(next, null)) : List.of();
      }

      if (policy != SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL) {
         return List.of();
      }

      List<SafeRoutePlanner.SearchNode> entries = new ArrayList<>();

      for (BlockPos goal : goals) {
         if (trench.isStrictInteriorXZ(goal.getX(), goal.getZ()) && next.equals(goal)) {
            entries.add(new SafeRoutePlanner.SearchNode(next, goal));
         }
      }

      return entries;
   }

   private static boolean regionTransitionAllowed(BlockPos from, SafeRoutePlanner.RouteStep step, WorkArea trench, SafeRoutePlanner.InteriorPolicy policy) {
      if (policy == SafeRoutePlanner.InteriorPolicy.ALLOW) {
         return true;
      } else {
         BlockPos next = step.position();
         boolean nextInterior = trench.isStrictInteriorXZ(next.getX(), next.getZ());
         boolean currentInterior = trench.isStrictInteriorXZ(from.getX(), from.getZ());
         BlockPos approachGoal = step.interiorApproachGoal();
         if (approachGoal != null) {
            return policy == SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL
               && nextInterior
               && trench.isStrictInteriorXZ(approachGoal.getX(), approachGoal.getZ())
               && !currentInterior
               && next.equals(approachGoal);
         } else {
            return !nextInterior
               ? true
               : currentInterior && policy != SafeRoutePlanner.InteriorPolicy.FORBID && interiorDepth(next, trench) < interiorDepth(from, trench);
         }
      }
   }

   private static int interiorDepth(BlockPos position, WorkArea trench) {
      return Math.min(
         Math.min(position.getX() - trench.minX(), trench.maxX() - position.getX()), Math.min(position.getZ() - trench.minZ(), trench.maxZ() - position.getZ())
      );
   }

   private static boolean closerHorizontally(BlockPos next, BlockPos current, BlockPos goal) {
      return horizontalDistance(next, goal) < horizontalDistance(current, goal);
   }

   static boolean isScaffoldLane(WorkArea trench, int x, int z) {
      return trench.permitsScaffoldAt(x, z);
   }

   private static boolean hasStaticPlacementSupport(SafeRoutePlanner.RouteEnvironment environment, BlockPos target) {
      for (Direction direction : Direction.values()) {
         if (environment.cell(target.relative(direction)) == SafeRoutePlanner.CellKind.SOLID) {
            return true;
         }
      }

      return false;
   }

   private static int horizontalDistance(BlockPos first, BlockPos second) {
      return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
   }

   private static boolean validVerticalRelation(SafeRoutePlanner.StepType type, int dy, int maximumDrop) {
      return switch (type) {
         case WALK, BRIDGE -> dy == 0;
         case STEP_UP, SCAFFOLD_UP -> dy == 1;
         case STEP_DOWN, WATER_BUCKET_FALL -> dy >= -maximumDrop && dy <= -1;
      };
   }

   private static Direction horizontalDirection(int dx, int dz) {
      if (dx == 1) {
         return Direction.EAST;
      } else if (dx == -1) {
         return Direction.WEST;
      } else {
         return dz == 1 ? Direction.SOUTH : Direction.NORTH;
      }
   }

   private static SafeRoutePlanner.StepValidation requireSafeDestination(SafeRoutePlanner.StandStatus destination) {
      return new SafeRoutePlanner.StepValidation(switch (destination) {
         case SAFE -> SafeRoutePlanner.StepStatus.VALID;
         case OPEN_NO_FLOOR -> SafeRoutePlanner.StepStatus.DESTINATION_MISSING_FLOOR;
         case BLOCKED -> SafeRoutePlanner.StepStatus.DESTINATION_BLOCKED;
         case FLUID -> SafeRoutePlanner.StepStatus.DESTINATION_FLUID;
         case UNLOADED -> SafeRoutePlanner.StepStatus.DESTINATION_UNLOADED;
      }, destination);
   }

   /** Minimum Manhattan distance to any goal, computed once per searched position. */
   private static final class GoalHeuristic {
      private final int[] xs;
      private final int[] ys;
      private final int[] zs;
      private final Map<Long, Double> cache = new HashMap<>();

      private GoalHeuristic(Set<BlockPos> goals) {
         this.xs = new int[goals.size()];
         this.ys = new int[goals.size()];
         this.zs = new int[goals.size()];
         int index = 0;
         for (BlockPos goal : goals) {
            this.xs[index] = goal.getX();
            this.ys[index] = goal.getY();
            this.zs[index] = goal.getZ();
            index++;
         }
      }

      private double at(BlockPos pos) {
         long key = pos.asLong();
         Double cached = this.cache.get(key);
         if (cached != null) {
            return cached;
         }
         int best = Integer.MAX_VALUE;
         for (int index = 0; index < this.xs.length && best > 0; index++) {
            int value = Math.abs(pos.getX() - this.xs[index]) + Math.abs(pos.getY() - this.ys[index]) + Math.abs(pos.getZ() - this.zs[index]);
            if (value < best) {
               best = value;
            }
         }
         double result = best == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : best;
         this.cache.put(key, result);
         return result;
      }
   }

   /** Caches cell classification for the duration of a single search. */
   private static SafeRoutePlanner.RouteEnvironment memoized(SafeRoutePlanner.RouteEnvironment source) {
      Map<Long, SafeRoutePlanner.CellKind> cells = new HashMap<>();
      return position -> {
         long key = position.asLong();
         SafeRoutePlanner.CellKind cached = cells.get(key);
         if (cached == null) {
            cached = source.cell(position);
            cells.put(key, cached);
         }
         return cached;
      };
   }

   private static List<SafeRoutePlanner.RouteStep> reconstruct(
      SafeRoutePlanner.SearchNode start,
      SafeRoutePlanner.SearchNode end,
      Map<SafeRoutePlanner.SearchNode, SafeRoutePlanner.SearchNode> previous,
      Map<SafeRoutePlanner.SearchNode, SafeRoutePlanner.StepType> previousStep
   ) {
      List<SafeRoutePlanner.RouteStep> reversed = new ArrayList<>();
      SafeRoutePlanner.SearchNode cursor = end;

      while (!cursor.equals(start)) {
         SafeRoutePlanner.SearchNode origin = previous.get(cursor);
         if (origin == null) {
            return List.of();
         }

         reversed.add(new SafeRoutePlanner.RouteStep(origin.pos(), cursor.pos(), previousStep.get(cursor), cursor.interiorApproachGoal()));
         cursor = origin;
      }

      Collections.reverse(reversed);
      return List.copyOf(reversed);
   }

   public enum CellKind {
      OPEN,
      SOLID,
      FLUID,
      UNLOADED,
      /** Damaging or trapping block: never occupied by the body and never stood on. */
      HAZARD,
      /** Collides like a solid block but is about to vanish (decaying leaves): never a floor or placement support. */
      UNSTABLE;
   }

   public enum InteriorPolicy {
      FORBID,
      EXIT_ONLY,
      EXIT_OR_TERMINAL_GOAL,
      ALLOW;
   }

   public record PlanResult(SafeRoutePlanner.PlanStatus status, List<SafeRoutePlanner.RouteStep> steps) {
      public PlanResult {
         Objects.requireNonNull(status, "status");
         steps = List.copyOf(steps);
      }

      public boolean hasRoute() {
         return this.status == SafeRoutePlanner.PlanStatus.FOUND || this.status == SafeRoutePlanner.PlanStatus.ALREADY_AT_GOAL;
      }

      public boolean hasProgressRoute() {
         return this.hasRoute() || this.status == SafeRoutePlanner.PlanStatus.PARTIAL && !this.steps.isEmpty();
      }
   }

   public enum PlanStatus {
      FOUND,
      ALREADY_AT_GOAL,
      WORLD_UNAVAILABLE,
      NO_GOALS,
      NO_SAFE_GOAL,
      START_REQUIRES_RECOVERY,
      START_INTERIOR_FORBIDDEN,
      PARTIAL,
      SEARCH_LIMIT,
      NO_ROUTE;
   }

   private record QueueNode(SafeRoutePlanner.SearchNode node, double score, double pathCost) {
   }

   @FunctionalInterface
   public interface RouteEnvironment {
      SafeRoutePlanner.CellKind cell(BlockPos var1);
   }

   public record RoutePolicy(boolean allowBridge, SafeRoutePlanner.InteriorPolicy interiorPolicy, int minimumFeetY, int maximumDrop) {
      public RoutePolicy {
         Objects.requireNonNull(interiorPolicy, "interiorPolicy");
         if (maximumDrop < 1 || maximumDrop > 64) {
            throw new IllegalArgumentException("maximumDrop must be between 1 and 64");
         }
      }

      public RoutePolicy(boolean allowBridge, SafeRoutePlanner.InteriorPolicy interiorPolicy, int minimumFeetY) {
         this(allowBridge, interiorPolicy, minimumFeetY, 3);
      }

      public RoutePolicy(boolean allowBridge, SafeRoutePlanner.InteriorPolicy interiorPolicy) {
         this(allowBridge, interiorPolicy, Integer.MIN_VALUE, 3);
      }

      public static SafeRoutePlanner.RoutePolicy natural() {
         return new SafeRoutePlanner.RoutePolicy(false, SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL, Integer.MIN_VALUE, 1);
      }

      public static SafeRoutePlanner.RoutePolicy withBoundaryBridges() {
         return new SafeRoutePlanner.RoutePolicy(true, SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL, Integer.MIN_VALUE);
      }

      public static SafeRoutePlanner.RoutePolicy withBoundaryBridgesAtOrAbove(int minimumFeetY) {
         return new SafeRoutePlanner.RoutePolicy(true, SafeRoutePlanner.InteriorPolicy.EXIT_OR_TERMINAL_GOAL, minimumFeetY);
      }

      public static SafeRoutePlanner.RoutePolicy flatteningFloor() {
         return new SafeRoutePlanner.RoutePolicy(false, SafeRoutePlanner.InteriorPolicy.ALLOW, Integer.MIN_VALUE, 1);
      }

      public static SafeRoutePlanner.RoutePolicy flatteningFloorWithMaximumDrop(int maximumDrop) {
         return new SafeRoutePlanner.RoutePolicy(
            false, SafeRoutePlanner.InteriorPolicy.ALLOW, Integer.MIN_VALUE, Math.min(64, Math.max(1, maximumDrop))
         );
      }

      public static SafeRoutePlanner.RoutePolicy flatteningFloorWithBridges() {
         return new SafeRoutePlanner.RoutePolicy(true, SafeRoutePlanner.InteriorPolicy.ALLOW, Integer.MIN_VALUE);
      }

      public static SafeRoutePlanner.RoutePolicy flatteningFloorWithReversibleBridges() {
         return new SafeRoutePlanner.RoutePolicy(true, SafeRoutePlanner.InteriorPolicy.ALLOW, Integer.MIN_VALUE, 1);
      }
   }

   public record RouteStep(BlockPos origin, BlockPos position, SafeRoutePlanner.StepType type, BlockPos interiorApproachGoal) {
      public RouteStep {
         if (origin != null) {
            origin = origin.immutable();
         }

         position = position.immutable();
         Objects.requireNonNull(type, "type");
         if (interiorApproachGoal != null) {
            interiorApproachGoal = interiorApproachGoal.immutable();
         }
      }

      public RouteStep(BlockPos position, SafeRoutePlanner.StepType type) {
         this(null, position, type, null);
      }

      public RouteStep(BlockPos position, SafeRoutePlanner.StepType type, BlockPos interiorApproachGoal) {
         this(null, position, type, interiorApproachGoal);
      }
   }

   private record SearchNode(BlockPos pos, BlockPos interiorApproachGoal) {
   }

   public enum StandStatus {
      SAFE,
      OPEN_NO_FLOOR,
      BLOCKED,
      FLUID,
      UNLOADED;
   }

   public enum StepStatus {
      VALID,
      BRIDGE_REQUIRED,
      SOURCE_UNSAFE,
      INVALID_GEOMETRY,
      SOURCE_CLEARANCE_BLOCKED,
      DESTINATION_UNLOADED,
      DESTINATION_BLOCKED,
      DESTINATION_FLUID,
      DESTINATION_MISSING_FLOOR,
      INTERIOR_FORBIDDEN,
      BRIDGE_FORBIDDEN,
      BRIDGE_OFF_BOUNDARY,
      SCAFFOLD_SUPPORT_MISSING,
      DROP_PATH_CHANGED;
   }

   public enum StepType {
      WALK,
      STEP_UP,
      STEP_DOWN,
      WATER_BUCKET_FALL,
      BRIDGE,
      SCAFFOLD_UP;
   }

   public record StepValidation(SafeRoutePlanner.StepStatus status, SafeRoutePlanner.StandStatus destination) {
      public StepValidation {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(destination, "destination");
      }

      public boolean canMoveNow() {
         return this.status == SafeRoutePlanner.StepStatus.VALID;
      }

      public boolean requiresBridgePlacement() {
         return this.status == SafeRoutePlanner.StepStatus.BRIDGE_REQUIRED;
      }
   }

   private record Transition(BlockPos pos, SafeRoutePlanner.StepType type, double cost) {
   }
}
