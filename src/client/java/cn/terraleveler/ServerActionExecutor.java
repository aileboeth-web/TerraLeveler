package cn.terraleveler;

import cn.terraleveler.mixin.BlockStatePredictionHandlerAccessor;
import cn.terraleveler.mixin.ClientLevelSequenceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Executes exactly one server-authoritative block action at a time. */
public final class ServerActionExecutor {
    /**
     * RECLASSIFY means a break target is now a pure LiquidBlock: the break
     * transaction is closed, but the excavation cell is not complete and must
     * be sent through fluid planning. NEED_REPOSITION is a recoverable
     * interaction precondition; move the real player before retrying.
     */
    public enum Status { RUNNING, SUCCESS, RECLASSIFY, NEED_REPOSITION, TOOL_BOX_REQUIRED, FAILED }

    enum ToolPreparation { READY, PENDING, UNAVAILABLE }

    /** SOLID_BLOCK requires the requested block; FLUID_REMOVED also accepts an existing dry solid seal. */
    public enum PlacementExpectation { SOLID_BLOCK, FLUID_REMOVED }

    private enum Type { NONE, BREAK, PLACE }
    enum BreakPostcondition { KEEP_BREAKING, SUCCESS, RECLASSIFY }
    enum BreakReachDecision { CONTINUE, NEED_REPOSITION }

    static final int PLACEMENT_CONFIRMATION_TICKS = 2;
    static final int BREAK_CONFIRMATION_TICKS = 2;
    static final int PLACEMENT_RETRY_TICKS = 10;
    static final int MAX_PLACEMENT_ATTEMPTS = 3;
    static final float FAST_FINISH_MIN_PROGRESS = 0.5F;
    static final int TOOL_STREAM_PENDING = -2;
    static final int MAX_PLACEMENT_PREDICTION_WAIT_TICKS = 200;

    private Type type = Type.NONE;
    private BlockPos target;
    private BlockPos placementTarget;
    private BlockPos support;
    private Item item;
    private Block expectedBlock;
    private Block activeBreakBlock;
    private PlacementExpectation expectation;
    private boolean supportExplicit;
    private int age;
    private int breakTransactionAge;
    private int selectedToolSlot = -1;
    private boolean breakAwaitingConfirmation;
    private boolean breakPredictionWaitLogged;
    private boolean breakFastFinishRequested;
    private int breakObservedFastProgressStreak;
    private int placementPredictionWaitTicks;
    private String failure = "";
    private String blockage = "";
    private final PlacementConfirmation confirmation = new PlacementConfirmation(PLACEMENT_CONFIRMATION_TICKS);
    private final BreakConfirmation breakConfirmation = new BreakConfirmation(BREAK_CONFIRMATION_TICKS);
    private final PlacementRequestTracker placementRequests = new PlacementRequestTracker(
            PLACEMENT_RETRY_TICKS, MAX_PLACEMENT_ATTEMPTS);

    public Status tickBreak(Minecraft mc, BlockPos requested, TerraLevelerConfig config) {
        return tickBreak(mc, requested, config, true);
    }

    public Status tickBreak(Minecraft mc, BlockPos requested, TerraLevelerConfig config,
                            boolean allowInventoryToolStreaming) {
        return tickBreak(mc, requested, config, allowInventoryToolStreaming, true);
    }

