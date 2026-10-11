package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FlattenFluidExecutionRulesTest {
    @Test
    void directlyReachableFluidFrontierBeatsOriginalTransactionOrder() {
        int selected = FlattenFluidExecutionRules.selectNextPlacement(
                0, 4,
                index -> true,
                index -> index == 2,
                index -> new double[]{4.0, 3.0, 9.0, 1.0}[index]);
        assertEquals(2, selected);
    }

    @Test
    void nearestReadySupportIsUsedWhenNothingIsDirectlyReachable() {
        int selected = FlattenFluidExecutionRules.selectNextPlacement(
                1, 5,
                index -> index != 2,
                index -> false,
                index -> new double[]{99.0, 8.0, 1.0, 3.0, 5.0}[index]);
        assertEquals(3, selected);
    }

    @Test
    void unavailableDependenciesReturnNoPlacement() {
        assertEquals(-1, FlattenFluidExecutionRules.selectNextPlacement(
                0, 3, index -> false, index -> true, index -> 0.0));
    }
}
