package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0914Test {
    @Test
    void theIslandPreviewLineKeepsOnlyWhatThePlayerDecidesOn() {
        String line = IslandPreviewText.summary(5321, -120, 40, -300, -150, 4, "“开始 / 停止施工”键（K）");
        assertEquals("TerraLeveler 岛屿预览：平整 5321 列，范围 X -120..40、Z -300..-150，分 4 个区域逐块施工。"
                + "确认无误后再按一次“开始 / 停止施工”键（K）开始。", line);
        assertFalse(IslandPreviewText.summary(10, 0, 1, 0, 1, 0, "K").contains("区域"), "one-go islands do not mention regions");
    }

    @Test
    void theFiguresMoveIntoTheTooltip() {
        String details = IslandPreviewText.details(6000, 420, 35, 2, 0);
        assertTrue(details.contains("连通的陆地：6000 列"));
        assertTrue(details.contains("截断的窄陆桥：2 处"));
        assertFalse(details.contains("每个区域"));
        assertTrue(IslandPreviewText.details(1, 0, 0, 0, 512).contains("512×512"));
    }
}
