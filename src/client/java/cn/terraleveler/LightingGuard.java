package cn.terraleveler;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Survival protection for area lighting (0.9.16): before this the lighting job only paused on
 * low health, so a zombie could keep hitting the player, hunger was ignored and a fall into
 * water or lava was not noticed. Deliberately simpler than the flatten controller: fight only
 * what is within reach (no chasing), eat ordinary safe food, swim to the nearest dry bank,
 * pause in lava.
 */
final class LightingGuard {
    enum Outcome { NONE, BUSY, PAUSE }

    private static final int WATER_ESCAPE_RADIUS = 32;
    private static final int WATER_TIMEOUT_TICKS = 500;
    private static final int WATER_REPLAN_TICKS = 40;
    private static final int DRY_CONFIRM_TICKS = 10;
    private static final int EAT_TIMEOUT_TICKS = 300;
    private static final int EAT_RETRY_TICKS = 10;
    private static final int NO_FOOD_RECHECK_TICKS = 1200;

    private String detail = "";
    private String pauseReason = "";
    private TerraLevelerAlerts.Resume pauseResume = TerraLevelerAlerts.Resume.BY_PLAYER;
    private boolean interruptedWork;

    private boolean inWaterRecovery;
    private int waterTicks;
    private int waterReplanTicks;
    private int dryTicks;
    private BlockPos waterExit;
    private List<BlockPos> waterWaypoints = List.of();
    private int waterWaypointIndex;

    private boolean eating;
    private int eatTicks;
    private int eatRetryTicks;
    private int eatPreviousSlot = -1;
    private int eatSettleTicks;
    private String eatItem = "none";
    private long noFoodUntilTick = Long.MIN_VALUE;

    private static final int LAVA_ESCAPE_TICKS = 100;
    private BlockPos lastSafeStand;
    private boolean lavaEscape;
    private int lavaTicks;

    Outcome tick(Minecraft mc, TerraLevelerConfig config) {
        rememberSafeStand(mc);
        if (lavaEscape || mc.player.isInLava()) return tickLava(mc);
        if (inWaterRecovery || mc.player.isInWater()) {
            Outcome water = tickWater(mc);
            if (water != Outcome.NONE) return water;
        }
        if (config.autoDefend && tickCombat(mc, config)) return Outcome.BUSY;
        if (eating) return tickEating(mc, config);
        if (config.autoEat && mc.player.onGround() && mc.level.getGameTime() >= noFoodUntilTick
                && ConstructionController.shouldBeginEating(mc.player.getFoodData().getFoodLevel(),
                mc.player.getHealth(), mc.player.getMaxHealth(), config.minimumHealth, false)) {
            if (beginEating(mc)) return Outcome.BUSY;
        }
        if (mc.player.getHealth() < config.minimumHealth) {
            return pause("生命值低于安全警戒值", TerraLevelerAlerts.Resume.AFTER_HEALTH);
        }
        return Outcome.NONE;
    }

    /**
     * While a low-health pause waits for health: still fight what comes within reach and eat
     * (natural regeneration needs a full hunger bar), never walk. 0.9.16 stood still and took
     * the hits while paused.
     */
    void tickPaused(Minecraft mc, TerraLevelerConfig config) {
        if (config.autoDefend && tickCombat(mc, config)) return;
        if (eating) {
            tickEating(mc, config);
            return;
        }
        if (config.autoEat && mc.player.onGround() && mc.level.getGameTime() >= noFoodUntilTick
                && ConstructionController.shouldBeginEating(mc.player.getFoodData().getFoodLevel(),
                mc.player.getHealth(), mc.player.getMaxHealth(), config.minimumHealth, false)) {
            beginEating(mc);
        }
    }

    // ---- lava ---------------------------------------------------------------------------

    private void rememberSafeStand(Minecraft mc) {
        if (!mc.player.isInLava() && !mc.player.isInWater() && PlayerPhysicalState.stablySupported(mc)) {
            lastSafeStand = mc.player.blockPosition().immutable();
        }
    }

