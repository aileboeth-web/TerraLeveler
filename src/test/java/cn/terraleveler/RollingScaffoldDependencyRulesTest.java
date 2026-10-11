package cn.terraleveler;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RollingScaffoldDependencyRulesTest {
    @Test
    void retirementPreservesSupportNeededByTheNextScaffoldUp() {
        BlockPos oldFeet = new BlockPos(-132, 73, -441);
        BlockPos oldSupport = oldFeet.below();
        BlockPos bridgeFeet = new BlockPos(-132, 73, -442);
        BlockPos upperFeet = new BlockPos(-132, 74, -441);
        var route = List.of(
                new SafeRoutePlanner.RouteStep(oldFeet, bridgeFeet, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(bridgeFeet, upperFeet, SafeRoutePlanner.StepType.SCAFFOLD_UP, null));

        assertTrue(RouteExecutionRules.futurePlacementDependsOnSupport(route, 0, oldSupport));
        assertFalse(RouteExecutionRules.futurePlacementDependsOnSupport(route, 1, oldSupport));
    }

    @Test
    void anAlreadySupportedFutureStepDoesNotBlockRollingRetirement() {
        BlockPos oldFeet = new BlockPos(8, 70, 8);
        BlockPos oldSupport = oldFeet.below();
        BlockPos bridgeFeet = new BlockPos(8, 70, 9);
        BlockPos supportedUpperFeet = new BlockPos(8, 71, 8);
        var route = List.of(
                new SafeRoutePlanner.RouteStep(oldFeet, bridgeFeet, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(bridgeFeet, supportedUpperFeet, SafeRoutePlanner.StepType.SCAFFOLD_UP, null));

        assertFalse(RouteExecutionRules.futureRequiredPlacementDependsOnSupport(
                route, 0, oldSupport, Set.of()));
    }

    @Test
    void fullBudgetRejectsAStairBridgeThatNeedsTwoPersistentRollingSupports() {
        BlockPos start = new BlockPos(0, 62, 0);
        BlockPos firstUpper = new BlockPos(0, 63, 1);
        BlockPos naturalShelf = new BlockPos(0, 64, 2);
        BlockPos secondUpper = new BlockPos(0, 65, 1);
        BlockPos bridgeFeet = new BlockPos(0, 65, 0);
        BlockPos finalUpper = new BlockPos(0, 66, 1);
        var route = List.of(
                new SafeRoutePlanner.RouteStep(start, firstUpper, SafeRoutePlanner.StepType.SCAFFOLD_UP, null),
                new SafeRoutePlanner.RouteStep(firstUpper, naturalShelf, SafeRoutePlanner.StepType.STEP_UP, null),
                new SafeRoutePlanner.RouteStep(naturalShelf, secondUpper, SafeRoutePlanner.StepType.SCAFFOLD_UP, null),
                new SafeRoutePlanner.RouteStep(secondUpper, bridgeFeet, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(bridgeFeet, finalUpper, SafeRoutePlanner.StepType.SCAFFOLD_UP, null));
        Set<BlockPos> placements = Set.of(
                firstUpper.below(), secondUpper.below(), bridgeFeet.below(), finalUpper.below());

        var assessment = RouteExecutionRules.assessScaffoldRouteBudget(
                route, placements, Set.of(), 128, 128, null);

        assertFalse(assessment.executable());
        assertEquals(secondUpper.below(), assessment.blockedSupport());
        assertEquals(bridgeFeet.below(), assessment.blockedPlacement());
        assertEquals(3, assessment.blockedStep());

        int reservedBudget = RouteExecutionRules.minimumExecutableScaffoldBudget(
                route, placements, Set.of(), 128, 129, 144, null);
        var reservedAssessment = RouteExecutionRules.assessScaffoldRouteBudget(
                route, placements, Set.of(), 128, reservedBudget, null);

        assertEquals(131, reservedBudget);
        assertTrue(reservedAssessment.executable());
        assertEquals(132, reservedAssessment.peakActive());
        assertEquals(-1, RouteExecutionRules.minimumExecutableScaffoldBudget(
                route, placements, Set.of(), 128, 129, 130, null));
        assertEquals(-1, RouteExecutionRules.minimumExecutableScaffoldBudget(
                route, placements, Set.of(), 128, 129, 131, null));
    }

    @Test
    void fullBudgetStillAllowsALinearBridgeToRollForward() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos first = new BlockPos(1, 64, 0);
        BlockPos second = new BlockPos(2, 64, 0);
        BlockPos third = new BlockPos(3, 64, 0);
        BlockPos startSupport = start.below();
        var route = List.of(
                new SafeRoutePlanner.RouteStep(start, first, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(first, second, SafeRoutePlanner.StepType.BRIDGE, null),
                new SafeRoutePlanner.RouteStep(second, third, SafeRoutePlanner.StepType.BRIDGE, null));
        Set<BlockPos> placements = Set.of(first.below(), second.below(), third.below());

        var assessment = RouteExecutionRules.assessScaffoldRouteBudget(
                route, placements, Set.of(startSupport), 128, 128, null);

        assertTrue(assessment.executable());
        assertEquals(129, assessment.peakActive());
    }
}
