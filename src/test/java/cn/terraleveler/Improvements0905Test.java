package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class Improvements0905Test {
    @Test
    void anUntouchedConfigIsNotSavedAgain() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        assertEquals(config.toJson(), config.copy().toJson());
    }

    @Test
    void aBoundShortcutOrASwitchCountsAsAChange() {
        TerraLevelerConfig config = new TerraLevelerConfig();
        String before = config.toJson();
        config.flattenHeightKey = "key.keyboard.backslash";
        assertNotEquals(before, config.toJson());

        String afterKey = config.toJson();
        config.useToolBoxForTools = true;
        assertNotEquals(afterKey, config.toJson());
    }
}
