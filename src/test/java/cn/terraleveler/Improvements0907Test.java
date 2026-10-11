package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0907Test {
    /** Longest option name in the settings list (about 105 px) plus a margin. */
    private static final int LONGEST_LABEL = 115;

    @Test
    void everyRowFitsFromTheSmallestGuiUp() {
        // List widths for 320, 426 and 480 px wide GUIs (the list leaves 10 px on each side).
        for (int listWidth : new int[] {300, 406, 460}) {
            int control = SettingsLayoutRules.controlWidth(listWidth);
            int padding = SettingsLayoutRules.labelPadding(listWidth, LONGEST_LABEL, control);
            int row = SettingsLayoutRules.rowWidth(LONGEST_LABEL, padding, control);
            assertTrue(row <= listWidth, "list " + listWidth + " row " + row);
            assertTrue(control >= 90, "control column stays usable at " + listWidth);
        }
    }

    @Test
    void aNormalSizedScreenKeepsTheOldLayout() {
        int control = SettingsLayoutRules.controlWidth(460);
        assertEquals(180, control);
        assertEquals(80, SettingsLayoutRules.labelPadding(560, LONGEST_LABEL, control));
    }
}
