package cn.terraleveler;

import java.util.function.IntFunction;

/**
 * Chat hints used to name fixed keys ("、", "开始"); every hotkey is unbound by default, so
 * hints name the action and its current key, or say it still needs binding (0.9.13).
 */
final class KeyHintText {
    static final int START_STOP = 0;
    static final int PAUSE_RESUME = 1;
    static final int FLATTEN_HEIGHT = 2;
    static final int RETAIN_ITEM = 3;

    /** Display name of the key bound to an action, "无" when unbound; set by TerraLeveler at startup. */
    static volatile IntFunction<String> boundKeys = action -> "无";

    private KeyHintText() { }

    /** Names of the four hotkeys as the settings screen shows them. */
    static String label(int action) {
        return action == START_STOP ? "开始 / 停止施工"
                : action == PAUSE_RESUME ? "暂停 / 恢复"
                : action == FLATTEN_HEIGHT ? "记录平地目标高度" : "记录保留物品";
    }

    static String hint(int action) {
        String bound;
        try {
            bound = boundKeys.apply(action);
        } catch (RuntimeException unavailable) {
            bound = null;
        }
        return format(action, bound);
    }

    static String format(int action, String bound) {
        boolean unbound = bound == null || bound.isBlank() || "无".equals(bound);
        return "“" + label(action) + "”键（" + (unbound ? "还没绑定，可在设置里绑定" : bound) + "）";
    }
}
