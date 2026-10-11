package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class FlattenScaffoldCleanupScheduler {
   record Result(List<BlockPos> targets, int clusterCount, Map<BlockPos, Integer> clusterIds) {
      Result {
         targets = List.copyOf(targets);
         clusterIds = Map.copyOf(clusterIds);
      }

      boolean sameCluster(BlockPos first, BlockPos second) {
         Integer firstCluster = this.clusterIds.get(first);
         return firstCluster != null && firstCluster.equals(this.clusterIds.get(second));
      }
   }

   private FlattenScaffoldCleanupScheduler() {
   }

   static Result schedule(List<BlockPos> source, BlockPos origin) {
      if (source == null || source.isEmpty()) {
         return new Result(List.of(), 0, Map.of());
      }

      Set<BlockPos> unique = new LinkedHashSet<>();
      for (BlockPos candidate : source) {
         if (candidate != null) {
            unique.add(candidate.immutable());
         }
      }
      if (unique.isEmpty()) {
         return new Result(List.of(), 0, Map.of());
      }

      Set<BlockPos> unassigned = new LinkedHashSet<>(unique);
      List<List<BlockPos>> remainingComponents = new ArrayList<>();
      while (!unassigned.isEmpty()) {
         BlockPos seed = unassigned.iterator().next();
         List<BlockPos> component = connectedComponent(seed, unassigned);
         unassigned.removeAll(component);
         remainingComponents.add(component);
      }

      List<BlockPos> ordered = new ArrayList<>(unique.size());
      Map<BlockPos, Integer> clusterIds = new LinkedHashMap<>();
      BlockPos cursor = origin;
      int cluster = 0;
      while (!remainingComponents.isEmpty()) {
         int componentIndex = highestComponentIndex(remainingComponents, cursor);
         List<BlockPos> members = remainingComponents.remove(componentIndex);
         List<BlockPos> clusterOrder = topDownLocality(members, cursor);
         for (BlockPos position : clusterOrder) {
            clusterIds.put(position, cluster);
         }
         ordered.addAll(clusterOrder);
         cursor = clusterOrder.getLast();
         cluster++;
      }
      return new Result(ordered, cluster, clusterIds);
   }

   static List<BlockPos> prioritizeCluster(List<BlockPos> targets, int startIndex,
                                           Map<BlockPos, Integer> clusterIds, BlockPos selected) {
      if (targets == null || selected == null || startIndex < 0 || startIndex >= targets.size()) {
         return targets == null ? List.of() : List.copyOf(targets);
      }
      Integer selectedCluster = clusterIds.get(selected);
      if (selectedCluster == null) {
         return List.copyOf(targets);
      }

      List<BlockPos> reordered = new ArrayList<>(targets.size());
      reordered.addAll(targets.subList(0, startIndex));
      reordered.add(selected.immutable());
      for (int index = startIndex; index < targets.size(); index++) {
         BlockPos candidate = targets.get(index);
         if (!candidate.equals(selected) && selectedCluster.equals(clusterIds.get(candidate))) {
            reordered.add(candidate.immutable());
         }
      }
      for (int index = startIndex; index < targets.size(); index++) {
         BlockPos candidate = targets.get(index);
         if (!selectedCluster.equals(clusterIds.get(candidate))) {
            reordered.add(candidate.immutable());
         }
      }
      return List.copyOf(reordered);
   }

   private static int highestComponentIndex(List<List<BlockPos>> components, BlockPos origin) {
      int selected = 0;
      int selectedTopY = maximumY(components.getFirst());
      long selectedDistance = distanceToComponentSquared(components.getFirst(), origin);
      for (int index = 1; index < components.size(); index++) {
         List<BlockPos> candidate = components.get(index);
         int candidateTopY = maximumY(candidate);
         long candidateDistance = distanceToComponentSquared(candidate, origin);
         if (candidateTopY > selectedTopY || candidateTopY == selectedTopY && candidateDistance < selectedDistance) {
            selected = index;
            selectedTopY = candidateTopY;
            selectedDistance = candidateDistance;
         }
      }
      return selected;
   }

   private static int maximumY(List<BlockPos> component) {
      return component.stream().mapToInt(BlockPos::getY).max().orElse(Integer.MIN_VALUE);
   }

   private static long distanceToComponentSquared(List<BlockPos> component, BlockPos origin) {
      if (origin == null) {
         return 0L;
      }
      return component.stream().mapToLong(position -> horizontalDistanceSquared(position, origin)).min().orElse(Long.MAX_VALUE);
   }

   static boolean directlyTouches(BlockPos first, BlockPos second) {
      if (first == null || second == null || first.equals(second)) {
         return false;
      }
      return Math.abs(first.getX() - second.getX()) <= 1
         && Math.abs(first.getY() - second.getY()) <= 1
         && Math.abs(first.getZ() - second.getZ()) <= 1;
   }

   private static List<BlockPos> connectedComponent(BlockPos seed, Set<BlockPos> candidates) {
      List<BlockPos> members = new ArrayList<>();
      List<BlockPos> frontier = new ArrayList<>();
      Set<BlockPos> visited = new LinkedHashSet<>();
      frontier.add(seed);
      visited.add(seed);
      for (int cursor = 0; cursor < frontier.size(); cursor++) {
         BlockPos current = frontier.get(cursor);
         members.add(current);
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx == 0 && dy == 0 && dz == 0) {
                     continue;
                  }
                  BlockPos neighbor = current.offset(dx, dy, dz);
                  if (candidates.contains(neighbor) && visited.add(neighbor)) {
                     frontier.add(neighbor.immutable());
                  }
               }
            }
         }
      }
      return members;
   }

   private static List<BlockPos> topDownLocality(List<BlockPos> source, BlockPos origin) {
      List<BlockPos> remaining = new ArrayList<>(source);
      remaining.sort(Comparator.comparingInt((BlockPos position) -> position.getY()).reversed());
      List<BlockPos> ordered = new ArrayList<>(remaining.size());
      BlockPos cursor = origin;
      int start = 0;
      while (start < remaining.size()) {
         int y = remaining.get(start).getY();
         int end = start + 1;
         while (end < remaining.size() && remaining.get(end).getY() == y) {
            end++;
         }

         List<BlockPos> layer = new ArrayList<>(remaining.subList(start, end));
         while (!layer.isEmpty()) {
            int nearestIndex = nearestIndex(layer, cursor);
            BlockPos next = layer.remove(nearestIndex);
            ordered.add(next);
            cursor = next;
         }
         start = end;
      }
      return ordered;
   }

   private static int nearestIndex(List<BlockPos> positions, BlockPos origin) {
      if (origin == null || positions.size() < 2) {
         return 0;
      }

      int nearestIndex = 0;
      long nearestDistance = horizontalDistanceSquared(positions.getFirst(), origin);
      for (int index = 1; index < positions.size(); index++) {
         long distance = horizontalDistanceSquared(positions.get(index), origin);
         if (distance < nearestDistance) {
            nearestIndex = index;
            nearestDistance = distance;
         }
      }
      return nearestIndex;
   }

   private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
      long dx = (long)first.getX() - second.getX();
      long dz = (long)first.getZ() - second.getZ();
      return dx * dx + dz * dz;
   }
}