    public Status tickBreak(Minecraft mc, BlockPos requested, TerraLevelerConfig config,
                            boolean allowInventoryToolStreaming, boolean allowToolBoxRequest) {
        if (!claim(Type.BREAK, requested)) return conflict(requested);
        if (++breakTransactionAge > 240) {
            BlockState observed = mc.level.getBlockState(requested);
            BreakPostcondition observedPostcondition = breakPostcondition(observed.isAir(),
                    !observed.getFluidState().isEmpty(), FluidBlocks.isPureFluid(observed));
            if (observedPostcondition == BreakPostcondition.KEEP_BREAKING) {
                return fail(mc, "服务器持续确认目标方块仍存在，破坏请求可能被领地保护或服务器规则拒绝 "
                        + requested.toShortString() + " block=" + observed.getBlock());
            }
            return fail(mc, "目标状态发生变化，但12秒内未形成连续两刻的稳定服务器确认 "
                    + requested.toShortString() + " block=" + observed.getBlock());
        }
        BlockState state = mc.level.getBlockState(requested);
        BreakPostcondition postcondition = breakPostcondition(state.isAir(),
                !state.getFluidState().isEmpty(), FluidBlocks.isPureFluid(state));
        boolean predictionPending = postcondition != BreakPostcondition.KEEP_BREAKING
                && hasPendingBlockPrediction(mc, requested);
        BreakPostcondition confirmed = breakConfirmation.observe(mc.level.getGameTime(),
                serverVerifiedBreakObservation(postcondition, predictionPending));
        if (confirmed == BreakPostcondition.SUCCESS) return success(mc);
        if (confirmed == BreakPostcondition.RECLASSIFY) return reclassify(mc);
        if (postcondition != BreakPostcondition.KEEP_BREAKING) {
            if (predictionPending && !breakPredictionWaitLogged) {
                breakPredictionWaitLogged = true;
                TerraLevelerDiagnostics.log("ACTION", "break-awaiting-server-ack target="
                        + requested.toShortString() + " observed=" + postcondition);
            }
            if (!breakAwaitingConfirmation && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            breakAwaitingConfirmation = true;
            age++;
            return Status.RUNNING;
        }
        if (breakAwaitingConfirmation) {
            // A one-tick predicted AIR/liquid was rolled back. Restart the real
            // break request instead of continuing a stopped client action.
            breakAwaitingConfirmation = false;
            age = 0;
            selectedToolSlot = -1;
            breakObservedFastProgressStreak = 0;
            breakPredictionWaitLogged = false;
        }
        if (state.is(Blocks.BEDROCK)) return fail(mc, "目标是基岩，不能挖掘 " + requested.toShortString());
        if (activeBreakBlock == null) {
            activeBreakBlock = state.getBlock();
        } else if (activeBreakBlock != state.getBlock()) {
            // Gravel/sand or a server-side block update can replace the locked
            // target while it is being mined. Restart progress and tool scoring
            // for the new block instead of continuing with stale break state.
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            TerraLevelerDiagnostics.log("ACTION", "break-block-changed target=" + requested.toShortString()
                    + " old=" + activeBreakBlock + " new=" + state.getBlock());
            activeBreakBlock = state.getBlock();
            selectedToolSlot = -1;
            age = 0;
            // A falling block or another server update replacing this cell is
            // observable transaction progress, not twelve seconds of silence.
            breakTransactionAge = 0;
            breakObservedFastProgressStreak = 0;
        }
        double reachSquared = mc.player.getEyePosition().distanceToSqr(
                requested.getX() + .5, requested.getY() + .5, requested.getZ() + .5);
        if (breakReachDecision(reachSquared) == BreakReachDecision.NEED_REPOSITION) {
            return needReposition(mc, "挖掘目标超出正常触及距离 " + requested.toShortString());
        }
        if (selectedToolSlot < 0) {
            selectedToolSlot = fastestSafeTool(mc, state, config, allowInventoryToolStreaming);
            if (selectedToolSlot == TOOL_STREAM_PENDING) {
                selectedToolSlot = -1;
                AutoNavigator.release(mc);
                AutoNavigator.lookAt(mc, requested);
                return Status.RUNNING;
            }
        }
        boolean selectedEffectiveTool = selectedToolSlot >= 0
                && isEffectiveMiningTool(mc.player.getInventory().getItem(selectedToolSlot).getDestroySpeed(state));
        if (allowToolBoxRequest && requiresManagedTool(state) && !selectedEffectiveTool) {
            TerraLevelerDiagnostics.log("TOOL", "tool-box-required target=" + requested.toShortString()
                    + " block=" + state.getBlock() + " selected=" + selectedToolSlot);
            return Status.TOOL_BOX_REQUIRED;
        }
        boolean instantBreak = state.getDestroySpeed(mc.level, requested) == 0.0F;
        if (selectedToolSlot < 0) selectedToolSlot = emptyHotbarFallback(mc);
        // Plants such as leaf litter break instantly and never cost durability, so the
        // held item is fine; otherwise any non-damageable hotbar item avoids a swap.
        if (selectedToolSlot < 0) selectedToolSlot = harmlessHotbarFallback(mc, instantBreak);
        if (selectedToolSlot < 0 && streamNonDamagingFallback(mc)) {
            selectedToolSlot = -1;
            AutoNavigator.release(mc);
            return Status.RUNNING;
        }
        if (selectedToolSlot < 0) return fail(mc, "没有可避免消耗低耐久工具的快捷栏槽位");
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        boolean reassertFastBreak = false;
        boolean predictedEffectiveFastReassert = false;
        boolean observedFastReassert = false;
        float observedReassertProgress = 0.0F;
        if (requiresToolSettle(currentSlot, selectedToolSlot)) {
            ItemStack external = mc.player.getInventory().getItem(currentSlot);
            boolean externalSafe = !external.isEmpty() && (!external.isDamageableItem()
                    || external.getMaxDamage() - external.getDamageValue() > config.durabilityReserve);
            if (shouldAdoptExternalTool(age, currentSlot, selectedToolSlot, externalSafe)) {
                TerraLevelerDiagnostics.log("TOOL", "external-tool-adopted target=" + requested.toShortString()
                        + " from=" + selectedToolSlot + " to=" + currentSlot
                        + " item=" + external + " block=" + state.getBlock());
                selectedToolSlot = currentSlot;
            } else {
                selectAndSyncToolSlot(mc, selectedToolSlot);
                predictedEffectiveFastReassert = BatchBreakExecutor.isEffectiveFastBreak(
                        mc, requested, state, mc.player.getInventory().getItem(selectedToolSlot));
                observedReassertProgress = state.getDestroyProgress(mc.player, mc.level, requested);
                breakObservedFastProgressStreak = nextObservedFastProgressStreak(
                        breakObservedFastProgressStreak, observedReassertProgress);
                observedFastReassert = hasStableObservedFastProgress(breakObservedFastProgressStreak);
                reassertFastBreak = shouldReassertFastBreak(
                        age, breakFastFinishRequested || predictedEffectiveFastReassert || observedFastReassert,
                        externalSafe);
            }
            AutoNavigator.release(mc);
            AutoNavigator.lookAt(mc, requested);
            boolean actionDeferred = shouldDeferToolSwitch(currentSlot, selectedToolSlot, age);
            if (actionDeferred || age % 20 == 0) {
                TerraLevelerDiagnostics.log("TOOL", "switch-settle target=" + requested.toShortString()
                        + " from=" + currentSlot + " to=" + selectedToolSlot
                        + " block=" + state.getBlock() + " actionDeferred=" + actionDeferred);
            }
            if (actionDeferred) return Status.RUNNING;
        }
        ItemStack held = mc.player.getInventory().getSelectedItem();
        if (!instantBreak && held.isDamageableItem()
                && held.getMaxDamage() - held.getDamageValue() <= config.durabilityReserve) {
            return fail(mc, "合适工具的剩余耐久不足");
        }
        AutoNavigator.release(mc);
        AutoNavigator.lookAt(mc, requested);
        if (reassertFastBreak) {
            boolean sent = BatchBreakExecutor.sendFastBreak(mc, requested);
            TerraLevelerDiagnostics.log("ACTION", "fast-reassert target=" + requested.toShortString()
                    + " age=" + age + " slot=" + selectedToolSlot + " sent=" + sent
                    + " predictedEffective=" + predictedEffectiveFastReassert
                    + " observed=" + observedFastReassert
                    + " observedProgress=" + observedReassertProgress
                    + " observedStreak=" + breakObservedFastProgressStreak
                    + " reason=unsafe-external-tool");
        }
        if (age == 0) {
            float progressPerTick = state.getDestroyProgress(mc.player, mc.level, requested);
            boolean accepted = mc.gameMode.startDestroyBlock(requested, Direction.UP);
            boolean fastFinish = accepted && shouldFastFinish(progressPerTick);
            boolean followUpAccepted = false;
            if (fastFinish) {
                // litematica-printer uses the same two progress contributions
                // in one client tick for blocks that need at most two vanilla
                // mining ticks. Keep TerraLeveler's server-state confirmation intact.
                followUpAccepted = mc.gameMode.continueDestroyBlock(requested, Direction.UP);
                breakFastFinishRequested = true;
            }
            TerraLevelerDiagnostics.log("ACTION", "break-start target=" + requested.toShortString()
                    + " block=" + state.getBlock() + " slot=" + selectedToolSlot
                    + " tool=" + held + " baseDestroySpeed=" + held.getDestroySpeed(state)
                    + " effectiveDestroySpeed=" + mc.player.getDestroySpeed(state)
                    + " progressPerTick=" + progressPerTick
                    + " enchanted=" + held.isEnchanted()
                    + " accepted=" + accepted + " fastFinish=" + fastFinish
                    + " followUpAccepted=" + followUpAccepted + " face=" + Direction.UP);
            if (shouldRetryRejectedBreakStart(age, accepted)) {
                return Status.RUNNING;
            }
        } else {
            boolean accepted = mc.gameMode.continueDestroyBlock(requested, Direction.UP);
            if (age % 20 == 0) {
                TerraLevelerDiagnostics.log("ACTION", "break-progress target=" + requested.toShortString()
                        + " age=" + age + " block=" + state.getBlock()
                        + " tool=" + held + " baseDestroySpeed=" + held.getDestroySpeed(state)
                        + " effectiveDestroySpeed=" + mc.player.getDestroySpeed(state)
                        + " enchanted=" + held.isEnchanted()
                        + " accepted=" + accepted);
            }
        }
        age++;
        if (!requiresToolSettle(currentSlot, selectedToolSlot)) {
            breakObservedFastProgressStreak = 0;
        }
        return Status.RUNNING;
    }

    public Status tickPlace(Minecraft mc, BlockPos requested, Item requestedItem, BlockPos requestedSupport,
                            PlacementExpectation requestedExpectation) {
        return tickPlace(mc, requested, requested, requestedItem, requestedSupport, requestedExpectation);
    }

    public Status tickPlace(Minecraft mc, BlockPos expectedTarget, BlockPos requestedPlacementTarget,
                            Item requestedItem, BlockPos requestedSupport,
                            PlacementExpectation requestedExpectation) {
        if (!claim(Type.PLACE, expectedTarget)) return conflict(expectedTarget);
        if (item == null) {
            if (!(requestedItem instanceof BlockItem blockItem)) {
                return fail(mc, "放置物品不是方块物品：" + requestedItem);
            }
            item = requestedItem;
            expectedBlock = blockItem.getBlock();
            placementTarget = requestedPlacementTarget.immutable();
            supportExplicit = requestedSupport != null;
            support = supportExplicit ? requestedSupport.immutable() : findSupport(mc, placementTarget);
            expectation = requestedExpectation;
        } else if (!samePlacementRequest(requestedPlacementTarget, requestedItem,
                requestedSupport, requestedExpectation)) {
            return conflict(expectedTarget);
        }

        // Observe the exact postcondition before checking inventory. A request
        // may consume the final stack; that must not turn an in-flight request
        // into an immediate false failure while the server response is pending.
        PlacementObservation observation = observePlacement(mc);
        if (observation.confirmed()) return success(mc);
        if (observation.exactBlockPresent()) {
            age++;
            if (observation.predictionPending()
                    && ++placementPredictionWaitTicks > MAX_PLACEMENT_PREDICTION_WAIT_TICKS) {
                return fail(mc, "服务器 10 秒内没有确认放置预测 " + expectedBlock + " 位于 "
                        + expectedTarget.toShortString() + "；不会把客户端预测当作服务器结果");
            }
            return Status.RUNNING;
        }

        long gameTime = mc.level.getGameTime();
        PlacementRequestTracker.Decision decision = placementRequests.decision(gameTime);
        if (decision == PlacementRequestTracker.Decision.WAIT) {
            age++;
            return Status.RUNNING;
        }
        if (decision == PlacementRequestTracker.Decision.EXHAUSTED) {
            return fail(mc, "服务器三次未确认期望方块 " + expectedBlock + " 位于 "
                    + expectedTarget.toShortString() + "；expectedState="
                    + mc.level.getBlockState(expectedTarget) + " placementState="
                    + mc.level.getBlockState(placementTarget) + " belowState="
                    + mc.level.getBlockState(expectedTarget.below()));
        }

        // Placement into the real player's body is a navigation precondition,
        // not a rejected placement attempt. Do not spend an attempt on it.
        if (placementIntersectsPlayer(mc.player.getBoundingBox(), placementTarget)) {
            return needReposition(mc, "真实玩家与放置目标重叠 " + placementTarget.toShortString());
        }
        if (!selectHotbarItem(mc, item)) {
            if (shouldAwaitMissingPlacementItem(placementRequests.attempts(), age)) {
                // The last item may already exist as a falling entity between
                // placementTarget and expectedTarget. Keep observing the exact
                // landing postcondition before asking the controller to unpack
                // more material; opening storage cannot interrupt this request.
                age++;
                return Status.RUNNING;
            }
            return fail(mc, "发送放置请求前快捷栏缺少 " + item);
        }
        if (!supportExplicit && !usableSupport(mc, support)) support = findSupport(mc, placementTarget);
        if (!usableSupport(mc, support)) {
            return fail(mc, "目标没有可点击的支撑面 " + placementTarget.toShortString());
        }
        if (mc.player.getEyePosition().distanceToSqr(
                support.getX() + .5, support.getY() + .5, support.getZ() + .5)
                > WorkPoseRules.SAFE_REACH_SQUARED) {
            return fail(mc, "放置支撑面超出正常触及距离 " + support.toShortString());
        }

        Direction face = faceFromSupportToTarget(support, placementTarget);
        if (face == null) return fail(mc, "放置目标与支撑面不相邻");
        AutoNavigator.release(mc);
        AutoNavigator.lookAt(mc, support);
        Vec3 hitLocation = Vec3.atCenterOf(support).add(face.getStepX() * .5,
                face.getStepY() * .5, face.getStepZ() * .5);
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitLocation, face, support, false));
        placementRequests.recordSend(gameTime);
        age++;
        TerraLevelerDiagnostics.log("ACTION", "place-request target=" + placementTarget.toShortString()
                + " expectedLanding=" + expectedTarget.toShortString()
                + " support=" + support.toShortString() + " face=" + face
                + " item=" + item + " expectedBlock=" + expectedBlock
                + " attempt=" + placementRequests.attempts() + " result=" + result);
        return Status.RUNNING;
    }

    public void cancel(Minecraft mc, String reason) {
        if (type == Type.BREAK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (type != Type.NONE) TerraLevelerDiagnostics.log("ACTION", "cancel type=" + type + " target=" + target + " reason=" + reason);
        reset();
    }

    /**
     * Reconciles an in-flight request with the latest server world snapshot.
     * Calling reconcile and tickPlace in the same game tick cannot count as two
     * confirmation ticks.
     */
    public boolean reconcile(Minecraft mc) {
        if (type == Type.NONE || mc.level == null) return false;
        if (type == Type.BREAK) {
            BlockState state = mc.level.getBlockState(target);
            BreakPostcondition postcondition = breakPostcondition(state.isAir(),
                    !state.getFluidState().isEmpty(), FluidBlocks.isPureFluid(state));
            // Same rule as tickBreak: a client-predicted AIR is not a server result.
            boolean predictionPending = postcondition != BreakPostcondition.KEEP_BREAKING
                    && hasPendingBlockPrediction(mc, target);
            BreakPostcondition confirmed = breakConfirmation.observe(mc.level.getGameTime(),
                    serverVerifiedBreakObservation(postcondition, predictionPending));
            if (confirmed == BreakPostcondition.SUCCESS) {
                success(mc);
                return true;
            }
            if (confirmed == BreakPostcondition.RECLASSIFY) {
                reclassify(mc);
                return true;
            }
            return false;
        }
        if (observePlacement(mc).confirmed()) {
            success(mc);
            return true;
        }
        return false;
    }

    public boolean isIdle() { return type == Type.NONE; }
    boolean isBreaking(BlockPos requested) {
        return type == Type.BREAK && target != null && target.equals(requested);
    }
    public boolean hasSentPlacementRequest() {
        return type == Type.PLACE && placementRequests.attempts() > 0;
    }
    public String failure() { return failure; }
    public String blockage() { return blockage; }

    private boolean claim(Type requestedType, BlockPos requestedTarget) {
        if (type == Type.NONE) {
            type = requestedType;
            target = requestedTarget.immutable();
            age = 0;
            breakTransactionAge = 0;
            selectedToolSlot = -1;
            failure = "";
            blockage = "";
            confirmation.reset();
            breakConfirmation.reset();
            placementRequests.reset();
            return true;
        }
        return type == requestedType && target.equals(requestedTarget);
    }

    private boolean samePlacementRequest(BlockPos requestedPlacementTarget, Item requestedItem,
                                         BlockPos requestedSupport,
                                         PlacementExpectation requestedExpectation) {
        if (requestedItem != item || requestedExpectation != expectation) return false;
        if (!placementTarget.equals(requestedPlacementTarget)) return false;
        if (supportExplicit != (requestedSupport != null)) return false;
        return !supportExplicit || support.equals(requestedSupport);
    }

    private Status conflict(BlockPos requested) {
        failure = "动作冲突：当前 " + type + " " + target + "，新目标 " + requested;
        blockage = "";
        TerraLevelerDiagnostics.log("ACTION", "failed-conflict " + failure);
        // FAILED is terminal: never leave the superseded transaction active.
        resetKeepingMessages();
        return Status.FAILED;
    }

    private Status success(Minecraft mc) {
        if (type == Type.BREAK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        TerraLevelerDiagnostics.log("ACTION", "confirmed type=" + type + " target=" + target + " age=" + age
                + " attempts=" + placementRequests.attempts() + " expectedBlock=" + expectedBlock
                + " fastFinish=" + breakFastFinishRequested);
        reset();
        return Status.SUCCESS;
    }

    private Status reclassify(Minecraft mc) {
        if (type == Type.BREAK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        TerraLevelerDiagnostics.log("ACTION", "reclassify type=" + type + " target=" + target
                + " reason=break-target-became-pure-liquid");
        reset();
        return Status.RECLASSIFY;
    }

    private Status needReposition(Minecraft mc, String reason) {
        blockage = reason;
        failure = "";
        if (type == Type.BREAK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        TerraLevelerDiagnostics.log("ACTION", "need-reposition type=" + type + " target=" + target
                + " attempts=" + placementRequests.attempts() + " reason=" + reason);
        // A recoverable precondition has no request left to reconcile. Releasing
        // the claim prevents stale break/place progress while navigation moves.
        resetKeepingMessages();
        return Status.NEED_REPOSITION;
    }

    private Status fail(Minecraft mc, String reason) {
        failure = reason;
        blockage = "";
        if (type == Type.BREAK && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        TerraLevelerDiagnostics.log("ACTION", "failed type=" + type + " target=" + target + " reason=" + reason);
        resetKeepingMessages();
        return Status.FAILED;
    }

    private void reset() {
        failure = "";
        blockage = "";
        resetKeepingMessages();
    }

    private void resetKeepingMessages() {
        type = Type.NONE;
        target = null;
        placementTarget = null;
        support = null;
        item = null;
        expectedBlock = null;
        activeBreakBlock = null;
        expectation = null;
        supportExplicit = false;
        age = 0;
        breakTransactionAge = 0;
        selectedToolSlot = -1;
        breakAwaitingConfirmation = false;
        breakPredictionWaitLogged = false;
        breakFastFinishRequested = false;
        breakObservedFastProgressStreak = 0;
        placementPredictionWaitTicks = 0;
        confirmation.reset();
        breakConfirmation.reset();
        placementRequests.reset();
    }

    private PlacementObservation observePlacement(Minecraft mc) {
        BlockState state = mc.level.getBlockState(target);
        boolean exact = expectedBlock != null && matchesExpectedBlock(state, expectedBlock);
        boolean existingSeal = expectation == PlacementExpectation.FLUID_REMOVED
                && drySolidContainment(!state.getFluidState().isEmpty(),
                state.getCollisionShape(mc.level, target).isEmpty());
        boolean falling = expectedBlock instanceof FallingBlock;
        // useItemOn places the block through client prediction. Until the server
        // acknowledges that sequence the observed block may still be rolled back.
        boolean predictionPending = hasPendingBlockPrediction(mc, target)
                || placementTarget != null && !placementTarget.equals(target)
                && hasPendingBlockPrediction(mc, placementTarget);
        boolean stable = !predictionPending && (existingSeal || placementPostcondition(exact, falling,
                !falling || isStableFallingSupport(mc.level.getBlockState(target.below()))));
        boolean confirmed = confirmation.observe(mc.level.getGameTime(), stable);
        return new PlacementObservation(exact || existingSeal, confirmed, predictionPending);
    }

    static boolean placementIntersectsPlayer(AABB playerBox, BlockPos target) {
        AABB targetBox = new AABB(target.getX(), target.getY(), target.getZ(),
                target.getX() + 1.0, target.getY() + 1.0, target.getZ() + 1.0);
        return playerBox.intersects(targetBox);
    }

    static BreakPostcondition breakPostcondition(boolean air, boolean hasFluid, boolean liquidBlock) {
        if (air) return BreakPostcondition.SUCCESS;
        if (hasFluid && liquidBlock) return BreakPostcondition.RECLASSIFY;
        // In particular, a waterlogged stair/slab/fence is still a solid block
        // that must be mined; non-empty FluidState alone is never break success.
        return BreakPostcondition.KEEP_BREAKING;
    }

    static BreakPostcondition serverVerifiedBreakObservation(BreakPostcondition observed,
                                                               boolean predictionPending) {
        return predictionPending ? BreakPostcondition.KEEP_BREAKING : observed;
    }

    static boolean hasPendingBlockPrediction(Minecraft mc, BlockPos position) {
        if (mc == null || mc.level == null || position == null) return false;
        BlockStatePredictionHandler handler = ((ClientLevelSequenceAccessor) mc.level)
                .terraleveler$getBlockStatePredictionHandler();
        return ((BlockStatePredictionHandlerAccessor) (Object) handler)
                .terraleveler$getServerVerifiedStates().containsKey(position.asLong());
    }

    static BreakReachDecision breakReachDecision(double reachSquared) {
        return reachSquared <= WorkPoseRules.SAFE_REACH_SQUARED
                ? BreakReachDecision.CONTINUE
                : BreakReachDecision.NEED_REPOSITION;
    }

    static boolean requiresToolSettle(int currentSlot, int selectedToolSlot) {
        return selectedToolSlot >= 0 && currentSlot != selectedToolSlot;
    }

    static boolean shouldFastFinish(float progressPerTick) {
        // Progress >= 1 is already completed by startDestroyBlock itself.
        return progressPerTick >= FAST_FINISH_MIN_PROGRESS && progressPerTick < 1.0F;
    }

    static boolean shouldRetryRejectedBreakStart(int age, boolean accepted) {
        return age == 0 && !accepted;
    }

    static boolean shouldAwaitMissingPlacementItem(int attempts, int age) {
        return attempts > 0 && age < 80;
    }

    static boolean matchesExpectedBlock(BlockState state, Block expectedBlock) {
        return exactIdentity(state.getBlock(), expectedBlock);
    }

    static boolean exactIdentity(Object actual, Object expected) {
        return actual == expected;
    }

    static boolean placementPostcondition(boolean exactBlock, boolean fallingBlock, boolean stableSupport) {
        return exactBlock && (!fallingBlock || stableSupport);
    }

    static boolean drySolidContainment(boolean hasFluid, boolean collisionEmpty) {
        return !hasFluid && !collisionEmpty;
    }

    static boolean isStableFallingSupport(BlockState state) {
        return !FallingBlock.isFree(state);
    }

    private static boolean usableSupport(Minecraft mc, BlockPos candidate) {
        if (candidate == null) return false;
        BlockState state = mc.level.getBlockState(candidate);
        // A waterlogged solid is still a clickable support. Pure LiquidBlock
        // cells and collision-free decorations are not.
        return !(FluidBlocks.isPureFluid(state))
                && !state.getCollisionShape(mc.level, candidate).isEmpty();
    }

    private static BlockPos findSupport(Minecraft mc, BlockPos target) {
        for (Direction direction : Direction.values()) {
            BlockPos candidate = target.relative(direction);
            if (usableSupport(mc, candidate)) return candidate.immutable();
        }
        return null;
    }

    private static Direction faceFromSupportToTarget(BlockPos support, BlockPos target) {
        int dx = target.getX() - support.getX();
        int dy = target.getY() - support.getY();
        int dz = target.getZ() - support.getZ();
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == dx && direction.getStepY() == dy && direction.getStepZ() == dz) return direction;
        }
        return null;
    }

    private static boolean selectHotbarItem(Minecraft mc, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).is(item)) {
                mc.player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    static int fastestSafeTool(Minecraft mc, BlockState state, TerraLevelerConfig config,
                               boolean allowInventoryToolStreaming) {
        return fastestSafeTool(mc, state, config, allowInventoryToolStreaming, true);
    }

    static int fastestSafeTool(Minecraft mc, BlockState state, TerraLevelerConfig config,
                               boolean allowInventoryToolStreaming, boolean logSelection) {
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        int bestSlot = -1;
        float bestScore = -1.0F;
        int bestRemainingDurability = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || swordWastedOn(stack, state)) continue;
            int remainingDurability = remainingDurability(stack);
            if (stack.isDamageableItem() && remainingDurability <= config.durabilityReserve) continue;
            float score = effectiveToolScore(mc, stack, state);
            if (!isUsableToolCandidate(state, score)) continue;
            if (betterToolCandidate(score, remainingDurability, bestScore, bestRemainingDurability)) {
                bestScore = score;
                bestRemainingDurability = remainingDurability;
                bestSlot = slot;
            }
        }

        ItemStack originalStack = mc.player.getInventory().getItem(originalSlot);
        int originalRemainingDurability = remainingDurability(originalStack);
        boolean originalUnsafeEffectiveTool = !originalStack.isEmpty()
                && isEffectiveMiningTool(originalStack.getDestroySpeed(state))
                && originalStack.isDamageableItem()
                && originalRemainingDurability <= config.durabilityReserve;

        int streamedSource = -1;
        float streamedScore = -1.0F;
        int streamedRemainingDurability = -1;
        for (int slot = 9; allowInventoryToolStreaming
                && slot < Math.min(36, mc.player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || swordWastedOn(stack, state)) continue;
            int remainingDurability = remainingDurability(stack);
            if (stack.isDamageableItem() && remainingDurability <= config.durabilityReserve) continue;
            float score = effectiveToolScore(mc, stack, state);
            if (!isUsableToolCandidate(state, score)) continue;
            if (betterToolCandidate(score, remainingDurability,
                    streamedScore, streamedRemainingDurability)) {
                streamedSource = slot;
                streamedScore = score;
                streamedRemainingDurability = remainingDurability;
            }
        }
        boolean streamInventoryTool = streamedSource >= 0 && shouldStreamTool(
                streamedScore, streamedRemainingDurability,
                bestScore, bestRemainingDurability, originalUnsafeEffectiveTool);
        if (streamInventoryTool && mc.player.containerMenu == mc.player.inventoryMenu) {
            Slot sourceMenuSlot = inventoryMenuSlot(mc, streamedSource);
            if (sourceMenuSlot != null) {
                int destination = originalUnsafeEffectiveTool || bestSlot < 0 ? originalSlot : bestSlot;
                ItemStack displaced = mc.player.getInventory().getItem(destination).copy();
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                        sourceMenuSlot.index, destination, ContainerInput.SWAP, mc.player);
                TerraLevelerDiagnostics.log("TOOL", "streamed source=" + streamedSource
                        + " destination=" + destination + " score=" + streamedScore
                        + " remaining=" + streamedRemainingDurability
                        + " displaced=" + displaced
                        + " block=" + state.getBlock()
                        + " backupBecauseNoSafeHotbar=" + (bestSlot < 0)
                        + " replacedUnsafeSelected=" + originalUnsafeEffectiveTool
                        + " actionDeferred=true");
                return TOOL_STREAM_PENDING;
            }
        }
        if (shouldRescueUnsafeSelectedTool(originalUnsafeEffectiveTool, originalSlot, bestSlot)
                && mc.player.containerMenu == mc.player.inventoryMenu) {
            Slot sourceMenuSlot = inventoryMenuSlot(mc, bestSlot);
            if (sourceMenuSlot != null) {
                ItemStack safeTool = mc.player.getInventory().getItem(bestSlot).copy();
                ItemStack unsafeTool = originalStack.copy();
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                        sourceMenuSlot.index, originalSlot, ContainerInput.SWAP, mc.player);
                TerraLevelerDiagnostics.log("TOOL", "hotbar-rescue-swapped source=" + bestSlot
                        + " destination=" + originalSlot
                        + " safeTool=" + safeTool
                        + " safeRemaining=" + bestRemainingDurability
                        + " displacedUnsafe=" + unsafeTool
                        + " unsafeRemaining=" + originalRemainingDurability
                        + " block=" + state.getBlock()
                        + " actionDeferred=true");
                return TOOL_STREAM_PENDING;
            }
        }
        if (logSelection) {
            TerraLevelerDiagnostics.log("TOOL", "selected slot=" + bestSlot + " effectiveScore=" + bestScore
                    + " remaining=" + bestRemainingDurability
                    + " block=" + state.getBlock() + " originalSlot=" + originalSlot);
        }
        return bestSlot;
    }

    static boolean shouldStreamTool(float candidateScore, int candidateRemainingDurability,
                                    float bestHotbarScore, int bestHotbarRemainingDurability,
                                    boolean unsafeSelectedEffectiveTool) {
        if (unsafeSelectedEffectiveTool && !isEffectiveMiningTool(bestHotbarScore)) {
            return isEffectiveMiningTool(candidateScore);
        }
        return betterToolCandidate(candidateScore, candidateRemainingDurability,
                bestHotbarScore, bestHotbarRemainingDurability);
    }

    static boolean shouldRescueUnsafeSelectedTool(boolean unsafeSelectedEffectiveTool,
                                                  int selectedSlot, int bestSafeHotbarSlot) {
        return unsafeSelectedEffectiveTool
                && bestSafeHotbarSlot >= 0
                && selectedSlot != bestSafeHotbarSlot;
    }

    static boolean betterToolCandidate(float candidateScore, int candidateRemainingDurability,
                                       float currentScore, int currentRemainingDurability) {
        int speedComparison = Float.compare(candidateScore, currentScore);
        return speedComparison > 0
                || speedComparison == 0 && candidateRemainingDurability > currentRemainingDurability;
    }

    static int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return stack.isDamageableItem()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue())
                : Integer.MAX_VALUE;
    }

    static boolean hasSafeEffectiveTool(Minecraft mc, BlockState state, TerraLevelerConfig config) {
        if (mc == null || mc.player == null || state == null || config == null) return false;
        int size = Math.min(36, mc.player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.isDamageableItem()
                    && stack.getMaxDamage() - stack.getDamageValue() <= config.durabilityReserve) continue;
            if (isEffectiveMiningTool(stack.getDestroySpeed(state))) return true;
        }
        return false;
    }

    static ToolPreparation prepareSafeEffectiveTool(Minecraft mc, BlockState state, TerraLevelerConfig config) {
        if (mc == null || mc.player == null || mc.gameMode == null || state == null || config == null) {
            return ToolPreparation.UNAVAILABLE;
        }
        int bestHotbar = bestSafeEffectiveTool(mc, state, config, 0, 9);
        int bestInventory = bestSafeEffectiveTool(mc, state, config, 9,
                Math.min(36, mc.player.getInventory().getContainerSize()));
        ToolPreparation preparation = toolPreparation(
                bestHotbar >= 0, bestInventory >= 0, mc.player.containerMenu == mc.player.inventoryMenu);
        if (preparation != ToolPreparation.PENDING) return preparation;
        Slot sourceMenuSlot = inventoryMenuSlot(mc, bestInventory);
        if (sourceMenuSlot == null) return ToolPreparation.UNAVAILABLE;

        int destination = mc.player.getInventory().getSelectedSlot();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                sourceMenuSlot.index, destination, ContainerInput.SWAP, mc.player);
        TerraLevelerDiagnostics.log("TOOL", "cleanup-tool-swapped source=" + bestInventory
                + " destination=" + destination
                + " full-inventory-compatible=true block=" + state.getBlock()
                + " actionDeferred=true");
        return ToolPreparation.PENDING;
    }

    static ToolPreparation toolPreparation(boolean safeHotbarTool, boolean safeInventoryTool,
                                           boolean inventoryMenuActive) {
        if (safeHotbarTool) return ToolPreparation.READY;
        if (safeInventoryTool && inventoryMenuActive) return ToolPreparation.PENDING;
        return ToolPreparation.UNAVAILABLE;
    }

    private static int bestSafeEffectiveTool(Minecraft mc, BlockState state, TerraLevelerConfig config,
                                             int fromInclusive, int toExclusive) {
        int bestSlot = -1;
        float bestScore = 1.0F;
        int bestRemainingDurability = -1;
        for (int slot = fromInclusive; slot < toExclusive; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || swordWastedOn(stack, state)) continue;
            int remainingDurability = remainingDurability(stack);
            if (stack.isDamageableItem() && remainingDurability <= config.durabilityReserve) continue;
            float score = effectiveToolScore(mc, stack, state);
            if (!isUsableToolCandidate(state, score)) continue;
            if (betterToolCandidate(score, remainingDurability,
                    bestScore, bestRemainingDurability)) {
                bestScore = score;
                bestRemainingDurability = remainingDurability;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * Swords lose two durability per block and are only marginally faster on
     * leaves and plants; keep them for cobwebs and blocks they mine instantly.
     */
    static boolean swordWastedOn(ItemStack stack, BlockState state) {
        return swordWastedOn(stack.is(ItemTags.SWORDS), state.is(Blocks.COBWEB),
                state.is(BlockTags.SWORD_INSTANTLY_MINES));
    }

    static boolean swordWastedOn(boolean sword, boolean cobweb, boolean swordInstantlyMines) {
        return sword && !cobweb && !swordInstantlyMines;
    }

    static boolean isEffectiveMiningTool(float baseDestroySpeed) {
        return baseDestroySpeed > 1.0F;
    }

    static boolean requiresManagedTool(BlockState state) {
        return state != null && (state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL));
    }

    static boolean isUsableToolCandidate(BlockState state, float score) {
        return isEffectiveMiningTool(score);
    }

    static int harmlessHotbarFallback(Minecraft mc, boolean instantBreak) {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (instantBreak) return selected;
        if (!mc.player.getInventory().getItem(selected).isDamageableItem()) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (!mc.player.getInventory().getItem(slot).isDamageableItem()) return slot;
        }
        return -1;
    }

    private static int emptyHotbarFallback(Minecraft mc) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static boolean streamNonDamagingFallback(Minecraft mc) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) return false;
        int size = Math.min(36, mc.player.getInventory().getContainerSize());
        for (int source = 9; source < size; source++) {
            ItemStack stack = mc.player.getInventory().getItem(source);
            if (stack.isEmpty() || stack.isDamageableItem()) continue;
            Slot sourceMenuSlot = inventoryMenuSlot(mc, source);
            if (sourceMenuSlot == null) continue;
            int destination = mc.player.getInventory().getSelectedSlot();
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                    sourceMenuSlot.index, destination, ContainerInput.SWAP, mc.player);
            TerraLevelerDiagnostics.log("TOOL", "fallback-streamed source=" + source
                    + " destination=" + destination + " item=" + stack);
            return true;
        }
        return false;
    }

    static float toolScore(float baseDestroySpeed, int efficiencyLevel) {
        if (baseDestroySpeed <= 1.0F || efficiencyLevel <= 0) return baseDestroySpeed;
        return baseDestroySpeed + efficiencyLevel * efficiencyLevel + 1.0F;
    }

    static float deterministicToolScore(Minecraft mc, ItemStack stack, BlockState state) {
        return toolScore(stack.getDestroySpeed(state), efficiencyLevel(mc, stack));
    }

    static float effectiveToolScore(Minecraft mc, ItemStack stack, BlockState state) {
        if (mc == null || mc.player == null || stack == null || stack.isEmpty() || state == null) return 0.0F;
        float base = deterministicToolScore(mc, stack, state);
        int hasteAmplifier = MobEffectUtil.hasDigSpeed(mc.player)
                ? MobEffectUtil.getDigSpeedAmplification(mc.player) : -1;
        float fatigueMultiplier = 1.0F;
        var fatigue = mc.player.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) {
            fatigueMultiplier = switch (fatigue.getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        float blockBreakAttribute = (float) mc.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
        boolean submerged = mc.player.isEyeInFluid(FluidTags.WATER);
        AttributeInstance submergedAttribute = mc.player.getAttribute(Attributes.SUBMERGED_MINING_SPEED);
        float submergedMultiplier = submergedAttribute == null ? 1.0F : (float) submergedAttribute.getValue();
        return applyMiningModifiers(base, hasteAmplifier, fatigueMultiplier,
                blockBreakAttribute, submerged, submergedMultiplier, mc.player.onGround());
    }

    static float applyMiningModifiers(float baseScore, int hasteAmplifier,
                                      float fatigueMultiplier, float blockBreakAttribute,
                                      boolean submerged, float submergedMultiplier,
                                      boolean onGround) {
        float score = Math.max(0.0F, baseScore);
        if (hasteAmplifier >= 0) score *= 1.0F + (hasteAmplifier + 1) * 0.2F;
        score *= Math.max(0.0F, fatigueMultiplier);
        score *= Math.max(0.0F, blockBreakAttribute);
        if (submerged) score *= Math.max(0.0F, submergedMultiplier);
        if (!onGround) score /= 5.0F;
        return score;
    }

    static float candidateToolScore(float baseDestroySpeed, int efficiencyLevel,
                                    boolean currentSlot, float currentEffectiveSpeed) {
        float deterministic = toolScore(baseDestroySpeed, efficiencyLevel);
        return currentSlot ? Math.max(deterministic, currentEffectiveSpeed) : deterministic;
    }

    private static int efficiencyLevel(Minecraft mc, ItemStack stack) {
        if (mc.level == null || !stack.isEnchanted()) return 0;
        var enchantments = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return EnchantmentHelper.getItemEnchantmentLevel(
                enchantments.getOrThrow(Enchantments.EFFICIENCY), stack);
    }

    static boolean shouldDeferToolSwitch(int currentSlot, int selectedSlot, int actionAge) {
        return requiresToolSettle(currentSlot, selectedSlot) && actionAge == 0;
    }

    static boolean shouldAdoptExternalTool(int actionAge, int currentSlot,
                                           int selectedSlot, boolean currentToolSafe) {
        return actionAge > 0 && currentSlot != selectedSlot && currentToolSafe;
    }

    static boolean shouldReassertFastBreak(int actionAge, boolean fastFinishRequested,
                                           boolean externalToolSafe) {
        return actionAge > 0 && fastFinishRequested && !externalToolSafe
                && (actionAge == 1 || actionAge % 20 == 0);
    }

    static int nextObservedFastProgressStreak(int currentStreak, float progressPerTick) {
        if (!(progressPerTick >= FAST_FINISH_MIN_PROGRESS)) return 0;
        return Math.min(2, Math.max(0, currentStreak) + 1);
    }

    static boolean hasStableObservedFastProgress(int streak) {
        return streak >= 2;
    }

    static void selectAndSyncToolSlot(Minecraft mc, int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static Slot inventoryMenuSlot(Minecraft mc, int inventorySlot) {
        for (Slot candidate : mc.player.inventoryMenu.slots) {
            if (candidate.container == mc.player.getInventory()
                    && candidate.getContainerSlot() == inventorySlot) return candidate;
        }
        return null;
    }

    private record PlacementObservation(boolean exactBlockPresent, boolean confirmed, boolean predictionPending) { }

    /** Pure tick-stability tracker, package-private for regression tests. */
    static final class PlacementConfirmation {
        private final int requiredTicks;
        private long lastTick = Long.MIN_VALUE;
        private boolean lastMatched;
        private int consecutiveTicks;

        PlacementConfirmation(int requiredTicks) {
            if (requiredTicks < 1) throw new IllegalArgumentException("requiredTicks must be positive");
            this.requiredTicks = requiredTicks;
        }

        boolean observe(long gameTime, boolean matches) {
            if (gameTime == lastTick) {
                if (matches != lastMatched) {
                    lastMatched = matches;
                    consecutiveTicks = matches ? 1 : 0;
                }
                return consecutiveTicks >= requiredTicks;
            }
            consecutiveTicks = matches
                    ? (lastMatched && gameTime == lastTick + 1 ? consecutiveTicks + 1 : 1)
                    : 0;
            lastTick = gameTime;
            lastMatched = matches;
            return consecutiveTicks >= requiredTicks;
        }

        void reset() {
            lastTick = Long.MIN_VALUE;
            lastMatched = false;
            consecutiveTicks = 0;
        }
    }

    /** Requires one stable non-solid break result across distinct game ticks. */
    static final class BreakConfirmation {
        private final int requiredTicks;
        private long lastTick = Long.MIN_VALUE;
        private BreakPostcondition lastObserved = BreakPostcondition.KEEP_BREAKING;
        private int consecutiveTicks;

        BreakConfirmation(int requiredTicks) {
            if (requiredTicks < 1) throw new IllegalArgumentException("requiredTicks must be positive");
            this.requiredTicks = requiredTicks;
        }

        BreakPostcondition observe(long gameTime, BreakPostcondition observed) {
            if (gameTime == lastTick) {
                if (observed != lastObserved) {
                    lastObserved = observed;
                    consecutiveTicks = observed == BreakPostcondition.KEEP_BREAKING ? 0 : 1;
                }
            } else {
                consecutiveTicks = observed != BreakPostcondition.KEEP_BREAKING
                        ? (observed == lastObserved && gameTime == lastTick + 1 ? consecutiveTicks + 1 : 1)
                        : 0;
                lastTick = gameTime;
                lastObserved = observed;
            }
            return consecutiveTicks >= requiredTicks ? observed : BreakPostcondition.KEEP_BREAKING;
        }

        void reset() {
            lastTick = Long.MIN_VALUE;
            lastObserved = BreakPostcondition.KEEP_BREAKING;
            consecutiveTicks = 0;
        }
    }

    /** Pure request/retry scheduler, package-private for regression tests. */
    static final class PlacementRequestTracker {
        enum Decision { WAIT, SEND, EXHAUSTED }

        private final int retryTicks;
        private final int maximumAttempts;
        private int attempts;
        private long lastRequestTick = Long.MIN_VALUE;

        PlacementRequestTracker(int retryTicks, int maximumAttempts) {
            if (retryTicks < 1 || maximumAttempts < 1) {
                throw new IllegalArgumentException("retryTicks and maximumAttempts must be positive");
            }
            this.retryTicks = retryTicks;
            this.maximumAttempts = maximumAttempts;
        }

        Decision decision(long gameTime) {
            if (attempts == 0) return Decision.SEND;
            long elapsed = gameTime - lastRequestTick;
            if (elapsed >= 0 && elapsed < retryTicks) return Decision.WAIT;
            return attempts >= maximumAttempts ? Decision.EXHAUSTED : Decision.SEND;
        }

        void recordSend(long gameTime) {
            attempts++;
            lastRequestTick = gameTime;
        }

        int attempts() { return attempts; }

        void reset() {
            attempts = 0;
            lastRequestTick = Long.MIN_VALUE;
        }
    }
}
