package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class Improvements0911Test {
    @Test
    void theNearestCellPlaceableWithoutMovingIsPlacedFirst() {
        // 0.9.10 test: the player stood in the active cell, stepped forward, placed it, and
        // repeated for every cell. Cells placeable from where it stands now come first.
        List<BlockPos> targets = new ArrayList<>(List.of(
                new BlockPos(0, 62, 0), new BlockPos(0, 62, 1), new BlockPos(1, 62, 0), new BlockPos(3, 62, 0)));
        Set<BlockPos> placeable = Set.of(new BlockPos(1, 62, 0), new BlockPos(3, 62, 0));
        int moved = FlattenRefillQueue.promoteNearestPlaceableHere(targets, 0, new BlockPos(0, 62, 0), placeable::contains);
        assertEquals(2, moved);
        assertEquals(new BlockPos(1, 62, 0), targets.get(0));
        assertEquals(new BlockPos(0, 62, 0), targets.get(1), "the cell the player stands in waits");
        assertEquals(4, targets.size());
    }

    @Test
    void theRouteIsTakenWhenNothingIsPlaceableHere() {
        List<BlockPos> targets = new ArrayList<>(List.of(new BlockPos(0, 62, 0), new BlockPos(1, 62, 0)));
        assertEquals(-1, FlattenRefillQueue.promoteNearestPlaceableHere(targets, 0, new BlockPos(0, 62, 0), cell -> false));
        assertEquals(new BlockPos(0, 62, 0), targets.get(0));
    }

    @Test
    void cellsOutsideTheRadiusAreNotEvenChecked() {
        List<BlockPos> targets = new ArrayList<>(List.of(new BlockPos(0, 62, 0), new BlockPos(9, 62, 0)));
        assertEquals(-1, FlattenRefillQueue.promoteNearestPlaceableHere(targets, 0, new BlockPos(0, 62, 0), cell -> {
            throw new AssertionError("checked a far cell " + cell);
        }));
    }
}
