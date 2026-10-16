package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/** Threat recognition and vanilla combat scoring for the real local player. */
final class DefensiveCombat {
    enum Reason { RECENT_ATTACKER, TARGETING_PLAYER, NEARBY_HOSTILE, ROUTE_BLOCKER, PLACEMENT_BLOCKER }

    static final int ROUTE_BLOCKER_CONFIRM_TICKS = 5;
    static final double NEARBY_HOSTILE_MAX_VERTICAL_DISTANCE = 4.0;

    record Threat(LivingEntity entity, Reason reason, double distance) { }

    record WeaponChoice(int slot, String category, double damage, double speed, ItemStack stack) { }

    private DefensiveCombat() { }

    static Threat findThreat(Minecraft mc, double range, int ignoredEntityId, long ignoreUntilTick) {
        long currentTick = mc.level == null ? Long.MIN_VALUE : mc.level.getGameTime();
        return findThreat(mc, range, entityId -> entityId == ignoredEntityId && currentTick < ignoreUntilTick);
    }

    static Threat findThreat(Minecraft mc, double range, IntPredicate ignoredEntity) {
        if (mc.player == null || mc.level == null) return null;
        LivingEntity attacker = mc.player.getLastHurtByMob();
        int hurtAge = mc.player.tickCount - mc.player.getLastHurtByMobTimestamp();
        Reason attackerReason = threatReason(valid(mc, attacker, range * 1.5)
                && hurtAge >= 0 && hurtAge <= 100, false, false);
        if (attackerReason != null) {
            return new Threat(attacker, attackerReason, mc.player.distanceTo(attacker));
        }

        List<Entity> nearby = mc.level.getEntities(mc.player,
                mc.player.getBoundingBox().inflate(range), entity -> entity instanceof LivingEntity);
        return nearby.stream()
                .map(entity -> (LivingEntity) entity)
                .filter(entity -> valid(mc, entity, range))
                .filter(entity -> ignoredEntity == null || !ignoredEntity.test(entity.getId()))
                .map(entity -> classify(mc, entity, range))
                .filter(threat -> threat != null)
                .min(Comparator.comparingInt((Threat threat) -> priority(threat.reason()))
                        .thenComparingDouble(Threat::distance))
                .orElse(null);
    }

    static Threat findRouteBlocker(Minecraft mc, BlockPos waypoint, double range,
                                   int ignoredEntityId, long ignoreUntilTick) {
        long currentTick = mc.level == null ? Long.MIN_VALUE : mc.level.getGameTime();
        return findRouteBlocker(mc, waypoint, range,
                entityId -> entityId == ignoredEntityId && currentTick < ignoreUntilTick);
    }

    static Threat findRouteBlocker(Minecraft mc, BlockPos waypoint, double range,
                                   IntPredicate ignoredEntity) {
        if (mc.player == null || mc.level == null || waypoint == null) return null;
        AABB corridor = routeCorridor(mc.player.getBoundingBox(), waypoint);
        List<Entity> occupants = mc.level.getEntities(mc.player, corridor,
                entity -> entity instanceof LivingEntity);
        return occupants.stream()
                .map(entity -> (LivingEntity) entity)
                .filter(entity -> validRouteBlocker(mc, entity, range))
                .filter(entity -> ignoredEntity == null || !ignoredEntity.test(entity.getId()))
                .map(entity -> new Threat(entity, Reason.ROUTE_BLOCKER, mc.player.distanceTo(entity)))
                .min(Comparator.comparingDouble(Threat::distance))
                .orElse(null);
    }

