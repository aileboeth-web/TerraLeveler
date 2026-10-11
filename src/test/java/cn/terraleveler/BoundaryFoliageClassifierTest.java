package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundaryFoliageClassifierTest {
    @Test
    void foliageConnectedToAnInSelectionLogIsInterior() {
        BlockPos log = new BlockPos(1, 65, 1);
        BlockPos leaf = new BlockPos(2, 66, 1);
        BlockPos branchLeaf = new BlockPos(3, 66, 1);

        BoundaryFoliageClassifier.Result result = BoundaryFoliageClassifier.classify(
                List.of(log), List.of(leaf, branchLeaf));

        assertEquals(java.util.Set.of(leaf, branchLeaf), result.interior());
        assertTrue(result.boundary().isEmpty());
    }

    @Test
    void canopyWithoutAnyInSelectionLogIsBoundaryFoliage() {
        BlockPos first = new BlockPos(0, 70, 0);
        BlockPos second = new BlockPos(1, 70, 0);

        BoundaryFoliageClassifier.Result result = BoundaryFoliageClassifier.classify(
                List.of(), List.of(first, second));

        assertTrue(result.interior().isEmpty());
        assertEquals(java.util.Set.of(first, second), result.boundary());
    }

    @Test
    void disconnectedCanopyRemainsBoundaryWhenAnotherTreeIsInside() {
        BlockPos log = new BlockPos(0, 65, 0);
        BlockPos interiorLeaf = new BlockPos(0, 66, 0);
        BlockPos boundaryLeaf = new BlockPos(8, 66, 0);

        BoundaryFoliageClassifier.Result result = BoundaryFoliageClassifier.classify(
                List.of(log), List.of(interiorLeaf, boundaryLeaf));

        assertEquals(java.util.Set.of(interiorLeaf), result.interior());
        assertEquals(java.util.Set.of(boundaryLeaf), result.boundary());
    }

    @Test
    void foliageIsOnlyProactivelyCleanedWhenTheExplicitSwitchIsEnabled() {
        BlockPos interior = new BlockPos(0, 66, 0);
        BlockPos boundary = new BlockPos(8, 66, 0);
        List<BlockPos> current = List.of(boundary, interior);

        assertEquals(List.of(), BoundaryFoliageClassifier.cleanupTargets(
                current, java.util.Set.of(interior), false));
        assertEquals(current, BoundaryFoliageClassifier.cleanupTargets(
                current, java.util.Set.of(interior), true));
    }
}
