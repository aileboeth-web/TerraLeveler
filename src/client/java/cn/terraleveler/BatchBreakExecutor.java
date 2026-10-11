package cn.terraleveler;

import cn.terraleveler.mixin.ClientLevelSequenceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks independent fast-break requests without occupying the single slow action slot.
 * Candidate safety and ordering remain the controller's responsibility.
 */
final class BatchBreakExecutor {
    enum Preparation { READY, WAIT_FOR_TOOL, NOT_FAST, FAILED }

    record SubmitResult(int sent, int pending, String failure) {
        boolean failed() { return failure != null && !failure.isEmpty(); }
    }

    record ReconcileResult(int confirmed, int reclassified, int expired, int pending) {
        boolean changed() { return confirmed > 0 || reclassified > 0 || expired > 0; }
    }

    private static final int MAX_PENDING = 40;
    private static final int MAX_BATCH_ATTEMPTS = 2;
    private static final int PENDING_TIMEOUT_TICKS = 40;
    private static final int RETRY_COOLDOWN_TICKS = 8;

    private final Map<BlockPos, PendingBreak> pending = new LinkedHashMap<>();
    private final Map<BlockPos, Long> cooldownUntil = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> attempts = new LinkedHashMap<>();
    private String failure = "";

    Preparation prepareTool(Minecraft mc, BlockPos target, BlockState state, TerraLevelerConfig config) {
        int selected = ServerActionExecutor.fastestSafeTool(mc, state, config, true);
        if (selected == ServerActionExecutor.TOOL_STREAM_PENDING) return Preparation.WAIT_FOR_TOOL;
        if (selected < 0) {
            failure = "没有可用于批量破坏的安全工具 " + target.toShortString();
            return Preparation.FAILED;
        }
        int current = mc.player.getInventory().getSelectedSlot();
        if (ServerActionExecutor.requiresToolSettle(current, selected)) {
            ServerActionExecutor.selectAndSyncToolSlot(mc, selected);
            TerraLevelerDiagnostics.log("TOOL", "batch-switch-settle target=" + target.toShortString()
                    + " from=" + current + " to=" + selected + " block=" + state.getBlock());
            return Preparation.WAIT_FOR_TOOL;
        }
        ItemStack held = mc.player.getInventory().getSelectedItem();
        if (held.isDamageableItem()
                && held.getMaxDamage() - held.getDamageValue() <= config.durabilityReserve) {
            failure = "批量破坏前工具剩余耐久不足";
            return Preparation.FAILED;
        }
        float progress = effectiveProgress(mc, target, state, held);
        return isBatchProgress(progress) ? Preparation.READY : Preparation.NOT_FAST;
    }

    static boolean hasFastSafeHotbarTool(Minecraft mc, BlockPos target,
                                         BlockState state, TerraLevelerConfig config) {
        if (mc == null || mc.player == null || mc.level == null) return false;
        int selected = ServerActionExecutor.fastestSafeTool(mc, state, config, false, false);
        if (selected < 0) return false;
        ItemStack candidate = mc.player.getInventory().getItem(selected);
        return isBatchProgress(effectiveProgress(mc, target, state, candidate));
    }

    SubmitResult submit(Minecraft mc, List<BlockPos> candidates, int configuredBudget) {
        failure = "";
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            return new SubmitResult(0, pending.size(), "批量破坏缺少客户端世界或连接");
        }
        int budget = availableSendBudget(configuredBudget, pending.size(), MAX_PENDING);
        if (budget <= 0 || candidates.isEmpty()) return new SubmitResult(0, pending.size(), "");