    /**
     * 0.9.16 paused while still standing in the lava. Climb back towards the last dry stand
     * for up to 5 seconds, then pause either way: a lava fall needs the player to look at
     * health and gear before the job goes on.
     */
    private Outcome tickLava(Minecraft mc) {
        if (!lavaEscape) {
            if (eating) finishEating(mc, "lava");
            if (inWaterRecovery) endWater(mc);
            lavaEscape = true;
            lavaTicks = 0;
            interruptedWork = true;
            TerraLevelerDiagnostics.log("LIGHTING", "guard-lava-start player=" + mc.player.blockPosition().toShortString()
                    + " safe=" + (lastSafeStand == null ? "none" : lastSafeStand.toShortString()) + " health=" + mc.player.getHealth());
        }
        lavaTicks++;
        if (!mc.player.isInLava() && PlayerPhysicalState.stablySupported(mc)) {
            lavaEscape = false;
            AutoNavigator.release(mc);
            TerraLevelerDiagnostics.log("LIGHTING", "guard-lava-escaped ticks=" + lavaTicks + " health=" + mc.player.getHealth());
            return pause("掉进岩浆后已爬出，请检查血量和装备", TerraLevelerAlerts.Resume.BY_PLAYER);
        }
        if (lavaTicks > LAVA_ESCAPE_TICKS) {
            lavaEscape = false;
            AutoNavigator.release(mc);
            TerraLevelerDiagnostics.log("LIGHTING", "guard-lava-timeout health=" + mc.player.getHealth());
            return pause("掉进岩浆，5 秒内没能爬出", TerraLevelerAlerts.Resume.BY_PLAYER);
        }
        if (lastSafeStand == null) {
            AutoNavigator.swimUp(mc);
        } else if (mc.player.isInLava()) {
            AutoNavigator.swimToward(mc, lastSafeStand);
        } else {
            AutoNavigator.moveToward(mc, lastSafeStand, false);
        }
        detail = "掉进岩浆：爬回 " + (lastSafeStand == null ? "上方" : lastSafeStand.toShortString());
        return Outcome.BUSY;
    }

    // ---- combat -------------------------------------------------------------------------

