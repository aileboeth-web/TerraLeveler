package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Collision-box based description of the real player's support.
 *
 * <p>A player's block position is only the cell containing their centre. Near an
 * edge that cell may be empty even though another block still supports part of
 * the player's 0.6-block-wide footprint. Every controller state uses this class
 * so route planning, fall recovery and block breaking agree about the same
 * physical situation.</p>
 */
public final class PlayerPhysicalState {
    private static final double HORIZONTAL_EPSILON = 1.0e-5;
    private static final double SUPPORT_Y_TOLERANCE = 0.08;
    /**
     * Chests, soul sand and mud stop 1/8 below the block top, farmland and dirt
     * paths 1/16. The route planner already treats them as full floors (any
     * collision is SOLID), so the stand grid must accept a player resting on them;
     * a slab (1/2 lower) is still rejected.
     */
    static final double NEAR_FULL_BLOCK_SINK = 0.13;

    private PlayerPhysicalState() { }

    /** A collision face that is physically touching the bottom of the player. */
    public record SupportSurface(BlockPos block, double topY) {
        public SupportSurface {
            block = block.immutable();
        }
    }

    /** All collision faces whose top surface currently supports the player's footprint. */
    public static List<SupportSurface> supportingSurfaces(Minecraft mc) {
        if (mc.player == null || mc.level == null) return List.of();
        AABB playerBox = mc.player.getBoundingBox();
        int minX = Mth.floor(playerBox.minX + HORIZONTAL_EPSILON);
        int maxX = Mth.floor(playerBox.maxX - HORIZONTAL_EPSILON);
        int minZ = Mth.floor(playerBox.minZ + HORIZONTAL_EPSILON);
        int maxZ = Mth.floor(playerBox.maxZ - HORIZONTAL_EPSILON);
        int topY = Mth.floor(playerBox.minY + SUPPORT_Y_TOLERANCE);
        int bottomY = Mth.floor(playerBox.minY - 2.0 - SUPPORT_Y_TOLERANCE);
        List<SupportSurface> result = new ArrayList<>();

        for (int y = bottomY; y <= topY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos block = new BlockPos(x, y, z);
                    if (!mc.level.hasChunkAt(block)) continue;
                    var shape = mc.level.getBlockState(block).getCollisionShape(mc.level, block);
                    if (shape.isEmpty()) continue;
                    for (AABB local : shape.toAabbs()) {
                        AABB worldCollision = local.move(block);
                        if (collisionSupports(playerBox, worldCollision)) {
                            result.add(new SupportSurface(block, worldCollision.maxY));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** All blocks contributing at least one real support collision face. */
    public static List<BlockPos> supportingBlocks(Minecraft mc) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (SupportSurface surface : supportingSurfaces(mc)) result.add(surface.block());
        return List.copyOf(result);
    }

    /** True when vanilla reports ground contact and a real collision surface explains it. */
    public static boolean stablySupported(Minecraft mc) {
        return mc.player != null && mc.player.onGround() && !supportingSurfaces(mc).isEmpty();
    }

    /** True when this exact block is one of the surfaces carrying the player. */
    public static boolean isSupportingBlock(Minecraft mc, BlockPos target) {
        if (target == null) return false;
        return supportingBlocks(mc).contains(target);
    }

    /**
     * Resolves the integer block layer that should become a flat finished floor.
     * Non-integer collision tops (slabs, stairs, carpets, snow layers) are
     * rejected so recording a height can never silently shift a large job.
     */
    public static OptionalInt integerGroundY(Minecraft mc) {
        if (!stablySupported(mc)) return OptionalInt.empty();
        List<SupportSurface> surfaces = supportingSurfaces(mc);
        long roundedTop = Math.round(surfaces.getFirst().topY());
        for (SupportSurface surface : surfaces) {
            if (Math.abs(surface.topY() - roundedTop) > SUPPORT_Y_TOLERANCE) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of((int) roundedTop - 1);
    }

    /**
     * Selects the safe route-grid stand cell that best represents the player's
     * actual supporting footprint. This deliberately prefers a supporting block
     * over the centre cell when the player is straddling a trench edge.
     */
    public static Optional<BlockPos> routeAnchor(Minecraft mc) {
        return nearestStand(mc, feet -> SafeRoutePlanner.canOccupy(mc, feet) && SafeRoutePlanner.hasFloor(mc, feet));
    }

    /**
     * Where a new route may start. Same as {@link #routeAnchor}, but also accepts
     * natural leaves already cut off from their logs: the player can still walk
     * off them, and the planner never steps back onto them. Without this, standing
     * on a felled tree's crown was reported as standing on a slab.
     */
    public static Optional<BlockPos> routeStart(Minecraft mc) {
        Optional<BlockPos> anchor = routeAnchor(mc);
        if (anchor.isPresent()) return anchor;
        SafeRoutePlanner.RouteEnvironment environment = SafeRoutePlanner.routeEnvironment(mc);
        return nearestStand(mc, feet -> SafeRoutePlanner.standableOrigin(environment, feet));
    }

    private static Optional<BlockPos> nearestStand(Minecraft mc, Predicate<BlockPos> standable) {
        if (mc.player == null || !mc.player.onGround()) return Optional.empty();
        List<SupportSurface> surfaces = supportingSurfaces(mc);
        if (surfaces.isEmpty()) return Optional.empty();
        List<BlockPos> candidates = new ArrayList<>();
        for (SupportSurface surface : surfaces) {
            Optional<BlockPos> routeFeet = integerRouteFeet(surface, mc.player.getY());
            if (routeFeet.isEmpty()) continue;
            BlockPos feet = routeFeet.get();
            if (standable.test(feet)) {
                candidates.add(feet.immutable());
            }
        }
        return candidates.stream().distinct().min(Comparator
                .comparingDouble((BlockPos pos) -> horizontalDistanceSquared(mc, pos))
                .thenComparingInt(pos -> Math.abs(pos.getY() - Mth.floor(mc.player.getY())))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
    }

    /**
     * Converts a real support face to the integer stand grid used by the route
     * planner. Slabs and lower stair faces are genuine support, but cannot be
     * silently rounded into a different route height.
     */
    static Optional<BlockPos> integerRouteFeet(SupportSurface surface, double playerFeetY) {
        if (Math.abs(surface.topY() - playerFeetY) > SUPPORT_Y_TOLERANCE) return Optional.empty();
        int feetY = Mth.floor(surface.topY() + .5);
        double sink = feetY - surface.topY();
        if (sink < -SUPPORT_Y_TOLERANCE || sink > NEAR_FULL_BLOCK_SINK) return Optional.empty();
        return Optional.of(new BlockPos(surface.block().getX(), feetY, surface.block().getZ()));
    }

    static boolean collisionSupports(AABB playerBox, AABB collision) {
        double overlapX = Math.min(playerBox.maxX, collision.maxX) - Math.max(playerBox.minX, collision.minX);
        double overlapZ = Math.min(playerBox.maxZ, collision.maxZ) - Math.max(playerBox.minZ, collision.minZ);
        if (overlapX <= HORIZONTAL_EPSILON || overlapZ <= HORIZONTAL_EPSILON) return false;
        return Math.abs(collision.maxY - playerBox.minY) <= SUPPORT_Y_TOLERANCE;
    }

    private static double horizontalDistanceSquared(Minecraft mc, BlockPos pos) {
        double dx = pos.getX() + .5 - mc.player.getX();
        double dz = pos.getZ() + .5 - mc.player.getZ();
        return dx * dx + dz * dz;
    }
}
