package cn.terraleveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * A block 38 above the ground whose side columns all ended on old scaffolds kept the 0.8.97 test
 * looping for 19 minutes: a pillar straight under it, and a limit on time spent on one target.
 */
class Improvements0898Test {
    private static final BlockPos TARGET = new BlockPos(-3608, 100, -9962);

    /** Ground up to Y=61; old scaffolds at Y=80 in the four side columns only. */
    private static FinalScaffoldPillarPlanner.CellKind world(BlockPos pos) {
        boolean sideColumn = Math.abs(pos.getX() - TARGET.getX()) + Math.abs(pos.getZ() - TARGET.getZ()) == 1;
        if (pos.getY() <= 61 || pos.equals(TARGET) || sideColumn && pos.getY() == 80) {
            return FinalScaffoldPillarPlanner.CellKind.SOLID;
        }
        return FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
    }

    @Test
    void sideColumnsEndOnTheOldScaffolds() {
        FinalScaffoldPillarPlanner.Result sides = FinalScaffoldPillarPlanner.planAccess(
                TARGET, new BlockPos(-3608, 62, -9962), Integer.MAX_VALUE, 64, -64, Improvements0898Test::world);
        assertTrue(sides.ready());
        for (FinalScaffoldPillarPlanner.Candidate candidate : sides.candidates()) {
            assertEquals(81, candidate.baseFeet().getY(), "side base sits on the scaffold, out of reach from the ground");
        }
    }

    @Test
    void pillarUnderTheTargetStartsWhereThePlayerStands() {
        FinalScaffoldPillarPlanner.Result under = FinalScaffoldPillarPlanner.planUnderTarget(
                TARGET, 64, -64, Improvements0898Test::world);
        assertTrue(under.ready());
        FinalScaffoldPillarPlanner.Candidate pillar = under.candidates().getFirst();
        assertEquals(new BlockPos(-3608, 62, -9962), pillar.baseFeet());
        // Feet three below the target: two free blocks above the head for the last jump.
        assertEquals(new BlockPos(-3608, 97, -9962), pillar.topFeet());
        assertEquals(35, pillar.requiredBlocks());
        assertEquals(96, pillar.placements().getLast().getY());
    }

    @Test
    void underTargetNeedsFreeHeadroomAndADryColumn() {
        Function<BlockPos, FinalScaffoldPillarPlanner.CellKind> blockedHead = pos -> pos.getY() <= 61
                || pos.equals(TARGET) || pos.equals(TARGET.below(2))
                ? FinalScaffoldPillarPlanner.CellKind.SOLID : FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
        assertEquals(FinalScaffoldPillarPlanner.Status.NO_CLEAR_COLUMN,
                FinalScaffoldPillarPlanner.planUnderTarget(TARGET, 64, -64, blockedHead).status());

        Function<BlockPos, FinalScaffoldPillarPlanner.CellKind> water = pos -> pos.getY() <= 61 || pos.equals(TARGET)
                ? FinalScaffoldPillarPlanner.CellKind.SOLID
                : pos.getY() == 62 ? FinalScaffoldPillarPlanner.CellKind.FLUID : FinalScaffoldPillarPlanner.CellKind.OPEN_DRY;
        assertEquals(FinalScaffoldPillarPlanner.Status.NO_CLEAR_COLUMN,
                FinalScaffoldPillarPlanner.planUnderTarget(TARGET, 64, -64, water).status());

        assertEquals(FinalScaffoldPillarPlanner.Status.PILLAR_TOO_TALL,
                FinalScaffoldPillarPlanner.planUnderTarget(TARGET, 20, -64, Improvements0898Test::world).status());
    }

    @Test
    void aStuckTargetMovesBehindAllOthersOnce() {
        BlockPos done = new BlockPos(0, 70, 0);
        BlockPos next = new BlockPos(2, 99, 0);
        BlockPos last = new BlockPos(3, 98, 0);
        List<BlockPos> queue = List.of(done, TARGET, next, last);
        assertEquals(List.of(done, next, last, TARGET),
                ConstructionController.deferZoneTargets(queue, 1, Set.of(TARGET)::contains));
        assertEquals(20 * 60 * 4, ConstructionController.STUCK_EXCAVATION_TARGET_TICKS);
    }
}
