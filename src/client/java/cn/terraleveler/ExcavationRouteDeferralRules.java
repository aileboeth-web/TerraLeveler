package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class ExcavationRouteDeferralRules {
   private static final Comparator<ExcavationRouteDeferralRules.Candidate> NEAREST_FIRST = Comparator.comparingInt(
         ExcavationRouteDeferralRules.Candidate::geometricDistance
      )
      .thenComparingInt(ExcavationRouteDeferralRules.Candidate::index);

   private ExcavationRouteDeferralRules() {
   }

   static List<ExcavationRouteDeferralRules.Candidate> shortlist(
      List<BlockPos> targets, int currentIndex, BlockPos failedTarget, BlockPos start, Set<BlockPos> excluded, boolean sameLayerOnly, int limit
   ) {
      if (limit > 0 && currentIndex + 1 < targets.size()) {
         List<ExcavationRouteDeferralRules.Candidate> nearest = new ArrayList<>(limit);

         for (int index = currentIndex + 1; index < targets.size(); index++) {
            BlockPos candidate = targets.get(index);
            if (!excluded.contains(candidate) && (sameLayerOnly ? candidate.getY() == failedTarget.getY() : candidate.getY() < failedTarget.getY())) {
               ExcavationRouteDeferralRules.Candidate ranked = new ExcavationRouteDeferralRules.Candidate(
                  index, candidate.immutable(), manhattanDistance(start, candidate)
               );
               int insertion = 0;

               while (insertion < nearest.size() && NEAREST_FIRST.compare(nearest.get(insertion), ranked) <= 0) {
                  insertion++;
               }

               if (insertion < limit) {
                  nearest.add(insertion, ranked);
                  if (nearest.size() > limit) {
                     nearest.removeLast();
                  }
               }
            }
         }

         return List.copyOf(nearest);
      } else {
         return List.of();
      }
   }

   static boolean acceptsRoute(int steps, int maxSteps) {
      return steps >= 0 && steps <= maxSteps;
   }

   private static int manhattanDistance(BlockPos first, BlockPos second) {
      return Math.abs(first.getX() - second.getX()) + Math.abs(first.getY() - second.getY()) + Math.abs(first.getZ() - second.getZ());
   }

   record Candidate(int index, BlockPos position, int geometricDistance) {
   }
}
