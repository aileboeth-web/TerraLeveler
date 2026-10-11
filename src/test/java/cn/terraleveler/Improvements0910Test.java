package cn.terraleveler;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Improvements0910Test {
    /** A row of surface cells at Y=62 along -Z, the way the fill walks. */
    private static final List<BlockPos> ROW = List.of(
            new BlockPos(0, 62, 0), new BlockPos(0, 62, -1), new BlockPos(0, 62, -2), new BlockPos(0, 62, -3),
            new BlockPos(0, 62, -4), new BlockPos(0, 62, -5), new BlockPos(0, 62, -6), new BlockPos(0, 62, -7));

    @Test
    void aStandFurtherAlongTheRowReachesMoreUpcomingCells() {
        // Standing beside the first cell (two blocks to the side) reaches only the first few;
        // standing beside the third cell reaches the cells on both sides.
        int nearest = WorkPoseRules.coveredTargets(new BlockPos(2, 63, 1), ROW, WorkPoseRules.FILL_LOOKAHEAD_REACH_SQUARED);
        int ahead = WorkPoseRules.coveredTargets(new BlockPos(2, 63, -3), ROW, WorkPoseRules.FILL_LOOKAHEAD_REACH_SQUARED);
        assertTrue(ahead > nearest, "ahead " + ahead + " nearest " + nearest);
    }

    @Test
    void aStandInsideAnUnfilledCellIsNeverPreferred() {
        assertEquals(-1, WorkPoseRules.coveredTargets(new BlockPos(0, 62, -2), ROW, WorkPoseRules.FILL_LOOKAHEAD_REACH_SQUARED));
    }

    @Test
    void onlyStandsNearlyAsGoodAsTheBestAreOffered() {
        assertEquals(List.of(1, 2), WorkPoseRules.lookaheadChoice(new int[] {2, 6, 5, -1}, 3));
        assertNull(WorkPoseRules.lookaheadChoice(new int[] {1, 2, 2}, 3), "nothing reaches enough: keep all stands");
    }

    @Test
    void theStatusBarSpeaksChineseAndNeverShowsAFinishedJobEarly() {
        assertEquals("铺地表", StatusText.stateLabel("BUILDING_WALL"));
        assertEquals("削平", StatusText.stateLabel("EXCAVATING"));
        assertEquals("45%", StatusText.percent(45, 100, false));
        assertEquals("99%", StatusText.percent(120, 100, false));
        assertEquals("100%", StatusText.percent(0, 0, true));
        assertEquals("", StatusText.percent(5, 0, false));
    }

    @Test
    void aPauseMessageSaysWhetherTerraLevelerContinuesByItself() {
        assertTrue(TerraLevelerAlerts.pauseMessage("TerraLeveler", "没有路线", TerraLevelerAlerts.Resume.AUTO_RETRY).contains("30 秒后自动重试"));
        assertTrue(TerraLevelerAlerts.pauseMessage("TerraLeveler", "被挡住", TerraLevelerAlerts.Resume.BY_PLAYER).contains("暂停 / 恢复"));
    }
}
