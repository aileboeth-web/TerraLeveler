package cn.terraleveler;

/**
 * Column widths of the settings list (0.9.07). At a large GUI scale the screen can be only
 * 320 px wide; the old fixed layout (label + 80 px, 180 px controls) pushed the reset and
 * shortcut buttons off-screen there. Normal-sized screens keep the old widths.
 */
final class SettingsLayoutRules {
    /** Control column (text fields, switches, shortcut buttons) at normal GUI sizes. */
    static final int CONTROL_WIDTH = 180;
    static final int MIN_CONTROL_WIDTH = 90;
    /** Gap left of a control (malilib draws it at label width + 10). */
    static final int CONTROL_GAP = 10;
    /** Room right of a control: 2 px gap, the reset button (about 30 px) and the scrollbar. */
    static final int ROW_TRAILING_WIDTH = 50;
    /** Width set aside for the longest option name (about 105 px) when sizing the control column. */
    static final int LABEL_RESERVE = 120;
    /** Space after the longest option name at normal GUI sizes. */
    static final int MAX_LABEL_PADDING = 80;

    private SettingsLayoutRules() { }

    /** Width of the control column for a list this wide. */
    static int controlWidth(int listWidth) {
        return Math.max(MIN_CONTROL_WIDTH, Math.min(CONTROL_WIDTH,
                listWidth - LABEL_RESERVE - CONTROL_GAP - ROW_TRAILING_WIDTH));
    }

    /** Space after the longest option name: up to 80 px, less when the list is narrow. */
    static int labelPadding(int listWidth, int longestLabel, int controlWidth) {
        return Math.max(4, Math.min(MAX_LABEL_PADDING,
                listWidth - longestLabel - CONTROL_GAP - controlWidth - ROW_TRAILING_WIDTH));
    }

    /** Total width of one option row (label column, control, reset button). */
    static int rowWidth(int longestLabel, int labelPadding, int controlWidth) {
        return longestLabel + labelPadding + CONTROL_GAP + controlWidth + ROW_TRAILING_WIDTH;
    }
}
