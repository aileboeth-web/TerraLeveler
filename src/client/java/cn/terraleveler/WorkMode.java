package cn.terraleveler;

public enum WorkMode {
    FLATTEN("平地机"),
    LIGHTING("区域照明");

    /** Modes a player can choose (the settings screen's mode buttons). */
    static final java.util.List<WorkMode> SELECTABLE = java.util.List.of(values());

    private final String label;

    WorkMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** A stored mode name; anything else, including "TRENCH" from before 0.9.03, becomes FLATTEN. */
    static WorkMode parse(String value) {
        if (value == null) return FLATTEN;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return FLATTEN;
        }
    }
}
