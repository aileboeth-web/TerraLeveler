package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class FlattenCleanupCheckpointRulesTest {
   @Test
   void recoversObservedStepUpLostByAutomaticEating() {
      BlockPos committedEnd = new BlockPos(-193, 71, -352);
      BlockPos current = new BlockPos(-193, 72, -351);
      SafeRoutePlanner.RouteStep pending = new SafeRoutePlanner.RouteStep(
         committedEnd, current, SafeRoutePlanner.StepType.STEP_UP, null
      );

      assertTrue(FlattenCleanupCheckpointRules.matchesPendingStep(committedEnd, current, pending));
      SafeRoutePlanner.RouteStep recovered = FlattenCleanupCheckpointRules.adjacentStep(committedEnd, current).orElseThrow();
      assertEquals(committedEnd, recovered.origin());
      assertEquals(current, recovered.position());
      assertEquals(SafeRoutePlanner.StepType.STEP_UP, recovered.type());
   }

   @Test
   void refusesToGuessAcrossMoreThanOneRouteStep() {
      BlockPos committedEnd = new BlockPos(-193, 71, -352);

      assertTrue(FlattenCleanupCheckpointRules.adjacentStep(
         committedEnd, new BlockPos(-193, 72, -350)
      ).isEmpty());
      assertTrue(FlattenCleanupCheckpointRules.adjacentStep(
         committedEnd, new BlockPos(-193, 73, -351)
      ).isEmpty());
   }

   @Test
   void pendingCheckpointMustStartAtCommittedEnd() {
      BlockPos committedEnd = new BlockPos(-193, 71, -352);
      BlockPos current = new BlockPos(-193, 72, -351);
      SafeRoutePlanner.RouteStep unrelated = new SafeRoutePlanner.RouteStep(
         new BlockPos(-194, 71, -352), current, SafeRoutePlanner.StepType.STEP_UP, null
      );

      assertFalse(FlattenCleanupCheckpointRules.matchesPendingStep(committedEnd, current, unrelated));
   }

   @Test
   void disconnectedRecordedRouteIsAbandonedInsteadOfPausedAndReplayed() {
      assertTrue(FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(true, false));
      assertFalse(FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(true, true));
      assertFalse(FlattenCleanupCheckpointRules.shouldAbandonRecordedRoute(false, false));

      assertTrue(FlattenCleanupCheckpointRules.shouldAbandonExactReverse(false, true));
      assertFalse(FlattenCleanupCheckpointRules.shouldAbandonExactReverse(true, true));
      assertFalse(FlattenCleanupCheckpointRules.shouldAbandonExactReverse(false, false));
   }
}
