package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class FlattenCleanupExcursionRulesTest {
   @Test
   void observedClustersContinueInsideOneExcursion() {
      BlockPos anchorAt0014 = new BlockPos(-208, 68, -162);
      assertTrue(FlattenCleanupExcursionRules.mayContinue(
         new BlockPos(-214, 71, -167), new BlockPos(-216, 71, -170), anchorAt0014, true
      ));

      BlockPos anchorAt0016 = new BlockPos(-209, 63, -328);
      assertTrue(FlattenCleanupExcursionRules.mayContinue(
         new BlockPos(-213, 69, -343), new BlockPos(-217, 69, -345), anchorAt0016, true
      ));
   }

   @Test
   void crossingAdaptiveClusterBoundaryEndsExcursion() {
      BlockPos initial = new BlockPos(-197, 79, -341);
      BlockPos nextCluster = new BlockPos(-194, 78, -375);

      assertFalse(FlattenCleanupExcursionRules.mayContinue(
         initial, nextCluster, new BlockPos(-214, 63, -360), false
      ));
   }

   @Test
   void returnAnchorSupportIsAlwaysHandledBySeparateTransaction() {
      BlockPos anchor = new BlockPos(-209, 68, -162);
      BlockPos ordinary = new BlockPos(-215, 71, -167);

      assertFalse(FlattenCleanupExcursionRules.mayContinue(anchor.below(), ordinary, anchor, true));
      assertFalse(FlattenCleanupExcursionRules.mayContinue(ordinary, anchor.below(), anchor, true));
   }

   @Test
   void firstConfirmedBridgeMovesAnchorPastTheLongNaturalApproach() {
      BlockPos naturalApproachStart = new BlockPos(-200, 63, -235);
      BlockPos bridgeOrigin = new BlockPos(-193, 63, -281);
      BlockPos bridgeFeet = new BlockPos(-193, 63, -282);
      SafeRoutePlanner.RouteStep firstBridge = new SafeRoutePlanner.RouteStep(
         bridgeOrigin, bridgeFeet, SafeRoutePlanner.StepType.BRIDGE, null
      );

      assertEquals(
         bridgeOrigin,
         FlattenCleanupExcursionRules.firstScaffoldAnchor(
            0, firstBridge, bridgeFeet.below()
         ).orElseThrow()
      );
      assertFalse(naturalApproachStart.equals(bridgeOrigin));
      assertTrue(FlattenCleanupExcursionRules.firstScaffoldAnchor(
         1, firstBridge, bridgeFeet.below()
      ).isEmpty());
   }

   @Test
   void localDeferralAndOwnedSupportDropDoNotRequireAFullReturn() {
      assertTrue(FlattenCleanupExcursionRules.mayContinueAfterTopologyDeferral(true, true));
      assertFalse(FlattenCleanupExcursionRules.mayContinueAfterTopologyDeferral(true, false));
      assertFalse(FlattenCleanupExcursionRules.mayContinueAfterTopologyDeferral(false, true));

      assertTrue(FlattenCleanupExcursionRules.mayDropOwnedSupport(
         true, 1, true, true, true, false
      ));
      assertFalse(FlattenCleanupExcursionRules.mayDropOwnedSupport(
         true, 1, false, true, true, false
      ));
      assertFalse(FlattenCleanupExcursionRules.mayDropOwnedSupport(
         true, 1, true, true, false, false
      ));
      assertFalse(FlattenCleanupExcursionRules.mayDropOwnedSupport(
         true, 1, true, true, true, true
      ));
   }

   @Test
   void distantLandingRebasesTheCleanupInsteadOfReplayingTheOldCheckpoint() {
      BlockPos committedEnd = new BlockPos(-204, 75, -358);
      BlockPos landed = new BlockPos(-204, 63, -358);

      assertTrue(FlattenCleanupCheckpointRules.adjacentStep(committedEnd, landed).isEmpty());
      assertTrue(FlattenCleanupExcursionRules.shouldRebaseAfterFall(true, true));
      assertTrue(FlattenCleanupExcursionRules.shouldRebaseAfterFall(false, true));
      assertFalse(FlattenCleanupExcursionRules.shouldRebaseAfterFall(false, false));
   }

   @Test
   void stableHighSupportIsNotMistakenForThePrecheckedGroundLanding() {
      BlockPos start = new BlockPos(-205, 78, -372);
      BlockPos expected = new BlockPos(-206, 63, -372);

      assertFalse(FlattenCleanupExcursionRules.didActuallyFall(
         start, expected, new BlockPos(-205, 78, -372)
      ));
      assertFalse(FlattenCleanupExcursionRules.didActuallyFall(
         new BlockPos(-211, 77, -374), null, new BlockPos(-211, 77, -374)
      ));
      assertTrue(FlattenCleanupExcursionRules.didActuallyFall(
         new BlockPos(-215, 76, -374), new BlockPos(-215, 63, -374), new BlockPos(-215, 63, -374)
      ));
   }

   @Test
   void fallReplanCannotFeedAccessScaffoldsBackIntoThePrimaryQueue() {
      BlockPos completed = new BlockPos(-209, 77, -371);
      BlockPos nextPrimary = new BlockPos(-207, 77, -372);
      BlockPos laterPrimary = new BlockPos(-205, 76, -370);
      BlockPos accessOne = new BlockPos(-206, 76, -372);
      BlockPos accessTwo = new BlockPos(-205, 76, -372);
      List<BlockPos> targets = List.of(completed, nextPrimary, laterPrimary, accessOne, accessTwo);
      Set<BlockPos> tracked = Set.of(nextPrimary, laterPrimary, accessOne, accessTwo);
      Set<BlockPos> access = Set.of(accessOne, accessTwo);

      assertEquals(
         List.of(nextPrimary, laterPrimary),
         FlattenCleanupExcursionRules.remainingPrimaryTargets(targets, 1, tracked, access)
      );
   }
}