    /** Living things inside a block cell; while one is there, placing a block in it fails. */
    static List<LivingEntity> placementOccupants(Minecraft mc, BlockPos target) {
        if (mc.player == null || mc.level == null || target == null) return List.of();
        return mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(target),
                entity -> entity != mc.player && entity.isAlive() && !entity.isSpectator());
    }

    /**
     * An occupant of a cell TerraLeveler wants to place a block in that may be killed
     * (0.9.09): what may be cleared from a route (hostile mobs), plus, when enabled, ordinary
     * animals that nobody marked as theirs (wild horses too since 0.9.10). Villagers, golems,
     * tamed pets and horses, named or leashed animals and players are never attacked; the cell
     * is placed later instead.
     */
    static Threat findPlacementBlocker(Minecraft mc, BlockPos target, double range, boolean allowAnimals,
                                       IntPredicate ignoredEntity) {
        return placementOccupants(mc, target).stream()
                .filter(entity -> validPlacementBlocker(mc, entity, range, allowAnimals))
                .filter(entity -> ignoredEntity == null || !ignoredEntity.test(entity.getId()))
                .map(entity -> new Threat(entity, Reason.PLACEMENT_BLOCKER, mc.player.distanceTo(entity)))
                .min(Comparator.comparingDouble(Threat::distance))
                .orElse(null);
    }

    static Threat refreshPlacementBlocker(Minecraft mc, LivingEntity entity, double range, boolean allowAnimals) {
        return validPlacementBlocker(mc, entity, range * 1.5, allowAnimals)
                ? new Threat(entity, Reason.PLACEMENT_BLOCKER, mc.player.distanceTo(entity))
                : null;
    }

    private static boolean validPlacementBlocker(Minecraft mc, LivingEntity entity, double range, boolean allowAnimals) {
        return hostileBlocker(mc, entity, range) || allowAnimals && animalBlocker(mc, entity, range);
    }

    /** An animal may be cleared unless it is somebody's: tamed (pets, ridden horses), named or leashed. */
    static boolean clearableAnimal(boolean tamed, boolean named, boolean leashed) {
        return !tamed && !named && !leashed;
    }

    static Threat refreshRouteBlocker(Minecraft mc, LivingEntity entity, double range) {
        return validRouteBlocker(mc, entity, range * 1.5)
                ? new Threat(entity, Reason.ROUTE_BLOCKER, mc.player.distanceTo(entity))
                : null;
    }

    static AABB routeCorridor(AABB playerBox, BlockPos waypoint) {
        double targetMinX = waypoint.getX() + .2;
        double targetMaxX = waypoint.getX() + .8;
        double targetMinY = waypoint.getY();
        double targetMaxY = waypoint.getY() + 1.8;
        double targetMinZ = waypoint.getZ() + .2;
        double targetMaxZ = waypoint.getZ() + .8;
        return new AABB(Math.min(playerBox.minX, targetMinX), Math.min(playerBox.minY, targetMinY),
                Math.min(playerBox.minZ, targetMinZ), Math.max(playerBox.maxX, targetMaxX),
                Math.max(playerBox.maxY, targetMaxY), Math.max(playerBox.maxZ, targetMaxZ))
                .inflate(.1, .1, .1);
    }

    static boolean routeBlockerConfirmed(int consecutiveTicks) {
        return consecutiveTicks >= ROUTE_BLOCKER_CONFIRM_TICKS;
    }

    static boolean routeBlockerConfirmed(int consecutiveTicks, boolean lowHealth) {
        return consecutiveTicks >= (lowHealth ? 1 : ROUTE_BLOCKER_CONFIRM_TICKS);
    }

    private static Threat classify(Minecraft mc, LivingEntity entity, double range) {
        double distance = mc.player.distanceTo(entity);
        boolean targetingPlayer = entity instanceof Mob mob
                && (mob.getTarget() == mc.player || mob.getTargetUnchecked() == mc.player);
        // A hostile that cannot be seen (in a cave behind a wall) is no threat yet and cannot
        // be hit; 0.8.74 locked onto such a zombie and stalled the job.
        boolean nearbyHostile = hostileKind(entity) && !(entity instanceof NeutralMob)
                && nearbyHostileWithinConstructionBand(mc.player.getY(), entity.getY())
                && mc.player.hasLineOfSight(entity);
        Reason reason = threatReason(false, targetingPlayer, nearbyHostile);
        return reason == null ? null : new Threat(entity, reason, distance);
    }

    /**
     * Slimes and magma cubes attack like monsters but are not {@link Monster}s, so 0.9.17 and
     * earlier only fought one after it had hit the player and never cleared one from a route
     * (10-08 test: a slime hit the player during area lighting).
     */
    static boolean hostileKind(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof Slime;
    }

    static boolean nearbyHostileWithinConstructionBand(double playerY, double entityY) {
        return Math.abs(playerY - entityY) <= NEARBY_HOSTILE_MAX_VERTICAL_DISTANCE;
    }

    static Reason threatReason(boolean recentAttacker, boolean targetingPlayer, boolean nearbyHostile) {
        if (recentAttacker) return Reason.RECENT_ATTACKER;
        if (targetingPlayer) return Reason.TARGETING_PLAYER;
        if (nearbyHostile) return Reason.NEARBY_HOSTILE;
        return null;
    }

    /**
     * Melee against a creeper makes it explode next to the player and the
     * construction; other players are never auto-attacked, even after PvP damage.
     */
    static boolean attackableTarget(boolean creeper, boolean player) {
        return !creeper && !player;
    }

    private static boolean valid(Minecraft mc, LivingEntity entity, double range) {
        return entity != null && entity != mc.player && attackableTarget(entity instanceof Creeper, entity instanceof Player)
                && entity.isAlive() && entity.isAttackable()
                && !entity.isSpectator() && !mc.player.isAlliedTo(entity)
                && mc.player.distanceToSqr(entity) <= range * range;
    }

    private static boolean validRouteBlocker(Minecraft mc, LivingEntity entity, double range) {
        // Hostile mobs are always cleared from a route; ordinary animals only when the player
        // allows it (0.9.10, setting "击杀挡路的动物"): livestock may belong to other players.
        TerraLevelerConfig config = TerraLeveler.CONFIG;
        boolean animals = config != null && config.clearBlockingAnimals;
        return hostileBlocker(mc, entity, range) || animals && animalBlocker(mc, entity, range);
    }

    private static boolean hostileBlocker(Minecraft mc, LivingEntity entity, double range) {
        return hostileKind(entity)
                && !(entity instanceof TamableAnimal)
                && !(entity instanceof AbstractHorse) && !entity.hasCustomName()
                && valid(mc, entity, range);
    }

    /** An animal nobody marked as theirs; wild horses and untamed wolves count (0.9.10). */
    private static boolean animalBlocker(Minecraft mc, LivingEntity entity, double range) {
        boolean tamed = entity instanceof TamableAnimal tamable && tamable.isTame()
                || entity instanceof AbstractHorse horse && horse.isTamed();
        return entity instanceof Animal
                && clearableAnimal(tamed, entity.hasCustomName(), entity instanceof Mob mob && mob.isLeashed())
                && valid(mc, entity, range);
    }

    /** Scores the full inventory without mutating it. Equipping is an on-demand transaction. */
    static WeaponChoice selectBestWeapon(Minecraft mc, int durabilityReserve) {
        int original = mc.player.getInventory().getSelectedSlot();
        WeaponChoice best = weaponChoice(mc, original);
        double bestScore = Double.NEGATIVE_INFINITY;
        int scanLimit = mc.player.containerMenu == mc.player.inventoryMenu
                ? Math.min(36, mc.player.getInventory().getContainerSize()) : 9;
        for (int slot = 0; slot < scanLimit; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.isDamageableItem()
                    && stack.getMaxDamage() - stack.getDamageValue() <= durabilityReserve) continue;
            ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.EMPTY);
            double damage = modifiers.compute(Attributes.ATTACK_DAMAGE,
                    mc.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), EquipmentSlot.MAINHAND);
            double speed = modifiers.compute(Attributes.ATTACK_SPEED,
                    mc.player.getAttributeBaseValue(Attributes.ATTACK_SPEED), EquipmentSlot.MAINHAND);
            String category = weaponCategory(stack);
            double score = weaponSelectionScore(category, damage, speed);
            if (score > bestScore) {
                bestScore = score;
                best = new WeaponChoice(slot, category, damage, speed, stack.copy());
            }
        }
        return best;
    }

    static WeaponChoice weaponChoice(Minecraft mc, int slot) {
        ItemStack stack = mc.player.getInventory().getItem(slot);
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double damage = modifiers.compute(Attributes.ATTACK_DAMAGE,
                mc.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), EquipmentSlot.MAINHAND);
        double speed = modifiers.compute(Attributes.ATTACK_SPEED,
                mc.player.getAttributeBaseValue(Attributes.ATTACK_SPEED), EquipmentSlot.MAINHAND);
        return new WeaponChoice(slot, weaponCategory(stack), damage, speed, stack.copy());
    }

    static String weaponCategory(ItemStack stack) {
        if (stack.is(ItemTags.SWORDS)) return "SWORD";
        if (stack.is(ItemTags.AXES)) return "AXE";
        return "OTHER";
    }

    static double weaponSelectionScore(String category, double attackDamage, double attackSpeed) {
        double categoryPriority = switch (category) {
            case "SWORD" -> 2_000_000.0;
            case "AXE" -> 1_000_000.0;
            default -> 0.0;
        };
        return categoryPriority + weaponScore(attackDamage, attackSpeed);
    }

    static double weaponScore(double attackDamage, double attackSpeed) {
        return attackDamage * Math.sqrt(Math.max(.1, attackSpeed)) + attackSpeed * .05;
    }

    static String describe(LivingEntity entity) {
        return EntityType.getKey(entity.getType()) + "#" + entity.getId();
    }

    private static int priority(Reason reason) {
        return switch (reason) {
            case RECENT_ATTACKER -> 0;
            case TARGETING_PLAYER -> 1;
            case NEARBY_HOSTILE -> 2;
            case ROUTE_BLOCKER -> 3;
            case PLACEMENT_BLOCKER -> 4;
        };
    }
}
