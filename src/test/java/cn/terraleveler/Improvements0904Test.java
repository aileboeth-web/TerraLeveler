package cn.terraleveler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class Improvements0904Test {
    @Test
    void aConfigSavedInTheRemovedTrenchModeOpensInFlatten() {
        assertEquals(WorkMode.FLATTEN, WorkMode.parse("TRENCH"));
        assertEquals(WorkMode.FLATTEN, WorkMode.parse(null));
        assertEquals(WorkMode.LIGHTING, WorkMode.parse("LIGHTING"));
    }

    @Test
    void onlyFlattenAndLightingCanBeChosen() {
        assertEquals(List.of(WorkMode.FLATTEN, WorkMode.LIGHTING), WorkMode.SELECTABLE);
    }

    @Test
    void aNewConfigStartsInFlatten() {
        assertEquals("FLATTEN", new TerraLevelerConfig().activeWorkMode);
    }
}