        long gameTime = mc.level.getGameTime();
        int sent = 0;
        BlockPos first = null;
        BlockPos last = null;
        for (BlockPos candidate : candidates) {
            if (sent >= budget) break;
            BlockPos immutable = candidate.immutable();
            if (pending.containsKey(immutable) || onCooldown(immutable, gameTime)
                    || !mayBatchAttempt(attempts.getOrDefault(immutable, 0), MAX_BATCH_ATTEMPTS)) continue;
            BlockState state = mc.level.getBlockState(immutable);
            if (!sendFastBreak(mc, immutable)) {
                failure = "无法取得客户端预测序列，批量破坏未发送";
                break;
            }
            pending.put(immutable, new PendingBreak(state.getBlock(), gameTime));
            attempts.merge(immutable, 1, Integer::sum);
            if (first == null) first = immutable;
            last = immutable;
            sent++;
        }
        if (sent > 0) {
            TerraLevelerDiagnostics.log("BATCH_BREAK", "submitted count=" + sent
                    + " effectiveBudget=" + configuredBudget
                    + " pending=" + pending.size() + "/" + MAX_PENDING
                    + " first=" + first.toShortString() + " last=" + last.toShortString()
                    + " tool=" + mc.player.getInventory().getSelectedItem());
        }
        return new SubmitResult(sent, pending.size(), failure);
    }

    ReconcileResult reconcile(Minecraft mc) {
        if (mc.level == null) return new ReconcileResult(0, 0, 0, pending.size());
        long gameTime = mc.level.getGameTime();
        cooldownUntil.entrySet().removeIf(entry -> gameTime >= entry.getValue());

        int confirmed = 0;
        int reclassified = 0;
        int expired = 0;
        Iterator<Map.Entry<BlockPos, PendingBreak>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PendingBreak> entry = iterator.next();
            BlockPos position = entry.getKey();
            PendingBreak request = entry.getValue();
            if (!mc.level.hasChunkAt(position)) continue;
            BlockState state = mc.level.getBlockState(position);
            ServerActionExecutor.BreakPostcondition observed = ServerActionExecutor.breakPostcondition(
                    state.isAir(), !state.getFluidState().isEmpty(),
                    FluidBlocks.isPureFluid(state));
            observed = ServerActionExecutor.serverVerifiedBreakObservation(observed,
                    ServerActionExecutor.hasPendingBlockPrediction(mc, position));
            ServerActionExecutor.BreakPostcondition stable = request.confirmation.observe(gameTime, observed);
            if (stable == ServerActionExecutor.BreakPostcondition.SUCCESS) {
                iterator.remove();
                attempts.remove(position);
                confirmed++;
                continue;
            }
            if (stable == ServerActionExecutor.BreakPostcondition.RECLASSIFY) {
                iterator.remove();
                attempts.remove(position);
                reclassified++;
                continue;
            }
            boolean blockChanged = observed == ServerActionExecutor.BreakPostcondition.KEEP_BREAKING
                    && state.getBlock() != request.originalBlock;
            if (blockChanged || gameTime - request.sentTick >= PENDING_TIMEOUT_TICKS) {
                iterator.remove();
                cooldownUntil.put(position, gameTime + RETRY_COOLDOWN_TICKS);
                expired++;
            }
        }
        ReconcileResult result = new ReconcileResult(confirmed, reclassified, expired, pending.size());
        if (result.changed()) {
            TerraLevelerDiagnostics.log("BATCH_BREAK", "reconciled confirmed=" + confirmed
                    + " reclassified=" + reclassified + " expired=" + expired
                    + " pending=" + pending.size() + "/" + MAX_PENDING);
        }
        return result;
    }

    boolean isPending(BlockPos position) {
        return pending.containsKey(position);
    }

    boolean canSubmit(BlockPos position, long gameTime) {
        return !pending.containsKey(position) && !onCooldown(position, gameTime)
                && mayBatchAttempt(attempts.getOrDefault(position, 0), MAX_BATCH_ATTEMPTS);
    }

    int pendingCount() {
        return pending.size();
    }

    int availableBudget(int configuredBudget) {
        return availableSendBudget(configuredBudget, pending.size(), MAX_PENDING);
    }

    void forget(BlockPos position) {
        if (position == null) return;
        pending.remove(position);
        cooldownUntil.remove(position);
        attempts.remove(position);
    }

    String failure() {
        return failure;
    }

    void reset(String reason) {
        if (!pending.isEmpty() || !cooldownUntil.isEmpty()) {
            TerraLevelerDiagnostics.log("BATCH_BREAK", "reset reason=" + reason
                    + " pending=" + pending.size() + " cooldown=" + cooldownUntil.size());
        }
        pending.clear();
        cooldownUntil.clear();
        attempts.clear();
        failure = "";
    }

    static boolean isBatchProgress(float progressPerTick) {
        return progressPerTick >= ServerActionExecutor.FAST_FINISH_MIN_PROGRESS;
    }

    static float deterministicBreakProgress(float toolScore, float hardness, boolean correctTool) {
        if (hardness < 0.0F) return 0.0F;
        if (hardness == 0.0F) return 1.0F;
        return toolScore / hardness / (correctTool ? 30.0F : 100.0F);
    }

    static float effectiveBreakProgress(Minecraft mc, BlockPos target,
                                        BlockState state, ItemStack stack) {
        return effectiveProgress(mc, target, state, stack);
    }

    static boolean canBatchWithStack(Minecraft mc, BlockPos target,
                                     BlockState state, ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && isBatchProgress(effectiveProgress(mc, target, state, stack));
    }

    private static float effectiveProgress(Minecraft mc, BlockPos target, BlockState state,
                                           ItemStack stack) {
        float score = ServerActionExecutor.effectiveToolScore(mc, stack, state);
        float hardness = state.getDestroySpeed(mc.level, target);
        return deterministicBreakProgress(score, hardness, stack.isCorrectToolForDrops(state));
    }

    static boolean isEffectiveFastBreak(Minecraft mc, BlockPos target,
                                        BlockState state, ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && isBatchProgress(effectiveProgress(mc, target, state, stack));
    }

    static int availableSendBudget(int configuredBudget, int currentPending, int maximumPending) {
        if (configuredBudget <= 0 || maximumPending <= currentPending) return 0;
        return Math.min(configuredBudget, maximumPending - currentPending);
    }

    static int safeToolBreakBudget(ItemStack stack, int durabilityReserve) {
        if (stack == null || stack.isEmpty()) return 0;
        if (!stack.isDamageableItem()) return Integer.MAX_VALUE;
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue() - durabilityReserve);
    }

    static boolean mayBatchAttempt(int attempts, int maximumAttempts) {
        return attempts >= 0 && attempts < maximumAttempts;
    }

    private boolean onCooldown(BlockPos position, long gameTime) {
        return cooldownUntil.getOrDefault(position, Long.MIN_VALUE) > gameTime;
    }

    static boolean sendFastBreak(Minecraft mc, BlockPos position) {
        if (!(mc.level instanceof ClientLevelSequenceAccessor accessor) || mc.getConnection() == null) return false;
        sendPredicted(mc, accessor, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position);
        sendPredicted(mc, accessor, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position);
        return true;
    }

    private static void sendPredicted(Minecraft mc, ClientLevelSequenceAccessor accessor,
                                      ServerboundPlayerActionPacket.Action action, BlockPos position) {
        BlockStatePredictionHandler handler = accessor.terraleveler$getBlockStatePredictionHandler();
        try (BlockStatePredictionHandler prediction = handler.startPredicting()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    action, position, Direction.UP, prediction.currentSequence()));
        }
    }

    private static final class PendingBreak {
        private final Block originalBlock;
        private final long sentTick;
        private final ServerActionExecutor.BreakConfirmation confirmation =
                new ServerActionExecutor.BreakConfirmation(ServerActionExecutor.BREAK_CONFIRMATION_TICKS);

        private PendingBreak(Block originalBlock, long sentTick) {
            this.originalBlock = originalBlock;
            this.sentTick = sentTick;
        }
    }
}
