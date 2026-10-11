package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Final scaffold cleanup rules added in 0.8.76 after the 0.8.75 test run. */
class Improvements0876Test {
    private static final BlockPos HIGH = new BlockPos(-179, 94, -485);
    private static final BlockPos HIGH_BELOW = new BlockPos(-179, 93, -485);
    private static final BlockPos LOW_A = new BlockPos(-56, 67, -484);
    private static final BlockPos LOW_B = new BlockPos(-49, 67, -477);

    @Test
    void parkedTargetsMoveBehindTheOpenOnesAndDoneTargetsStay() {
        BlockPos done = new BlockPos(0, 70, -480);
        List<BlockPos> targets = List.of(done, HIGH, HIGH_BELOW, LOW_A, LOW_B);
        List<BlockPos> parked = CleanupParkingRules.parkRemaining(targets, 1, Set.of(HIGH, HIGH_BELOW));
        assertEquals(List.of(done, LOW_A, LOW_B, HIGH, HIGH_BELOW), parked);
        assertEquals(2, CleanupParkingRules.openRemaining(parked, 1, Set.of(HIGH, HIGH_BELOW)));
        assertEquals(0, CleanupParkingRules.openRemaining(parked, 3, Set.of(HIGH, HIGH_BELOW)));
    }

    @Test
    void parkedTargetsAreRetriedOnceOnlyAfterSomethingWasRemoved() {
        // 0.8.75 retried the same unreachable pair three times without any change in between.
        assertFalse(CleanupParkingRules.mayRetryParked(10, 10, 0));
        assertTrue(CleanupParkingRules.mayRetryParked(10, 39, 0));
        assertFalse(CleanupParkingRules.mayRetryParked(10, 39, CleanupParkingRules.MAX_PARKED_RETRIES));
    }

    @Test
    void ownStubInTheOnlyPillarColumnInsideTheAreaIsCleanedFirst() {
        // The 0.8.74 pillar left dirt at -179,63..64,-484; the target sits just outside z=-484.
        BlockPos stubLow = new BlockPos(-179, 63, -484);
        BlockPos stubHigh = new BlockPos(-179, 64, -484);
        BlockPos floor = new BlockPos(-179, 62, -484);
        BlockPos elsewhere = new BlockPos(-177, 64, -484);
        List<BlockPos> blockers = CleanupParkingRules.pillarColumnBlockers(
                HIGH, List.of(HIGH, HIGH_BELOW, stubLow, stubHigh, floor, elsewhere), 63, (x, z) -> z >= -484 && z <= -475);
        assertEquals(List.of(stubHigh, stubLow), blockers);
    }

    @Test
    void pillarIsRaisedToTheHighestScaffoldOfTheSmallGroup() {
        // 0.8.75: pillar to -18,80,-476, then a second pillar on the same base for -18,71,-476.
        BlockPos low = new BlockPos(-18, 71, -476);
        BlockPos high = new BlockPos(-18, 80, -476);
        BlockPos far = new BlockPos(-12, 90, -478);
        assertEquals(high, CleanupParkingRules.highestInGroup(low, List.of(low, high, far), pos -> true));
        assertEquals(low, CleanupParkingRules.highestInGroup(low, List.of(low, high, far), pos -> !pos.equals(high)));
        assertEquals(far, CleanupParkingRules.highestInGroup(far, List.of(low, high, far), pos -> true));
    }
}
