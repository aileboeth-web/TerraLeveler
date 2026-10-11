package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutoNavigatorTest {
    @Test
    void movementNeverStopsOutsideTheControllerReachedRadius() {
        assertFalse(AutoNavigator.reachedWaypoint(.21, 0.0));
        assertTrue(AutoNavigator.horizontalSpeed(true, .21) > 0.0);
        assertTrue(AutoNavigator.reachedWaypoint(.20, 0.0));
    }

    @Test
    void ordinaryMovementSlowsNearTheCentreWithoutCreatingADeadZone() {
        assertEquals(.18, AutoNavigator.horizontalSpeed(true, 1.0));
        assertEquals(.09, AutoNavigator.horizontalSpeed(true, .20), 1.0e-9);
        assertEquals(0.0, AutoNavigator.horizontalSpeed(true, .079));
    }

    @Test
    void sprintingUsesAFasterButStillBoundedApproachSpeed() {
        assertEquals(.26, AutoNavigator.horizontalSpeed(true, 1.0, true));
        assertEquals(.11, AutoNavigator.horizontalSpeed(true, .20, true), 1.0e-9);
        assertEquals(.10, AutoNavigator.horizontalSpeed(false, 1.0, true));
    }

    @Test
    void descentKeepsMovingInsideTheOrdinaryWaypointStopRadius() {
        assertEquals(.16, AutoNavigator.descentHorizontalSpeed(true, .20));
        assertEquals(.09, AutoNavigator.descentHorizontalSpeed(false, .20));
    }

    @Test
    void descentStopsOnlyAtTheTightTargetCentreRadius() {
        assertEquals(0.0, AutoNavigator.descentHorizontalSpeed(true, .099));
        assertEquals(.16, AutoNavigator.descentHorizontalSpeed(true, .10));
    }

    @Test
    void groundedDescentAimsPastTheLandingCentreToClearTheOldLedge() {
        Vec3 east = AutoNavigator.descentAim(
                new BlockPos(0, 10, 0), new BlockPos(1, 9, 0), true);
        Vec3 north = AutoNavigator.descentAim(
                new BlockPos(0, 10, 0), new BlockPos(0, 9, -1), true);
        Vec3 west = AutoNavigator.descentAim(
                new BlockPos(0, 10, 0), new BlockPos(-1, 9, 0), true);
        Vec3 south = AutoNavigator.descentAim(
                new BlockPos(0, 10, 0), new BlockPos(0, 9, 1), true);
        assertEquals(1.75, east.x, 1.0e-9);
        assertEquals(.50, east.z, 1.0e-9);
        assertEquals(.50, north.x, 1.0e-9);
        assertEquals(-.75, north.z, 1.0e-9);
        assertEquals(-.75, west.x, 1.0e-9);
        assertEquals(.50, west.z, 1.0e-9);
        assertEquals(.50, south.x, 1.0e-9);
        assertEquals(1.75, south.z, 1.0e-9);
    }

    @Test
    void airborneDescentReturnsToTheRecordedLandingCentre() {
        Vec3 aim = AutoNavigator.descentAim(
                new BlockPos(0, 10, 0), new BlockPos(1, 9, 0), false);
        assertEquals(1.50, aim.x, 1.0e-9);
        assertEquals(.50, aim.z, 1.0e-9);
    }

    @Test
    void ascentWaitsForAlignmentAndStartsOnlyOnceOnGround() {
        BlockPos origin = new BlockPos(0, 10, 0);
        BlockPos target = new BlockPos(1, 11, 0);
        assertFalse(AutoNavigator.shouldStartJump(origin, target, -.20, .50, true));
        assertFalse(AutoNavigator.shouldStartJump(origin, target, .50, .80, true));
        assertTrue(AutoNavigator.shouldStartJump(origin, target, .50, .50, true));
        assertFalse(AutoNavigator.shouldStartJump(origin, target, .50, .50, false));
    }

    @Test
    void stepUpKeepsEnoughHorizontalImpulseToCrossTheLedgeDuringOneJumpArc() {
        assertEquals(.22, AutoNavigator.climbHorizontalSpeed(.80), 1.0e-9);
        assertEquals(.14, AutoNavigator.climbHorizontalSpeed(.20), 1.0e-9);
        assertEquals(0.0, AutoNavigator.climbHorizontalSpeed(.079), 1.0e-9);
    }
}
