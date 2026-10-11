package cn.terraleveler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefensiveCombatTest {
    @Test
    void weaponScoreAccountsForDamageAndCooldownSpeed() {
        assertTrue(DefensiveCombat.weaponScore(8.0, 1.6) > DefensiveCombat.weaponScore(2.0, 4.0));
        assertTrue(DefensiveCombat.weaponScore(10.0, 1.0) > DefensiveCombat.weaponScore(1.0, 4.0));
    }

    @Test
    void actualWeaponsArePreferredOverMiningTools() {
        assertTrue(DefensiveCombat.weaponSelectionScore("SWORD", 7.0, 1.6)
                > DefensiveCombat.weaponSelectionScore("AXE", 20.0, 1.0));
        assertTrue(DefensiveCombat.weaponSelectionScore("AXE", 7.0, 1.0)
                > DefensiveCombat.weaponSelectionScore("OTHER", 100.0, 4.0));
    }

    @Test
    void routeBlockerMustOccupyTheMovementCorridorForSeveralTicks() {
        AABB corridor = DefensiveCombat.routeCorridor(
                new AABB(.2, 0.0, .2, .8, 1.8, .8), new BlockPos(0, 0, 2));

        assertTrue(corridor.intersects(new AABB(.25, 0.0, 1.1, .75, 1.4, 1.7)));
        assertFalse(corridor.intersects(new AABB(1.2, 0.0, 1.1, 1.8, 1.4, 1.7)));
        assertFalse(DefensiveCombat.routeBlockerConfirmed(
                DefensiveCombat.ROUTE_BLOCKER_CONFIRM_TICKS - 1));
        assertTrue(DefensiveCombat.routeBlockerConfirmed(
                DefensiveCombat.ROUTE_BLOCKER_CONFIRM_TICKS));
    }

    @Test
    void lowHealthImmediatelyEscalatesAnObservedRouteBlockerInsteadOfPausing() {
        assertTrue(DefensiveCombat.routeBlockerConfirmed(1, true));
        assertFalse(DefensiveCombat.routeBlockerConfirmed(1, false));
    }

    @Test
    void nearbyHostileIsRecognizedWithoutClientTargetSynchronization() {
        assertNull(DefensiveCombat.threatReason(false, false, false));
        assertEquals(DefensiveCombat.Reason.RECENT_ATTACKER,
                DefensiveCombat.threatReason(true, false, true));
        assertEquals(DefensiveCombat.Reason.TARGETING_PLAYER,
                DefensiveCombat.threatReason(false, true, true));
        assertEquals(DefensiveCombat.Reason.NEARBY_HOSTILE,
                DefensiveCombat.threatReason(false, false, true));
    }

    @Test
    void nearbyHostileDoesNotPullConstructionIntoADeepPit() {
        assertTrue(DefensiveCombat.nearbyHostileWithinConstructionBand(62.0, 58.0));
        assertFalse(DefensiveCombat.nearbyHostileWithinConstructionBand(66.0, 56.0));
    }
}