    /** True while a threat within reach is being fought; farther threats are left to come closer. */
    private boolean tickCombat(Minecraft mc, TerraLevelerConfig config) {
        DefensiveCombat.Threat threat = DefensiveCombat.findThreat(mc, config.defenseRange, null);
        if (threat == null) return false;
        LivingEntity target = threat.entity();
        int slot = bestHotbarWeapon(mc, config.durabilityReserve);
        ItemStack weapon = mc.player.getInventory().getItem(slot);
        if (!mc.player.isWithinAttackRange(weapon, target.getBoundingBox(), 0.0) || !mc.player.hasLineOfSight(target)) {
            return false;
        }
        if (eating) finishEating(mc, "combat");
        interruptedWork = true;
        AutoNavigator.release(mc);
        AutoNavigator.lookAt(mc, target.getEyePosition());
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
            detail = "切换武器，准备反击 " + target.getName().getString();
            return true;
        }
        if (mc.player.getAttackStrengthScale(0.0F) >= 0.9F) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            TerraLevelerDiagnostics.log("LIGHTING", "guard-attack target=" + DefensiveCombat.describe(target)
                    + " reason=" + threat.reason() + " distance=" + String.format("%.2f", threat.distance())
                    + " slot=" + slot + " targetHealth=" + String.format("%.1f", target.getHealth()));
        }
        detail = "暂停放火把，反击 " + target.getName().getString();
        return true;
    }

    private static int bestHotbarWeapon(Minecraft mc, int durabilityReserve) {
        Inventory inventory = mc.player.getInventory();
        int best = inventory.getSelectedSlot();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= durabilityReserve) continue;
            DefensiveCombat.WeaponChoice choice = DefensiveCombat.weaponChoice(mc, slot);
            double score = DefensiveCombat.weaponSelectionScore(choice.category(), choice.damage(), choice.speed());
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    // ---- eating -------------------------------------------------------------------------

    private boolean beginEating(Minecraft mc) {
        Inventory inventory = mc.player.getInventory();
        int hotbar = foodSlot(inventory, 0, 9);
        if (hotbar < 0) {
            int source = foodSlot(inventory, 9, Math.min(36, inventory.getContainerSize()));
            if (source < 0 || !swapIntoHotbar(mc, source)) {
                noFoodUntilTick = mc.level.getGameTime() + NO_FOOD_RECHECK_TICKS;
                TerraLevelerDiagnostics.log("LIGHTING", "guard-no-food hunger=" + mc.player.getFoodData().getFoodLevel() + "/20");
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "TerraLeveler 区域照明：饿了，但背包里没有普通安全食物，继续照明（1 分钟后再检查）。"));
                return false;
            }
            eatSettleTicks = 2;
        }
        eating = true;
        eatTicks = 0;
        eatRetryTicks = 0;
        eatPreviousSlot = inventory.getSelectedSlot();
        interruptedWork = true;
        AutoNavigator.release(mc);
        TerraLevelerDiagnostics.log("LIGHTING", "guard-eat-start hunger=" + mc.player.getFoodData().getFoodLevel()
                + "/20 health=" + mc.player.getHealth());
        detail = "饱食度不足，开始进食";
        return true;
    }

    private Outcome tickEating(Minecraft mc, TerraLevelerConfig config) {
        AutoNavigator.release(mc);
        eatTicks++;
        int food = mc.player.getFoodData().getFoodLevel();
        if (food >= 20 || !config.autoEat) {
            finishEating(mc, food >= 20 ? "full" : "disabled");
            return Outcome.NONE;
        }
        if (eatTicks > EAT_TIMEOUT_TICKS) {
            finishEating(mc, "timeout");
            return pause("自动进食超过 15 秒仍未完成", TerraLevelerAlerts.Resume.BY_PLAYER);
        }
        if (eatSettleTicks > 0) {
            eatSettleTicks--;
            detail = "食物正在换入快捷栏";
            return Outcome.BUSY;
        }
        if (mc.player.isUsingItem()) {
            mc.options.keyUse.setDown(true);
            detail = "饱食度 " + food + "/20，正在进食 " + eatItem;
            return Outcome.BUSY;
        }
        mc.options.keyUse.setDown(false);
        if (eatRetryTicks > 0) {
            eatRetryTicks--;
            return Outcome.BUSY;
        }
        Inventory inventory = mc.player.getInventory();
        int slot = foodSlot(inventory, 0, 9);
        if (slot < 0) {
            finishEating(mc, "no-food-left");
            return Outcome.NONE;
        }
        inventory.setSelectedSlot(slot);
        eatItem = BuiltInRegistries.ITEM.getKey(inventory.getItem(slot).getItem()).toString();
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(mc.player.isUsingItem());
        eatRetryTicks = EAT_RETRY_TICKS;
        detail = "饱食度 " + food + "/20，开始进食 " + eatItem;
        return Outcome.BUSY;
    }

    private void finishEating(Minecraft mc, String reason) {
        mc.options.keyUse.setDown(false);
        if (mc.player != null && eatPreviousSlot >= 0 && eatPreviousSlot < 9) {
            mc.player.getInventory().setSelectedSlot(eatPreviousSlot);
        }
        TerraLevelerDiagnostics.log("LIGHTING", "guard-eat-finish reason=" + reason + " item=" + eatItem
                + " hunger=" + (mc.player == null ? "?" : mc.player.getFoodData().getFoodLevel()) + "/20 ticks=" + eatTicks);
        eating = false;
        eatPreviousSlot = -1;
        eatItem = "none";
    }

    private static int foodSlot(Inventory inventory, int from, int to) {
        int best = -1;
        int bestNutrition = -1;
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (stack.isEmpty() || food == null || !ConstructionController.isSafeOrdinaryFood(stack)) continue;
            if (food.nutrition() > bestNutrition) {
                best = slot;
                bestNutrition = food.nutrition();
            }
        }
        return best;
    }

    private static boolean swapIntoHotbar(Minecraft mc, int source) {
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return false;
        Slot menuSlot = null;
        for (Slot slot : mc.player.inventoryMenu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == source) menuSlot = slot;
        }
        if (menuSlot == null) return false;
        Inventory inventory = mc.player.getInventory();
        int destination = inventory.getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                destination = slot;
                break;
            }
        }
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot.index, destination,
                ContainerInput.SWAP, mc.player);
        TerraLevelerDiagnostics.log("LIGHTING", "guard-food-hotbar-swap source=" + source + " destination=" + destination);
        return true;
    }

    // ---- water --------------------------------------------------------------------------

    private Outcome tickWater(Minecraft mc) {
        if (!inWaterRecovery) {
            if (eating) finishEating(mc, "water");
            inWaterRecovery = true;
            interruptedWork = true;
            waterTicks = 0;
            waterReplanTicks = 0;
            dryTicks = 0;
            waterExit = null;
            waterWaypoints = List.of();
            waterWaypointIndex = 0;
            TerraLevelerDiagnostics.log("LIGHTING", "guard-water-start player=" + mc.player.blockPosition().toShortString());
        }
        if (++waterTicks > WATER_TIMEOUT_TICKS) {
            endWater(mc);
            return pause("落水后 25 秒仍未找到安全干岸", TerraLevelerAlerts.Resume.BY_PLAYER);
        }
        if (!mc.player.isInWater()) {
            if (PlayerPhysicalState.stablySupported(mc)) {
                AutoNavigator.release(mc);
                if (++dryTicks >= DRY_CONFIRM_TICKS) {
                    TerraLevelerDiagnostics.log("LIGHTING", "guard-water-done player=" + mc.player.blockPosition().toShortString()
                            + " ticks=" + waterTicks);
                    endWater(mc);
                    return Outcome.NONE;
                }
            } else {
                dryTicks = 0;
                if (waterExit != null) AutoNavigator.moveToward(mc, waterExit, false);
            }
            detail = "已离水，确认站稳后继续照明";
            return Outcome.BUSY;
        }
        dryTicks = 0;
        if (--waterReplanTicks <= 0) {
            planWaterEscape(mc);
            waterReplanTicks = WATER_REPLAN_TICKS;
        }
        if (waterExit == null) {
            AutoNavigator.swimUp(mc);
            detail = "落水：上浮并寻找干岸（" + waterTicks + "/" + WATER_TIMEOUT_TICKS + "）";
            return Outcome.BUSY;
        }
        while (waterWaypointIndex < waterWaypoints.size() && reached(mc, waterWaypoints.get(waterWaypointIndex))) {
            waterWaypointIndex++;
        }
        BlockPos next = waterWaypointIndex < waterWaypoints.size() ? waterWaypoints.get(waterWaypointIndex) : waterExit;
        AutoNavigator.swimToward(mc, next);
        detail = "落水：游向干岸 " + waterExit.toShortString() + "（" + waterTicks + "/" + WATER_TIMEOUT_TICKS + "）";
        return Outcome.BUSY;
    }

    private void planWaterEscape(Minecraft mc) {
        WaterEscapePlanner.EscapePlan escape = WaterEscapePlanner.nearestDryEscape(new WaterEscapePlanner.Environment() {
            @Override
            public boolean loaded(BlockPos position) {
                return mc.level.hasChunkAt(position);
            }

            @Override
            public boolean safeDryStand(BlockPos feet) {
                return SafeRoutePlanner.canOccupy(mc, feet) && SafeRoutePlanner.hasFloor(mc, feet)
                        && mc.level.getFluidState(feet).isEmpty() && mc.level.getFluidState(feet.above()).isEmpty();
            }

            @Override
            public boolean water(BlockPos position) {
                return mc.level.getFluidState(position).is(FluidTags.WATER);
            }

            @Override
            public boolean passable(BlockPos position) {
                if (!mc.level.hasChunkAt(position)) return false;
                BlockState state = mc.level.getBlockState(position);
                return state.getCollisionShape(mc.level, position).isEmpty()
                        && (state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER));
            }
        }, mc.player.blockPosition(), WATER_ESCAPE_RADIUS).orElse(null);
        waterExit = escape == null ? null : escape.bank();
        waterWaypoints = escape == null ? List.of() : escape.waterWaypoints();
        waterWaypointIndex = 0;
        TerraLevelerDiagnostics.log("LIGHTING", "guard-water-plan exit=" + (waterExit == null ? "none" : waterExit.toShortString())
                + " waypoints=" + waterWaypoints.size());
    }

    private static boolean reached(Minecraft mc, BlockPos waypoint) {
        double dx = waypoint.getX() + 0.5 - mc.player.getX();
        double dz = waypoint.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz < 0.36 && Math.abs(waypoint.getY() - mc.player.getY()) < 1.2;
    }

    private void endWater(Minecraft mc) {
        AutoNavigator.release(mc);
        inWaterRecovery = false;
        waterExit = null;
        waterWaypoints = List.of();
    }

    // ---- shared -------------------------------------------------------------------------

    private Outcome pause(String reason, TerraLevelerAlerts.Resume resume) {
        pauseReason = reason;
        pauseResume = resume;
        return Outcome.PAUSE;
    }

    /** Releases keys and movement this guard holds; called on pause, stop and job reset. */
    void reset(Minecraft mc) {
        if (eating) finishEating(mc, "reset");
        if (inWaterRecovery) endWater(mc);
        if (lavaEscape) AutoNavigator.release(mc);
        lavaEscape = false;
        interruptedWork = false;
        noFoodUntilTick = Long.MIN_VALUE;
    }

    /** Short state for the lighting snapshot log. */
    String activity() {
        return lavaEscape ? "lava" : inWaterRecovery ? "water" : eating ? "eating" : "none";
    }

    /** True once after the guard moved or turned the player: the job re-plans its route. */
    boolean takeInterrupted() {
        boolean value = interruptedWork;
        interruptedWork = false;
        return value;
    }

    String detail() { return detail; }
    String pauseReason() { return pauseReason; }
    TerraLevelerAlerts.Resume pauseResume() { return pauseResume; }
}
