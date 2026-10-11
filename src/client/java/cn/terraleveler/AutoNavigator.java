package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class AutoNavigator {
    static final double WAYPOINT_REACHED_DISTANCE = .20;
    static final double MOVEMENT_STOP_DISTANCE = .08;
    static final double DESCENT_STOP_DISTANCE = .10;
    private static boolean ownsPlayerMotion;
    private static boolean ownsSprint;

    private AutoNavigator() { }

    public static void moveToward(Minecraft mc, BlockPos target) {
        moveToward(mc, target, false, false, false);
    }

    public static void moveToward(Minecraft mc, BlockPos target, boolean jump) {
        moveToward(mc, target, jump, false, false);
    }

    public static void moveTowardPreservingView(Minecraft mc, BlockPos target, boolean jump) {
        moveToward(mc, target, jump, false, true);
    }

    public static void sprintToward(Minecraft mc, BlockPos target) {
        moveToward(mc, target, false, true, false);
    }

    public static void sprintTowardPreservingView(Minecraft mc, BlockPos target) {
        moveToward(mc, target, false, true, true);
    }

    private static void moveToward(Minecraft mc, BlockPos target, boolean jump,
                                   boolean sprint, boolean preserveView) {
        if (mc.player == null) return;
        double dx = target.getX() + 0.5 - mc.player.getX();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean sprinting = sprint && mc.player.onGround() && horizontal > .45;
        double speed = horizontalSpeed(mc.player.onGround(), horizontal, sprinting);
        if (speed == 0.0) {
            release(mc);
            return;
        }
        // Movement uses a world-space velocity vector, so excavation routes do
        // not need to overwrite the camera yaw or pitch to remain on course.
        if (!preserveView) {
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            mc.player.setYRot(yaw);
            mc.player.setXRot(0.0F);
        }
        // Never press Minecraft's global movement mappings.  Tweakeroo's
        // free-camera consumes those mappings for the camera entity, while this
        // controller must move only the real LocalPlayer.
        ownsPlayerMotion = true;
        setOwnedSprint(mc, sprinting);
        Vec3 current = mc.player.getDeltaMovement();
        double vertical = jump && mc.player.onGround() ? 0.42 : current.y;
        mc.player.setDeltaMovement(dx / horizontal * speed, vertical, dz / horizontal * speed);
    }

    static boolean reachedWaypoint(double horizontalDistance, double verticalDistance) {
        return horizontalDistance <= WAYPOINT_REACHED_DISTANCE && verticalDistance < .75;
    }

    static double horizontalSpeed(boolean onGround, double horizontalDistance) {
        return horizontalSpeed(onGround, horizontalDistance, false);
    }

    static double horizontalSpeed(boolean onGround, double horizontalDistance, boolean sprinting) {
        if (horizontalDistance < MOVEMENT_STOP_DISTANCE) return 0.0;
        double maximum = onGround ? (sprinting ? .26 : .18) : .10;
        double approach = sprinting ? .55 : .45;
        return Math.min(maximum, Math.max(.035, horizontalDistance * approach));
    }

    /**
     * Crosses a drop edge and then lets gravity settle the player on the recorded
     * lower stand cell. Descent uses its own tight centre radius so the
     * 0.6-block-wide player cannot remain hanging from the old ledge.
     */
    public static void descendToward(Minecraft mc, BlockPos origin, BlockPos target) {
        descendToward(mc, origin, target, false);
    }

    public static void descendTowardPreservingView(Minecraft mc, BlockPos origin, BlockPos target) {
        descendToward(mc, origin, target, true);
    }

    private static void descendToward(Minecraft mc, BlockPos origin, BlockPos target, boolean preserveView) {
        if (mc.player == null) return;
        setOwnedSprint(mc, false);
        // Vanilla's sneak edge protection removes horizontal movement that
        // would leave the current support block. That looks exactly like an
        // invisible wall when an automated route deliberately steps down.
        // Clear crouch on the physical LocalPlayer only; key mappings and a
        // Tweakeroo free-camera entity remain untouched.
        if (mc.player.isShiftKeyDown()) {
            mc.player.setShiftKeyDown(false);
            TerraLevelerDiagnostics.log("ROUTE", "step-down-release-sneak player="
                    + mc.player.blockPosition().toShortString() + " target=" + target.toShortString()
                    + " freecam=" + isFreeCamera(mc));
        }
        Vec3 aim = descentAim(origin, target, mc.player.onGround());
        double dx = aim.x - mc.player.getX();
        double dz = aim.z - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = descentHorizontalSpeed(mc.player.onGround(), horizontal);
        if (speed == 0.0) {
            ownsPlayerMotion = true;
            Vec3 current = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, current.y, 0.0);
            return;
        }
        if (!preserveView) {
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot(0.0F);
        }
        ownsPlayerMotion = true;
        Vec3 current = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(dx / horizontal * speed, current.y, dz / horizontal * speed);
    }

    static double descentHorizontalSpeed(boolean onGround, double horizontalDistance) {
        if (horizontalDistance < DESCENT_STOP_DISTANCE) return 0.0;
        return onGround ? .16 : .09;
    }

    static Vec3 descentAim(BlockPos origin, BlockPos target, boolean onGround) {
        double x = target.getX() + .5;
        double z = target.getZ() + .5;
        if (onGround && origin != null) {
            int dx = Integer.signum(target.getX() - origin.getX());
            int dz = Integer.signum(target.getZ() - origin.getZ());
            if (Math.abs(dx) + Math.abs(dz) == 1) {
                x += dx * .25;
                z += dz * .25;
            }
        }
        return new Vec3(x, target.getY() + .5, z);
    }

    public static void lookAt(Minecraft mc, BlockPos target) {
        lookAt(mc, Vec3.atCenterOf(target));
    }

    public static void lookAt(Minecraft mc, Vec3 target) {
        if (mc.player == null) return;
        double dx = target.x - mc.player.getEyePosition().x;
        double dy = target.y - mc.player.getEyePosition().y;
        double dz = target.z - mc.player.getEyePosition().z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    public static void swimUp(Minecraft mc) {
        if (mc.player == null) return;
        setOwnedSprint(mc, false);
        ownsPlayerMotion = true;
        Vec3 movement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(movement.x * 0.35, Math.max(0.18, movement.y), movement.z * 0.35);
    }

    public static void swimToward(Minecraft mc, BlockPos target) {
        if (mc.player == null) return;
        setOwnedSprint(mc, false);
        double dx = target.getX() + 0.5 - mc.player.getX();
        double dy = target.getY() + 0.5 - mc.player.getY();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        ownsPlayerMotion = true;
        double horizontalSpeed = horizontal < 0.08 ? 0.0 : 0.12;
        double vertical = Math.max(-0.14, Math.min(0.16, dy * 0.12));
        mc.player.setDeltaMovement(horizontal < 0.08 ? 0.0 : dx / horizontal * horizontalSpeed,
                vertical, horizontal < 0.08 ? 0.0 : dz / horizontal * horizontalSpeed);
    }

    /**
     * Keeps the real player centred over one column and optionally begins one
     * vanilla jump. The controller waits until the player's collision box has
     * cleared the old feet cell before issuing the normal right-click placement.
     */
    public static void holdPillarColumn(Minecraft mc, boolean beginJump) {
        if (mc.player == null) return;
        setOwnedSprint(mc, false);
        ownsPlayerMotion = true;
        Vec3 movement = mc.player.getDeltaMovement();
        double vertical = beginJump && mc.player.onGround() ? .42 : movement.y;
        mc.player.setDeltaMovement(0.0, vertical, 0.0);
    }

    public static void release(Minecraft mc) {
        if (ownsPlayerMotion && mc.player != null) {
            Vec3 movement = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, movement.y, 0.0);
        }
        setOwnedSprint(mc, false);
        ownsPlayerMotion = false;
    }

    public static void climbToward(Minecraft mc, BlockPos origin, BlockPos target) {
        climbToward(mc, origin, target, false);
    }

    public static void climbTowardPreservingView(Minecraft mc, BlockPos origin, BlockPos target) {
        climbToward(mc, origin, target, true);
    }

    private static void climbToward(Minecraft mc, BlockPos origin, BlockPos target, boolean preserveView) {
        if (mc.player == null) return;
        boolean startJump = shouldStartJump(origin, target, mc.player.getX(), mc.player.getZ(),
                mc.player.onGround());
        if (startJump) {
            TerraLevelerDiagnostics.log("ROUTE", String.format(
                    "step-up-jump origin=%s target=%s player=%.3f,%.3f,%.3f freecam=%s",
                    origin.toShortString(), target.toShortString(), mc.player.getX(), mc.player.getY(),
                    mc.player.getZ(), isFreeCamera(mc)));
        }
        // A one-block ledge cancels horizontal collision movement until the
        // player's feet rise above its top. Ordinary airborne speed (.10) then
        // leaves too few ticks to cross the block boundary before landing. Keep
        // a dedicated climb impulse for the whole arc, as mature path executors
        // do for STEP_UP rather than treating it as an ordinary walk waypoint.
        climbArcToward(mc, target, startJump, preserveView);
    }

    private static void climbArcToward(Minecraft mc, BlockPos target, boolean startJump, boolean preserveView) {
        setOwnedSprint(mc, false);
        double dx = target.getX() + .5 - mc.player.getX();
        double dz = target.getZ() + .5 - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = climbHorizontalSpeed(horizontal);
        if (speed == 0.0) {
            release(mc);
            return;
        }
        if (!preserveView) {
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot(0.0F);
        }
        ownsPlayerMotion = true;
        Vec3 current = mc.player.getDeltaMovement();
        double vertical = startJump ? .42 : current.y;
        mc.player.setDeltaMovement(dx / horizontal * speed, vertical, dz / horizontal * speed);
    }

    static double climbHorizontalSpeed(double horizontalDistance) {
        if (horizontalDistance < MOVEMENT_STOP_DISTANCE) return 0.0;
        return Math.min(.22, Math.max(.14, horizontalDistance * .55));
    }

    static boolean shouldStartJump(BlockPos origin, BlockPos target,
                                   double playerX, double playerZ, boolean onGround) {
        if (!onGround || origin == null || target == null) return false;
        int xAxis = Math.abs(target.getX() - origin.getX());
        int zAxis = Math.abs(target.getZ() - origin.getZ());
        if (xAxis + zAxis != 1) return false;
        double along = xAxis * Math.abs(target.getX() + .5 - playerX)
                + zAxis * Math.abs(target.getZ() + .5 - playerZ);
        double side = zAxis * Math.abs(target.getX() + .5 - playerX)
                + xAxis * Math.abs(target.getZ() + .5 - playerZ);
        return along <= 1.20 && side <= .22;
    }

    public static boolean isFreeCamera(Minecraft mc) {
        return mc.player != null && mc.getCameraEntity() != null && mc.getCameraEntity() != mc.player;
    }

    private static void setOwnedSprint(Minecraft mc, boolean sprinting) {
        if (mc.player == null) {
            ownsSprint = false;
            return;
        }
        if (sprinting) {
            mc.player.setSprinting(true);
            ownsSprint = true;
        } else if (ownsSprint) {
            mc.player.setSprinting(false);
            ownsSprint = false;
        }
    }
}
