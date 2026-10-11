package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Rules added in 0.8.74 after the 0.8.73 test run stalled at a lava pocket with the scaffold budget full. */
class Improvements0874Test {
    @Test
    void fluidRoutesMayBorrowTheTemporaryScaffoldBudget() {
        assertTrue(ConstructionController.mayBorrowRouteScaffoldBudget(ConstructionController.State.EXCAVATING));
        assertTrue(ConstructionController.mayBorrowRouteScaffoldBudget(ConstructionController.State.CONTAINING_FLUID));
        assertFalse(ConstructionController.mayBorrowRouteScaffoldBudget(ConstructionController.State.CLEANING_SCAFFOLDS));
        assertFalse(ConstructionController.mayBorrowRouteScaffoldBudget(ConstructionController.State.DEFENDING));
    }

    @Test
    void deferredPocketIncludesAShellAroundTheFluid() {
        Set<BlockPos> zone = ConstructionController.fluidZone(List.of(new BlockPos(0, 81, 0)), ConstructionController.FLUID_ZONE_SHELL);
        assertEquals(125, zone.size());
        assertTrue(zone.contains(new BlockPos(2, 83, -2)));
        assertFalse(zone.contains(new BlockPos(3, 81, 0)));
    }

    @Test
    void deferredTargetsMoveBehindAllOtherWorkInOrder() {
        BlockPos done = new BlockPos(9, 90, 9);
        BlockPos nearA = new BlockPos(0, 82, 0);
        BlockPos far1 = new BlockPos(20, 82, 0);
        BlockPos nearB = new BlockPos(1, 81, 0);
        BlockPos far2 = new BlockPos(21, 81, 0);
        Set<BlockPos> zone = Set.of(nearA, nearB);
        List<BlockPos> reordered = ConstructionController.deferZoneTargets(List.of(done, nearA, far1, nearB, far2), 1, zone::contains);
        assertEquals(List.of(done, far1, far2, nearA, nearB), reordered);
        assertFalse(ConstructionController.deferredFluidOnlyWorkRemains(reordered, 1, zone::contains));
        assertTrue(ConstructionController.deferredFluidOnlyWorkRemains(reordered, 3, zone::contains));
    }

    @Test
    void onlyWorkAtOrAboveAScaffoldKeepsIt() {
        BlockPos scaffold = new BlockPos(0, 80, 0);
        // Something left one block lower no longer keeps the scaffold (0.8.73 kept it).
        assertTrue(ObsoleteScaffoldRules.isObsolete(scaffold, List.of(new BlockPos(1, 79, 0)), pos -> true));
        assertFalse(ObsoleteScaffoldRules.isObsolete(scaffold, List.of(new BlockPos(1, 80, 0)), pos -> true));
        assertEquals(6.0, ObsoleteScaffoldRules.KEEP_RADIUS);
    }
}
