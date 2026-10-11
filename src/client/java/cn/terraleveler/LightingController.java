package cn.terraleveler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class LightingController {
    private static final int LIGHT_SETTLE_TICKS = 4;
    private static final int ROUTE_STUCK_TICKS = 120;
    private static final int MAX_ROUTE_RETRIES = 3;
    /** Straight-line running (0.9.19): how far ahead to look, and when a waypoint counts as passed. */
    private static final int AIM_LOOKAHEAD = 10;
    private static final double AIM_PASS_DISTANCE = .6;
    private static final int GROUND_WAIT_TICKS = 60;
    private static final SafeRoutePlanner.RoutePolicy ROUTE_POLICY = SafeRoutePlanner.RoutePolicy.flatteningFloor();

    private final ServerActionExecutor actions = new ServerActionExecutor();
    private final LightingTorchShulkerExtractor torchShulker = new LightingTorchShulkerExtractor();
    private final List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> processed = new LinkedHashSet<>();
    private LightingPlan plan;
    private State state = State.IDLE;
    private State resumeState = State.SCANNING;
    private String detail = "尚未开始";
    private int scanX;
    private int scanY;
    private int scanZ;
    private boolean scanFinished;
    private long scanned;
    private long unloaded;
    private int placed;
    private int resolvedByLight;
    private int skipped;
    private BlockPos target;
    private List<BlockPos> targetGoals = List.of();
    private List<SafeRoutePlanner.RouteStep> route = List.of();
    private int routeIndex;
    private int aimIndex = -1;
    private int groundWaitTicks;
    private int routeRetries;
    private int routeNoProgressTicks;
    private double lastRouteDistance = Double.POSITIVE_INFINITY;
    private int lightSettleTicks;
    private int actionCooldownTicks;
    private int equipSettleTicks;
    private boolean pauseAfterTorchRollback;
    private boolean stopAfterTorchRollback;
    private final LightingGuard guard = new LightingGuard();
    /** Paused by the guard for low health: resumes by itself once health recovers. */
    private boolean pausedForHealth;
    private int snapshotTicks;

    void toggle(LightingPlan requestedPlan) {
        Minecraft mc = Minecraft.getInstance();
        if (state == State.UNPACKING_TORCH) {
            pauseAfterTorchRollback = true;
            torchShulker.requestRollback("用户暂停区域照明，先安全归还火把潜影盒");
            detail = "正在安全归还火把潜影盒，完成后暂停";
            TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-pause-request");
        } else if (isRunning()) {
            resumeState = state;
            state = State.PAUSED;
            actions.cancel(mc, "lighting-user-pause");
            guard.reset(mc);
            AutoNavigator.release(mc);
            detail = "区域照明已暂停";
            TerraLevelerDiagnostics.log("LIGHTING", "pause target=" + posText(target));
        } else if (state == State.PAUSED && plan != null) {
            pausedForHealth = false;
            state = resumeState;
            detail = "区域照明正在恢复";
            TerraLevelerDiagnostics.log("LIGHTING", "resume state=" + state + " target=" + posText(target));
        } else {
            start(requestedPlan);
        }
    }

    void start(LightingPlan requestedPlan) {
        Minecraft mc = Minecraft.getInstance();
        resetRuntime(mc);
        plan = requestedPlan;
        scanX = plan.minX();
        scanY = plan.minY();
        scanZ = plan.minZ();
        state = State.SCANNING;
        resumeState = state;
        detail = "正在增量扫描三维选区内的暗面";
        TerraLevelerDiagnostics.log("LIGHTING", "scan-start bounds=" + plan.minX() + "," + plan.minY() + "," + plan.minZ()
                + ".." + plan.maxX() + "," + plan.maxY() + "," + plan.maxZ() + " volume=" + plan.volume());
    }

    void tick(Minecraft mc, TerraLevelerConfig config) {
        logSnapshot(mc);
        if (state == State.PAUSED && pausedForHealth) {
            tickHealthPause(mc, config);
            return;
        }
        if (!isRunning()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            pause(mc, "客户端世界或真实玩家暂不可用");
            return;
        }
        if (state == State.UNPACKING_TORCH) {
            // The torch box transaction keeps its own low-health rollback; the guard would
            // move or turn the player while the box is open.
            if (mc.player.getHealth() < config.minimumHealth || mc.player.isInLava()) {
                pauseAfterTorchRollback = true;
                torchShulker.requestRollback("生命值过低或进入岩浆，先安全归还火把潜影盒");
                detail = "正在安全归盒后暂停";
            }
            tickTorchShulker(mc);
            return;
        }
        LightingGuard.Outcome guarded = guard.tick(mc, config);
        if (guarded == LightingGuard.Outcome.PAUSE) {
            pause(mc, guard.pauseReason(), guard.pauseResume());
            return;
        }
        if (guarded == LightingGuard.Outcome.BUSY) {
            if (!actions.isIdle()) actions.cancel(mc, "lighting-guard");
            detail = guard.detail();
            return;
        }
        if (guard.takeInterrupted() && target != null && (state == State.POSITIONING || state == State.PLACING)) {
            // Fighting, eating or swimming moved the player (knockback too). 0.9.18 went on
            // placing from where the fight ended, failed out of reach and skipped the spot.
            route = List.of();
            routeIndex = 0;
            aimIndex = -1;
            if (actions.isIdle()) state = State.POSITIONING;
            TerraLevelerDiagnostics.log("LIGHTING", "guard-replan target=" + posText(target));
        }
        if (state == State.SCANNING) tickScanning(mc, config);
        else if (state == State.POSITIONING) tickPositioning(mc);
        else if (state == State.PLACING) tickPlacing(mc, config);
        else if (state == State.UNPACKING_TORCH) tickTorchShulker(mc);
    }

    private void tickScanning(Minecraft mc, TerraLevelerConfig config) {
        int budget = Math.max(1, config.lightingScanBlocksPerTick);
        for (int index = 0; index < budget && !scanFinished; index++) {
            BlockPos support = new BlockPos(scanX, scanY, scanZ);
            BlockPos pos = LightingPlacementRules.targetAboveScannedSupport(support);
            scanned++;
            if (!mc.level.hasChunkAt(support) || !mc.level.hasChunkAt(pos)) unloaded++;
            else if (needsTorch(mc, pos, config.lightingBlockLightThreshold)) candidates.add(pos.immutable());
            advanceScan();
        }
        detail = "扫描暗面 " + scanned + "/" + plan.volume() + "，候选 " + candidates.size();
        if (!scanFinished) return;
        TerraLevelerDiagnostics.log("LIGHTING", "scan-complete scanned=" + scanned + " candidates="
                + candidates.size() + " unloaded=" + unloaded);
        state = State.PLACING;
        resumeState = state;
        detail = "暗面扫描完成，正在选择第一处火把位置";
    }

    private void advanceScan() {
        if (++scanX <= plan.maxX()) return;
        scanX = plan.minX();
        if (++scanZ <= plan.maxZ()) return;
        scanZ = plan.minZ();
        if (++scanY <= plan.maxY()) return;
        scanFinished = true;
    }

    private void tickPositioning(Minecraft mc) {
        if (!stillNeedsTorch(mc, target)) {
            markResolvedByLight();
            return;
        }
        if (placeableFromHere(mc)) {
            // 0.9.19: place as soon as the torch is within reach instead of walking on to the
            // planned stand (which is up to three blocks closer).
            AutoNavigator.release(mc);
            route = List.of();
            routeIndex = 0;
            aimIndex = -1;
            state = State.PLACING;
            detail = "已进入火把放置距离 " + target.toShortString();
            return;
        }
        if (routeIndex >= route.size()) {
            Optional<BlockPos> anchor = PlayerPhysicalState.routeAnchor(mc);
            if (anchor.isPresent() && targetGoals.contains(anchor.get())) {
                AutoNavigator.release(mc);
                state = State.PLACING;
                detail = "已到达火把放置站位 " + target.toShortString();
            } else {
                planRoute(mc);
            }
            return;
        }

        SafeRoutePlanner.RouteStep step = route.get(routeIndex);
        SafeRoutePlanner.StepValidation validation = SafeRoutePlanner.validateStep(mc, step, plan, ROUTE_POLICY);
        if (!validation.canMoveNow()) {
            retryRoute(mc, "route-step-" + validation.status());
            return;
        }
        if (step.type() == SafeRoutePlanner.StepType.WALK) {
            if (aimIndex < routeIndex) {
                aimIndex = straightAimIndex(mc);
                if (aimIndex > routeIndex) {
                    SafeRoutePlanner.StepValidation aimValidation = SafeRoutePlanner.validateStep(mc, route.get(aimIndex), plan, ROUTE_POLICY);
                    if (!aimValidation.canMoveNow()) aimIndex = routeIndex;
                }
                lastRouteDistance = Double.POSITIVE_INFINITY;
            }
            if (aimIndex > routeIndex) {
                // Run straight at a waypoint further along. Following the route block by
                // block slowed down at every block centre and zig-zagged on diagonals
                // (0.9.18 test: about 3.7 blocks a second against 5.2 sprinting).
                BlockPos aim = route.get(aimIndex).position();
                double aimHorizontal = horizontalDistance(mc, aim);
                if (aimHorizontal < AIM_PASS_DISTANCE && Math.abs(aim.getY() - mc.player.getY()) < .75) {
                    routeIndex = aimIndex + 1;
                    aimIndex = -1;
                    routeNoProgressTicks = 0;
                    lastRouteDistance = Double.POSITIVE_INFINITY;
                    return;
                }
                if (aimHorizontal + .02 < lastRouteDistance) {
                    lastRouteDistance = aimHorizontal;
                    routeNoProgressTicks = 0;
                } else if (++routeNoProgressTicks >= ROUTE_STUCK_TICKS) {
                    retryRoute(mc, "route-no-progress");
                    return;
                }
                AutoNavigator.sprintToward(mc, aim);
                detail = "前往火把站位 " + aim.toShortString() + "（" + (aimIndex + 1) + "/" + route.size() + "）";
                return;
            }
        }
        BlockPos waypoint = step.position();
        Optional<BlockPos> anchor = PlayerPhysicalState.routeAnchor(mc);
        double horizontal = horizontalDistance(mc, waypoint);
        double vertical = Math.abs(waypoint.getY() - mc.player.getY());
        if (anchor.filter(waypoint::equals).isPresent() && AutoNavigator.reachedWaypoint(horizontal, vertical)) {
            routeIndex++;
            aimIndex = -1;
            routeNoProgressTicks = 0;
            lastRouteDistance = Double.POSITIVE_INFINITY;
            return;
        }
        double distance = horizontal + vertical * .35;
        if (distance + .02 < lastRouteDistance) {
            lastRouteDistance = distance;
            routeNoProgressTicks = 0;
        } else if (++routeNoProgressTicks >= ROUTE_STUCK_TICKS) {
            retryRoute(mc, "route-no-progress");
            return;
        }
        if (step.type() == SafeRoutePlanner.StepType.STEP_UP) {
            AutoNavigator.climbToward(mc, step.origin(), waypoint);
        } else if (step.type() == SafeRoutePlanner.StepType.STEP_DOWN) {
            AutoNavigator.descendToward(mc, step.origin(), waypoint);
        } else if (step.type() == SafeRoutePlanner.StepType.WALK) {
            AutoNavigator.sprintToward(mc, waypoint);
        } else {
            retryRoute(mc, "unsupported-step-" + step.type());
            return;
        }
        detail = "前往火把站位 " + waypoint.toShortString() + "（" + (routeIndex + 1) + "/" + route.size() + "）";
    }

    /** The torch can be placed from where the player stands now (same reach the placement checks). */
    private boolean placeableFromHere(Minecraft mc) {
        if (target == null || !actions.isIdle() || !mc.player.onGround()) return false;
        BlockPos support = target.below();
        return supportInReach(mc, support)
                && !ServerActionExecutor.placementIntersectsPlayer(mc.player.getBoundingBox(), target);
    }

    private static boolean supportInReach(Minecraft mc, BlockPos support) {
        return mc.player.getEyePosition().distanceToSqr(support.getX() + .5, support.getY() + .5, support.getZ() + .5)
                <= WorkPoseRules.SAFE_REACH_SQUARED;
    }

    /**
     * The furthest walk waypoint (at most {@link #AIM_LOOKAHEAD} ahead, never the final stand)
     * the player can run to in a straight line: same height, every cell the player's box
     * crosses is free with a floor under it. Returns routeIndex when none further is.
     */
    private int straightAimIndex(Minecraft mc) {
        int feetY = mc.player.blockPosition().getY();
        int last = Math.min(route.size() - 2, routeIndex + AIM_LOOKAHEAD);
        int best = routeIndex;
        for (int index = routeIndex; index <= last; index++) {
            SafeRoutePlanner.RouteStep step = route.get(index);
            if (step.type() != SafeRoutePlanner.StepType.WALK || step.position().getY() != feetY) break;
            // Climbs and drops start from a centred stand: only skip to a waypoint followed by a walk.
            if (index > routeIndex && route.get(index + 1).type() == SafeRoutePlanner.StepType.WALK
                    && straightWalkable(mc, step.position(), feetY)) best = index;
        }
        return best;
    }

    private static boolean straightWalkable(Minecraft mc, BlockPos to, int feetY) {
        double fromX = mc.player.getX();
        double fromZ = mc.player.getZ();
        double dx = to.getX() + .5 - fromX;
        double dz = to.getZ() + .5 - fromZ;
        int samples = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / .25));
        Set<BlockPos> checked = new java.util.HashSet<>();
        for (int sample = 0; sample <= samples; sample++) {
            double x = fromX + dx * sample / samples;
            double z = fromZ + dz * sample / samples;
            for (double offsetX = -.31; offsetX <= .31; offsetX += .62) {
                for (double offsetZ = -.31; offsetZ <= .31; offsetZ += .62) {
                    BlockPos cell = BlockPos.containing(x + offsetX, feetY, z + offsetZ);
                    if (!checked.add(cell)) continue;
                    if (!mc.level.hasChunkAt(cell) || !SafeRoutePlanner.canOccupy(mc, cell)
                            || !SafeRoutePlanner.hasFloor(mc, cell)) return false;
                }
            }
        }
        return true;
    }

    private void tickPlacing(Minecraft mc, TerraLevelerConfig config) {
        AutoNavigator.release(mc);
        if (target == null) {
            if (lightSettleTicks > 0) {
                lightSettleTicks--;
                detail = "等待服务器光照稳定（剩余 " + lightSettleTicks + " 刻）";
                return;
            }
            if (actionCooldownTicks > 0) {
                actionCooldownTicks--;
                detail = "等待下一次火把操作（剩余 " + actionCooldownTicks + " 刻）";
                return;
            }
            selectNextTarget(mc, config);
            return;
        }
        if (!stillNeedsTorch(mc, target) && actions.isIdle()) {
            markResolvedByLight();
            return;
        }
        if (actions.isIdle() && !ensureTorchInHotbar(mc)) {
            if (torchCount(mc) == 0 && equipSettleTicks == 0) {
                beginTorchShulker(mc);
            }
            return;
        }

        if (actions.isIdle() && !supportInReach(mc, target.below())) {
            // Pushed out of reach (knockback, a mob): walk back instead of failing and skipping.
            retryRoute(mc, "support-out-of-reach");
            return;
        }
        ServerActionExecutor.Status result = actions.tickPlace(mc, target, Items.TORCH, target.below(),
                ServerActionExecutor.PlacementExpectation.SOLID_BLOCK);
        if (result == ServerActionExecutor.Status.RUNNING) {
            detail = "等待服务器确认火把 " + target.toShortString();
        } else if (result == ServerActionExecutor.Status.SUCCESS) {
            BlockPos confirmed = target;
            processed.add(confirmed);
            target = null;
            placed++;
            routeRetries = 0;
            lightSettleTicks = LIGHT_SETTLE_TICKS;
            actionCooldownTicks = Math.max(0, config.operationDelayTicks);
            TerraLevelerDiagnostics.log("LIGHTING", "place-confirmed target=" + confirmed.toShortString()
                    + " placed=" + placed + " candidates=" + candidates.size());
        } else if (result == ServerActionExecutor.Status.NEED_REPOSITION) {
            actions.cancel(mc, "lighting-reposition");
            retryRoute(mc, "placement-needs-reposition");
        } else {
            String reason = actions.failure();
            actions.cancel(mc, "lighting-place-failed");
            skipTarget(mc, reason == null || reason.isBlank() ? result.name() : reason);
        }
    }

    private void selectNextTarget(Minecraft mc, TerraLevelerConfig config) {
        // Light is only re-read for the nearest remaining candidate; resolved or
        // processed candidates are dropped from the list. Re-checking every
        // candidate's light after each torch made large areas quadratic.
        candidates.removeIf(processed::contains);
        BlockPos best = null;
        while (!candidates.isEmpty()) {
            int bestIndex = 0;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < candidates.size(); index++) {
                BlockPos candidate = candidates.get(index);
                double distance = mc.player.distanceToSqr(candidate.getX() + .5, candidate.getY(), candidate.getZ() + .5);
                if (distance < bestDistance) {
                    bestIndex = index;
                    bestDistance = distance;
                }
            }
            BlockPos nearest = candidates.get(bestIndex);
            if (needsTorch(mc, nearest, config.lightingBlockLightThreshold)) {
                best = nearest;
                break;
            }
            processed.add(nearest);
            resolvedByLight++;
            int last = candidates.size() - 1;
            candidates.set(bestIndex, candidates.get(last));
            candidates.remove(last);
        }
        if (best == null) {
            complete(mc, "区域内已没有需要处理的可放置暗面");
            return;
        }
        if (torchCount(mc) == 0) {
            beginTorchShulker(mc);
            return;
        }
        target = best.immutable();
        routeRetries = 0;
        planRoute(mc);
    }

    private void planRoute(Minecraft mc) {
        if (!stillNeedsTorch(mc, target)) {
            markResolvedByLight();
            return;
        }
        Optional<BlockPos> anchor = PlayerPhysicalState.routeAnchor(mc);
        if (anchor.isEmpty()) {
            // Knockback from a fight leaves the player in the air for a moment: wait to land
            // (re-planning from POSITIONING) before giving up.
            if (++groundWaitTicks < GROUND_WAIT_TICKS) {
                AutoNavigator.release(mc);
                route = List.of();
                aimIndex = -1;
                routeIndex = 0;
                state = State.POSITIONING;
                detail = "等待站稳后重新规划路线";
                return;
            }
            groundWaitTicks = 0;
            pause(mc, "请让真实玩家稳定站在完整方块上后继续照明");
            return;
        }
        groundWaitTicks = 0;
        List<BlockPos> goals = workPoses(mc, target);
        if (goals.isEmpty()) {
            skipTarget(mc, "no-safe-placement-pose");
            return;
        }
        targetGoals = goals.size() > 32 ? List.copyOf(goals.subList(0, 32)) : List.copyOf(goals);
        SafeRoutePlanner.PlanResult result = SafeRoutePlanner.findRoute(mc, anchor.get(), targetGoals, plan, ROUTE_POLICY);
        if (result.status() == SafeRoutePlanner.PlanStatus.ALREADY_AT_GOAL) {
            route = List.of();
            aimIndex = -1;
            routeIndex = 0;
            state = State.PLACING;
            detail = "当前站位可以放置火把 " + target.toShortString();
            return;
        }
        if (!result.hasProgressRoute()) {
            skipTarget(mc, "route-" + result.status());
            return;
        }
        route = result.steps();
        aimIndex = -1;
        routeIndex = 0;
        routeNoProgressTicks = 0;
        lastRouteDistance = Double.POSITIVE_INFINITY;
        state = State.POSITIONING;
        resumeState = state;
        TerraLevelerDiagnostics.log("LIGHTING", "route target=" + target.toShortString()
                + " status=" + result.status() + " steps=" + route.size());
    }

    private List<BlockPos> workPoses(Minecraft mc, BlockPos placementTarget) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos support = placementTarget.below();
        int maximumFeetY = WorkPoseRules.maximumScanFeetY(placementTarget, support, true);
        for (int y = placementTarget.getY() - 2; y <= maximumFeetY; y++) {
            for (int x = placementTarget.getX() - 3; x <= placementTarget.getX() + 3; x++) {
                for (int z = placementTarget.getZ() - 3; z <= placementTarget.getZ() + 3; z++) {
                    BlockPos pose = new BlockPos(x, y, z);
                    if (SafeRoutePlanner.canOccupy(mc, pose) && SafeRoutePlanner.hasFloor(mc, pose)
                            && WorkPoseRules.placementAllowed(pose, placementTarget, support)) {
                        result.add(pose.immutable());
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(pos -> mc.player.distanceToSqr(
                pos.getX() + .5, pos.getY(), pos.getZ() + .5)));
        return result;
    }

    private void retryRoute(Minecraft mc, String reason) {
        AutoNavigator.release(mc);
        route = List.of();
        aimIndex = -1;
        routeIndex = 0;
        if (++routeRetries >= MAX_ROUTE_RETRIES) {
            skipTarget(mc, reason);
            return;
        }
        TerraLevelerDiagnostics.log("LIGHTING", "route-retry target=" + posText(target)
                + " attempt=" + routeRetries + " reason=" + reason);
        planRoute(mc);
    }

    private void skipTarget(Minecraft mc, String reason) {
        if (target != null) processed.add(target);
        skipped++;
        TerraLevelerDiagnostics.log("LIGHTING", "skipped target=" + posText(target) + " reason=" + reason);
        target = null;
        route = List.of();
        aimIndex = -1;
        targetGoals = List.of();
        routeIndex = 0;
        routeRetries = 0;
        actions.cancel(mc, "lighting-target-skipped");
        state = State.PLACING;
        detail = "跳过不可安全处理的暗面，继续选择下一处";
    }

    private void markResolvedByLight() {
        if (target != null) processed.add(target);
        resolvedByLight++;
        target = null;
        route = List.of();
        aimIndex = -1;
        targetGoals = List.of();
        routeIndex = 0;
        routeRetries = 0;
        state = State.PLACING;
        detail = "目标已被现有或新放置光源覆盖";
    }

    private boolean ensureTorchInHotbar(Minecraft mc) {
        Inventory inventory = mc.player.getInventory();
        int hotbar = hotbarTorchSlot(inventory);
        if (hotbar >= 0) {
            inventory.setSelectedSlot(hotbar);
            if (equipSettleTicks > 0) {
                equipSettleTicks--;
                detail = "等待火把进入快捷栏";
                return false;
            }
            return true;
        }
        if (equipSettleTicks > 0) {
            equipSettleTicks--;
            detail = "等待服务器确认火把快捷栏交换";
            return false;
        }
        int source = inventoryTorchSlot(inventory);
        if (source < 0 || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.inventoryMenu.getCarried().isEmpty()) return false;
        Slot menuSlot = inventoryMenuSlot(mc, source);
        if (menuSlot == null) return false;
        int destination = inventory.getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                destination = slot;
                break;
            }
        }
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index,
                destination, ContainerInput.SWAP, mc.player);
        inventory.setSelectedSlot(destination);
        equipSettleTicks = 2;
        TerraLevelerDiagnostics.log("LIGHTING", "torch-hotbar-swap source=" + source + " destination=" + destination);
        detail = "正在把背包火把交换到快捷栏";
        return false;
    }

    private void beginTorchShulker(Minecraft mc) {
        LightingTorchShulkerExtractor.StartResult result = torchShulker.start(mc);
        if (result == LightingTorchShulkerExtractor.StartResult.STARTED) {
            actions.cancel(mc, "lighting-torch-shulker-start");
            AutoNavigator.release(mc);
            state = State.UNPACKING_TORCH;
            resumeState = State.PLACING;
            detail = torchShulker.detail();
        } else if (result == LightingTorchShulkerExtractor.StartResult.WAITING_FOR_CLOSED_GUI) {
            AutoNavigator.release(mc);
            detail = torchShulker.detail();
        } else if (result == LightingTorchShulkerExtractor.StartResult.BLOCKED) {
            pause(mc, torchShulker.detail());
        } else {
            complete(mc, torchShulker.detail() + "，已完成可用火把范围内的区域照明");
        }
    }

    private void tickTorchShulker(Minecraft mc) {
        LightingTorchShulkerExtractor.Status result = torchShulker.tick(mc);
        detail = torchShulker.detail();
        if (result == LightingTorchShulkerExtractor.Status.RUNNING) return;

        boolean pendingPause = pauseAfterTorchRollback;
        boolean pendingStop = stopAfterTorchRollback;
        boolean safeFailure = torchShulker.safeFailure();
        String outcome = torchShulker.detail();
        torchShulker.reset();
        pauseAfterTorchRollback = false;
        stopAfterTorchRollback = false;

        if (pendingStop) {
            resetRuntime(mc);
            state = State.IDLE;
            detail = "火把潜影盒已安全处理，区域照明已紧急停止";
            TerraLevelerDiagnostics.log("LIGHTING", "stop-after-torch-shulker");
            return;
        }
        if (pendingPause) {
            state = State.PAUSED;
            resumeState = State.PLACING;
            detail = "火把潜影盒已安全归还，区域照明已暂停";
            TerraLevelerDiagnostics.log("LIGHTING", "paused-after-torch-shulker outcome=" + outcome);
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("TerraLeveler 区域照明已暂停：" + detail));
            return;
        }
        if (result == LightingTorchShulkerExtractor.Status.SUCCESS) {
            state = State.PLACING;
            resumeState = State.PLACING;
            equipSettleTicks = 0;
            detail = "已从潜影盒取出火把，继续区域照明";
            TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-resume loose=" + torchCount(mc));
            return;
        }
        if (safeFailure) {
            complete(mc, outcome + "；火把潜影盒已安全归还");
        } else {
            pause(mc, outcome);
            resumeState = State.PLACING;
        }
    }

    private boolean needsTorch(Minecraft mc, BlockPos pos, int maximumBlockLight) {
        if (mc.level == null || !mc.level.hasChunkAt(pos)) return false;
        BlockState targetState = mc.level.getBlockState(pos);
        BlockState supportState = mc.level.getBlockState(pos.below());
        boolean survives = Blocks.TORCH.defaultBlockState().canSurvive(mc.level, pos);
        return LightingPlacementRules.needsTorch(targetState.isAir(), survives,
                safeSupport(mc, pos.below(), supportState),
                mc.level.getBrightness(LightLayer.BLOCK, pos), maximumBlockLight);
    }

    private boolean stillNeedsTorch(Minecraft mc, BlockPos pos) {
        TerraLevelerConfig config = TerraLeveler.CONFIG;
        return pos != null && config != null && needsTorch(mc, pos, config.lightingBlockLightThreshold);
    }

    private static boolean safeSupport(Minecraft mc, BlockPos support, BlockState state) {
        if (mc.level.getBlockEntity(support) != null || !state.getFluidState().isEmpty()
                || state.is(BlockTags.LEAVES) || state.getCollisionShape(mc.level, support).isEmpty()) return false;
        return !state.is(Blocks.ICE) && !state.is(Blocks.PACKED_ICE) && !state.is(Blocks.BLUE_ICE)
                && !state.is(Blocks.SNOW_BLOCK) && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.FARMLAND) && !state.is(Blocks.DIRT_PATH) && !state.is(Blocks.TNT);
    }

    private static int torchCount(Minecraft mc) {
        int count = 0;
        Inventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.TORCH)) count += stack.getCount();
        }
        return count;
    }

    private static int hotbarTorchSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) if (inventory.getItem(slot).is(Items.TORCH)) return slot;
        return -1;
    }

    private static int inventoryTorchSlot(Inventory inventory) {
        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).is(Items.TORCH)) return slot;
        }
        return -1;
    }

    private static Slot inventoryMenuSlot(Minecraft mc, int inventorySlot) {
        for (Slot slot : mc.player.inventoryMenu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventorySlot) return slot;
        }
        return null;
    }

    private void complete(Minecraft mc, String reason) {
        actions.cancel(mc, "lighting-complete");
        AutoNavigator.release(mc);
        state = State.COMPLETE;
        detail = reason + "；放置 " + placed + "，光照覆盖 " + resolvedByLight
                + "，跳过 " + skipped + (unloaded > 0 ? "，未加载格 " + unloaded : "");
        TerraLevelerDiagnostics.log("LIGHTING", "complete placed=" + placed + " resolvedByLight="
                + resolvedByLight + " skipped=" + skipped + " unloaded=" + unloaded
                + " candidates=" + candidates.size() + " reason=" + reason);
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("TerraLeveler 区域照明：" + detail));
        TerraLevelerAlerts.finished(mc);
    }

    private void pause(Minecraft mc, String reason) {
        pause(mc, reason, TerraLevelerAlerts.Resume.BY_PLAYER);
    }

    private void pause(Minecraft mc, String reason, TerraLevelerAlerts.Resume resume) {
        resumeState = state;
        state = State.PAUSED;
        pausedForHealth = resume == TerraLevelerAlerts.Resume.AFTER_HEALTH;
        actions.cancel(mc, "lighting-pause");
        guard.reset(mc);
        AutoNavigator.release(mc);
        // The route is re-planned from wherever the player is when the job resumes.
        route = List.of();
        aimIndex = -1;
        routeIndex = 0;
        detail = reason;
        TerraLevelerDiagnostics.log("LIGHTING", "paused state=" + resumeState + " target=" + posText(target)
                + " resume=" + resume + " reason=" + reason);
        TerraLevelerAlerts.paused(mc, "TerraLeveler 区域照明", reason, resume);
    }

    /**
     * Once a second while a lighting job runs or waits for health. Before 0.9.17 the lighting
     * log only had placements, so what the player saw in game could not be traced.
     */
    private void logSnapshot(Minecraft mc) {
        if (!(isRunning() || state == State.PAUSED && pausedForHealth) || mc.player == null
                || !TerraLevelerDiagnostics.isEnabled() || ++snapshotTicks % 20 != 0) return;
        TerraLevelerDiagnostics.log("TICK", "lighting state=" + state
                + " player=" + String.format("%.2f,%.2f,%.2f", mc.player.getX(), mc.player.getY(), mc.player.getZ())
                + " onGround=" + mc.player.onGround()
                + " health=" + String.format("%.1f", mc.player.getHealth())
                + " food=" + mc.player.getFoodData().getFoodLevel()
                + " water=" + mc.player.isInWater() + " lava=" + mc.player.isInLava()
                + " guard=" + guard.activity()
                + " target=" + posText(target) + " route=" + routeIndex + "/" + route.size()
                + " placed=" + placed + " candidates=" + candidates.size() + " detail=" + detail);
    }

    /** Low-health pause resumes by itself once health has recovered, like the flatten job. */
    private void tickHealthPause(Minecraft mc, TerraLevelerConfig config) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        guard.tickPaused(mc, config);
        float resumeAt = ConstructionController.lowHealthResumeThreshold(config.minimumHealth, mc.player.getMaxHealth());
        detail = "生命值过低，等待回复到 " + (int) resumeAt + " 后自动继续（当前 " + (int) mc.player.getHealth() + "）";
        if (mc.player.getHealth() < resumeAt) return;
        pausedForHealth = false;
        state = resumeState;
        detail = "生命值已回复，继续区域照明";
        TerraLevelerDiagnostics.log("LIGHTING", "health-resume state=" + state + " health=" + mc.player.getHealth());
    }

    void stop() {
        Minecraft mc = Minecraft.getInstance();
        if (state == State.UNPACKING_TORCH) {
            stopAfterTorchRollback = true;
            torchShulker.requestRollback("用户紧急停止区域照明，先安全归还火把潜影盒");
            detail = "正在安全归还火把潜影盒，完成后停止";
            TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-stop-request");
            return;
        }
        resetRuntime(mc);
        state = State.IDLE;
        detail = "已紧急停止";
        TerraLevelerDiagnostics.log("LIGHTING", "stop");
    }

    void resetForNewSelection() {
        resetRuntime(Minecraft.getInstance());
        state = State.IDLE;
        detail = "角点已更新，旧照明计划已清除";
    }

    void onWorldChanged(Minecraft mc) {
        resetRuntime(mc);
        state = State.IDLE;
        detail = "等待选择照明区域";
    }

    private void resetRuntime(Minecraft mc) {
        actions.cancel(mc, "lighting-reset");
        AutoNavigator.release(mc);
        plan = null;
        candidates.clear();
        processed.clear();
        target = null;
        targetGoals = List.of();
        route = List.of();
        aimIndex = -1;
        routeIndex = 0;
        routeRetries = 0;
        routeNoProgressTicks = 0;
        lastRouteDistance = Double.POSITIVE_INFINITY;
        scanFinished = false;
        scanned = 0;
        unloaded = 0;
        placed = 0;
        resolvedByLight = 0;
        skipped = 0;
        lightSettleTicks = 0;
        actionCooldownTicks = 0;
        equipSettleTicks = 0;
        pauseAfterTorchRollback = false;
        stopAfterTorchRollback = false;
        torchShulker.reset();
        pausedForHealth = false;
        guard.reset(mc);
    }

    boolean selectionMutable() {
        return state == State.IDLE || state == State.PAUSED || state == State.COMPLETE;
    }

    boolean modeSwitchSafe() {
        return state == State.IDLE || state == State.COMPLETE;
    }

    boolean isRunning() {
        return state == State.SCANNING || state == State.POSITIONING || state == State.PLACING
                || state == State.UNPACKING_TORCH;
    }

    State state() { return state; }
    String detail() { return detail; }
    int placed() { return placed; }
    int candidates() { return candidates.size(); }
    long scanned() { return scanned; }

    private static double horizontalDistance(Minecraft mc, BlockPos pos) {
        double dx = pos.getX() + .5 - mc.player.getX();
        double dz = pos.getZ() + .5 - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String posText(BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }

    enum State {
        IDLE,
        SCANNING,
        POSITIONING,
        PLACING,
        UNPACKING_TORCH,
        PAUSED,
        COMPLETE
    }
}
