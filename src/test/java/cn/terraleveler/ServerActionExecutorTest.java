package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerActionExecutorTest {
    @Test
    void toolRankingPrefersSpeedThenRemainingDurability() {
        assertTrue(ServerActionExecutor.betterToolCandidate(35.0F, 900, 35.0F, 40));
        assertFalse(ServerActionExecutor.betterToolCandidate(35.0F, 40, 35.0F, 900));
        assertTrue(ServerActionExecutor.betterToolCandidate(35.0F, 40, 9.0F, 900));
        assertFalse(ServerActionExecutor.betterToolCandidate(9.0F, 900, 35.0F, 40));
    }

    @Test
    void backpackToolReplacesUnsafeSelectedHotbarToolEvenWhenAnotherHotbarToolIsFast() {
        assertFalse(ServerActionExecutor.shouldStreamTool(9.0F, 900, 35.0F, 500, true));
        assertTrue(ServerActionExecutor.shouldStreamTool(9.0F, 900, -1.0F, -1, true));
        assertTrue(ServerActionExecutor.shouldStreamTool(35.0F, 900, 35.0F, 500, false));
        assertFalse(ServerActionExecutor.shouldStreamTool(35.0F, 400, 35.0F, 500, false));
        assertFalse(ServerActionExecutor.shouldStreamTool(1.0F, Integer.MAX_VALUE, -1.0F, -1, true));
    }

    @Test
    void safeHotbarToolMovesIntoTheSlotAnExternalAutoToolKeepsSelecting() {
        assertTrue(ServerActionExecutor.shouldRescueUnsafeSelectedTool(true, 1, 2));
        assertFalse(ServerActionExecutor.shouldRescueUnsafeSelectedTool(false, 1, 2));
        assertFalse(ServerActionExecutor.shouldRescueUnsafeSelectedTool(true, 2, 2));
        assertFalse(ServerActionExecutor.shouldRescueUnsafeSelectedTool(true, 1, -1));
    }

    @Test
    void rollingScaffoldCleanupRequiresAnActuallyEffectiveMiningTool() {
        assertTrue(ServerActionExecutor.isEffectiveMiningTool(2.0F));
        assertTrue(ServerActionExecutor.isEffectiveMiningTool(9.0F));
        assertFalse(ServerActionExecutor.isEffectiveMiningTool(1.0F));
        assertFalse(ServerActionExecutor.isEffectiveMiningTool(0.0F));
        assertFalse(ServerActionExecutor.isUsableToolCandidate(
                net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState(), 1.0F));
        assertTrue(ServerActionExecutor.isUsableToolCandidate(
                net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState(), 2.0F));
        assertFalse(ServerActionExecutor.isUsableToolCandidate(
                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 1.0F));
    }

    @Test
    void fullInventoryUsesAnInPlaceToolSwapInsteadOfRequiringAnEmptySlot() {
        assertEquals(ServerActionExecutor.ToolPreparation.PENDING,
                ServerActionExecutor.toolPreparation(false, true, true));
        assertEquals(ServerActionExecutor.ToolPreparation.READY,
                ServerActionExecutor.toolPreparation(true, true, true));
        assertEquals(ServerActionExecutor.ToolPreparation.UNAVAILABLE,
                ServerActionExecutor.toolPreparation(false, true, false));
    }

    @Test
    void everyConfirmedLedgerScaffoldBreakRequiresTheSafeToolGuard() {
        assertTrue(ConstructionController.requiresSafeEffectiveToolForTrackedScaffold(true, true));
        assertFalse(ConstructionController.requiresSafeEffectiveToolForTrackedScaffold(true, false));
        assertFalse(ConstructionController.requiresSafeEffectiveToolForTrackedScaffold(false, true));
        assertFalse(ConstructionController.requiresSafeEffectiveToolForTrackedScaffold(false, false));
    }

    @Test
    void deterministicToolScoreDoesNotDependOnTransientSelectedSlotAttributes() {
        assertEquals(1.5F, ServerActionExecutor.toolScore(1.5F, 0));
        assertEquals(35.0F, ServerActionExecutor.toolScore(9.0F, 5));
        assertEquals(1.0F, ServerActionExecutor.toolScore(1.0F, 5),
                "efficiency does not make an ineffective item a mining tool");
    }

    @Test
    void stableCurrentSlotUsesItsReal26_1AttributeScore() {
        assertEquals(27.5F, ServerActionExecutor.candidateToolScore(1.0F, 0, true, 27.5F));
        assertEquals(1.0F, ServerActionExecutor.candidateToolScore(1.0F, 0, false, 27.5F));
        assertEquals(35.0F, ServerActionExecutor.candidateToolScore(9.0F, 5, true, 27.5F));
    }

    @Test
    void candidateToolSpeedIncludesHasteAndEnvironmentalModifiersWithoutSelectingIt() {
        assertEquals(49.0F, ServerActionExecutor.applyMiningModifiers(
                35.0F, 1, 1.0F, 1.0F, false, 1.0F, true), .0001F);
        assertEquals(9.8F, ServerActionExecutor.applyMiningModifiers(
                35.0F, 1, 1.0F, 1.0F, false, 1.0F, false), .0001F);
        assertEquals(4.9F, ServerActionExecutor.applyMiningModifiers(
                35.0F, 1, 1.0F, 1.0F, true, 0.1F, true), .0001F);
    }

    @Test
    void toolSwitchMustSettleBeforeTheBreakRequestStarts() {
        assertTrue(ServerActionExecutor.requiresToolSettle(0, 2));
        assertFalse(ServerActionExecutor.requiresToolSettle(2, 2));
        assertFalse(ServerActionExecutor.requiresToolSettle(2, -1));
    }

    @Test
    void twoTickVanillaBreaksUseTheSameTickFastFinishPath() {
        assertFalse(ServerActionExecutor.shouldFastFinish(Math.nextDown(0.5F)));
        assertTrue(ServerActionExecutor.shouldFastFinish(0.5F));
        assertTrue(ServerActionExecutor.shouldFastFinish(Math.nextDown(1.0F)));
        assertFalse(ServerActionExecutor.shouldFastFinish(1.0F),
                "one-tick blocks are already completed by startDestroyBlock");
    }

    @Test
    void rejectedBreakStartMustRetryStartInsteadOfSendingOnlyContinuePackets() {
        assertTrue(ServerActionExecutor.shouldRetryRejectedBreakStart(0, false));
        assertFalse(ServerActionExecutor.shouldRetryRejectedBreakStart(0, true));
        assertFalse(ServerActionExecutor.shouldRetryRejectedBreakStart(1, false));
    }

    @Test
    void breakOnlySucceedsForAirAndReclassifiesPureLiquid() {
        assertEquals(ServerActionExecutor.BreakPostcondition.SUCCESS,
                ServerActionExecutor.breakPostcondition(true, false, false));
        assertEquals(ServerActionExecutor.BreakPostcondition.RECLASSIFY,
                ServerActionExecutor.breakPostcondition(false, true, true));
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                ServerActionExecutor.breakPostcondition(false, false, false));
    }

    @Test
    void waterloggedSolidKeepsBreaking() {
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                ServerActionExecutor.breakPostcondition(false, true, false));
    }

    @Test
    void collisionKnockbackBeyondBreakReachRequestsRepositionInsteadOfFailure() {
        assertEquals(ServerActionExecutor.BreakReachDecision.CONTINUE,
                ServerActionExecutor.breakReachDecision(WorkPoseRules.SAFE_REACH_SQUARED));
        assertEquals(ServerActionExecutor.BreakReachDecision.NEED_REPOSITION,
                ServerActionExecutor.breakReachDecision(Math.nextUp(WorkPoseRules.SAFE_REACH_SQUARED)));
    }

    @Test
    void placementRequiresTheExactExpectedBlock() {
        Object expectedSand = new Object();
        Object anotherSandLikeBlock = new Object();

        assertTrue(ServerActionExecutor.exactIdentity(expectedSand, expectedSand));
        assertFalse(ServerActionExecutor.exactIdentity(anotherSandLikeBlock, expectedSand));
        assertFalse(ServerActionExecutor.exactIdentity(null, expectedSand));
    }

    @Test
    void fallingBlockRequiresAStableLandingPostcondition() {
        assertFalse(ServerActionExecutor.placementPostcondition(true, true, false));
        assertTrue(ServerActionExecutor.placementPostcondition(true, true, true));
        assertTrue(ServerActionExecutor.placementPostcondition(true, false, false));
        assertFalse(ServerActionExecutor.placementPostcondition(false, true, true));
    }

    @Test
    void drySolidScaffoldAlreadySatisfiesAFluidSealButAirOrWaterDoesNot() {
        assertTrue(ServerActionExecutor.drySolidContainment(false, false));
        assertFalse(ServerActionExecutor.drySolidContainment(false, true));
        assertFalse(ServerActionExecutor.drySolidContainment(true, false));
    }

    @Test
    void reconcileAndTickInSameGameTickCannotDoubleConfirm() {
        var confirmation = new ServerActionExecutor.PlacementConfirmation(2);

        assertFalse(confirmation.observe(100, true));
        assertFalse(confirmation.observe(100, true));
        assertTrue(confirmation.observe(101, true));
    }

    @Test
    void confirmationMustBeConsecutiveAndResetHasNoStaleState() {
        var confirmation = new ServerActionExecutor.PlacementConfirmation(2);

        assertFalse(confirmation.observe(20, true));
        assertFalse(confirmation.observe(21, false));
        assertFalse(confirmation.observe(22, true));
        assertTrue(confirmation.observe(23, true));

        confirmation.reset();
        assertFalse(confirmation.observe(24, true));
        assertFalse(confirmation.observe(30, true));
        assertTrue(confirmation.observe(31, true));
    }

    @Test
    void predictedAirMustRemainStableAcrossTwoServerTicks() {
        var confirmation = new ServerActionExecutor.BreakConfirmation(2);

        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(40, ServerActionExecutor.BreakPostcondition.SUCCESS));
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(41, ServerActionExecutor.BreakPostcondition.KEEP_BREAKING),
                "a one-tick predicted AIR rollback must not commit excavation progress");
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(42, ServerActionExecutor.BreakPostcondition.SUCCESS));
        assertEquals(ServerActionExecutor.BreakPostcondition.SUCCESS,
                confirmation.observe(43, ServerActionExecutor.BreakPostcondition.SUCCESS));
    }

    @Test
    void breakConfirmationDoesNotMixAirAndLiquidOutcomesOrDoubleCountATick() {
        var confirmation = new ServerActionExecutor.BreakConfirmation(2);

        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(100, ServerActionExecutor.BreakPostcondition.SUCCESS));
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(100, ServerActionExecutor.BreakPostcondition.SUCCESS));
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                confirmation.observe(101, ServerActionExecutor.BreakPostcondition.RECLASSIFY));
        assertEquals(ServerActionExecutor.BreakPostcondition.RECLASSIFY,
                confirmation.observe(102, ServerActionExecutor.BreakPostcondition.RECLASSIFY));
    }

    @Test
    void predictedAirCannotBecomeAServerConfirmedBreak() {
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                ServerActionExecutor.serverVerifiedBreakObservation(
                        ServerActionExecutor.BreakPostcondition.SUCCESS, true));
        assertEquals(ServerActionExecutor.BreakPostcondition.KEEP_BREAKING,
                ServerActionExecutor.serverVerifiedBreakObservation(
                        ServerActionExecutor.BreakPostcondition.RECLASSIFY, true));
        assertEquals(ServerActionExecutor.BreakPostcondition.SUCCESS,
                ServerActionExecutor.serverVerifiedBreakObservation(
                        ServerActionExecutor.BreakPostcondition.SUCCESS, false));
    }

    @Test
    void sameTickMatchChangesCannotManufactureTwoConfirmationTicks() {
        var confirmation = new ServerActionExecutor.PlacementConfirmation(2);

        assertFalse(confirmation.observe(100, true));
        assertFalse(confirmation.observe(100, false));
        assertFalse(confirmation.observe(100, true));
        assertTrue(confirmation.observe(101, true));
    }

    @Test
    void inventoryIsOnlyNeededWhenSchedulerPermitsASendOrRetry() {
        var requests = new ServerActionExecutor.PlacementRequestTracker(10, 3);

        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.SEND, requests.decision(100));
        requests.recordSend(100);
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.WAIT, requests.decision(100));
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.WAIT, requests.decision(109));
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.SEND, requests.decision(110));

        requests.recordSend(110);
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.WAIT, requests.decision(119));
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.SEND, requests.decision(120));
        requests.recordSend(120);
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.WAIT, requests.decision(129));
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.EXHAUSTED, requests.decision(130));

        requests.reset();
        assertEquals(0, requests.attempts());
        assertEquals(ServerActionExecutor.PlacementRequestTracker.Decision.SEND, requests.decision(130));
    }

    @Test
    void finalFallingItemKeepsWaitingForItsServerLandingAfterTheStackEmpties() {
        assertTrue(ServerActionExecutor.shouldAwaitMissingPlacementItem(1, 20));
        assertTrue(ServerActionExecutor.shouldAwaitMissingPlacementItem(2, 79));
        assertFalse(ServerActionExecutor.shouldAwaitMissingPlacementItem(0, 20));
        assertFalse(ServerActionExecutor.shouldAwaitMissingPlacementItem(2, 80));
    }

    @Test
    void activeBreakReassertsItsToolWithoutDeferringProgress() {
        assertTrue(ServerActionExecutor.shouldDeferToolSwitch(0, 3, 0));
        assertFalse(ServerActionExecutor.shouldDeferToolSwitch(0, 3, 1));
        assertFalse(ServerActionExecutor.shouldDeferToolSwitch(3, 3, 0));
    }

    @Test
    void activeBreakAdoptsASafeSlotChosenByTheExistingToolManager() {
        assertFalse(ServerActionExecutor.shouldAdoptExternalTool(0, 0, 3, true));
        assertTrue(ServerActionExecutor.shouldAdoptExternalTool(1, 0, 3, true));
        assertFalse(ServerActionExecutor.shouldAdoptExternalTool(1, 0, 3, false));
        assertFalse(ServerActionExecutor.shouldAdoptExternalTool(1, 3, 3, true));
    }

    @Test
    void fastBreakReassertsAfterAnUnsafeExternalToolOverride() {
        assertFalse(ServerActionExecutor.shouldReassertFastBreak(0, true, false));
        assertTrue(ServerActionExecutor.shouldReassertFastBreak(1, true, false));
        assertFalse(ServerActionExecutor.shouldReassertFastBreak(2, true, false));
        assertTrue(ServerActionExecutor.shouldReassertFastBreak(20, true, false));
        assertFalse(ServerActionExecutor.shouldReassertFastBreak(20, false, false));
        assertFalse(ServerActionExecutor.shouldReassertFastBreak(20, true, true));
    }

    @Test
    void observedPlayerMiningBoostMustBeFastForTwoConsecutiveConflictTicks() {
        int streak = ServerActionExecutor.nextObservedFastProgressStreak(0, 0.14F);
        assertEquals(0, streak, "the transient post-switch copper speed is not fast");

        streak = ServerActionExecutor.nextObservedFastProgressStreak(streak, 0.544F);
        assertEquals(1, streak);
        assertFalse(ServerActionExecutor.hasStableObservedFastProgress(streak));

        streak = ServerActionExecutor.nextObservedFastProgressStreak(streak, 0.544F);
        assertTrue(ServerActionExecutor.hasStableObservedFastProgress(streak));
        assertTrue(ServerActionExecutor.shouldReassertFastBreak(20, true, false));

        streak = ServerActionExecutor.nextObservedFastProgressStreak(streak, 0.49F);
        assertEquals(0, streak, "a slow observation resets the stability proof");
        assertEquals(0, ServerActionExecutor.nextObservedFastProgressStreak(1, Float.NaN));
    }

    @Test
    void submittedBridgePlacementDoesNotRequireAnotherInventoryItemWhileConfirming() {
        assertTrue(ConstructionController.shouldAcquireBridgeMaterial(true));
        assertFalse(ConstructionController.shouldAcquireBridgeMaterial(false));
    }

    @Test
    void realPlayerAabbCollisionIsAPlacementBlocker() {
        BlockPos target = new BlockPos(10, 20, 30);

        assertTrue(ServerActionExecutor.placementIntersectsPlayer(
                new AABB(10.2, 20.0, 30.2, 10.8, 21.8, 30.8), target));
        assertFalse(ServerActionExecutor.placementIntersectsPlayer(
                new AABB(10.2, 21.0, 30.2, 10.8, 22.8, 30.8), target));
        assertFalse(ServerActionExecutor.placementIntersectsPlayer(
                new AABB(12.0, 20.0, 30.0, 12.6, 21.8, 30.6), target));
    }

    @Test
    void centeringOnTheRouteOriginClearsTheObservedAdjacentScaffoldCollision() {
        BlockPos target = new BlockPos(-424, 53, -186);

        assertTrue(ServerActionExecutor.placementIntersectsPlayer(
                new AABB(-423.800, 53.000, -186.449,
                        -423.200, 54.800, -185.849), target));
        assertFalse(ServerActionExecutor.placementIntersectsPlayer(
                new AABB(-423.800, 53.000, -186.800,
                        -423.200, 54.800, -186.200), target));
    }
}
