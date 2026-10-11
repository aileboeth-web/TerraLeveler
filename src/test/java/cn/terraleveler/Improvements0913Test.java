package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0913Test {
    @Test
    void digsAheadOnlyAtTheFloorOrAbove() {
        // Standing at feet Y=68 on the Y=67 layer (0.9.11 test): the floor layer and up may be
        // dug before walking, nothing below it.
        assertTrue(BatchMiningSafetyRules.allowsReachPromotion(67, 68), "the floor layer");
        assertTrue(BatchMiningSafetyRules.allowsReachPromotion(70, 68), "above the player");
        assertFalse(BatchMiningSafetyRules.allowsReachPromotion(66, 68), "a pit under the floor");
    }

    @Test
    void keyHintsNameTheActionAndItsKeyOrSayItIsUnbound() {
        assertEquals("“暂停 / 恢复”键（P）", KeyHintText.format(KeyHintText.PAUSE_RESUME, "P"));
        assertEquals("“记录平地目标高度”键（还没绑定，可在设置里绑定）", KeyHintText.format(KeyHintText.FLATTEN_HEIGHT, "无"));
        assertEquals("“开始 / 停止施工”键（还没绑定，可在设置里绑定）", KeyHintText.format(KeyHintText.START_STOP, null));
    }

    @Test
    void aPauseNeedingThePlayerNamesThePauseKey() {
        assertTrue(TerraLevelerAlerts.pauseMessage("TerraLeveler", "被挡住", TerraLevelerAlerts.Resume.BY_PLAYER)
                .contains("“暂停 / 恢复”键"));
    }
}
