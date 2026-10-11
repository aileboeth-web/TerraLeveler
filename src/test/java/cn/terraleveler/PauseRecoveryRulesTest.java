package cn.terraleveler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PauseRecoveryRulesTest {
    @Test
    void everyInternalPauseCanRetryAfterThirtySeconds() {
        assertTrue(ConstructionController.autoRetryAllowedForPause("连续三次找不到安全路线（NO_PATH）"));
        assertTrue(ConstructionController.autoRetryAllowedForPause("服务器确认超时"));
        assertTrue(ConstructionController.autoRetryAllowedForPause("合适工具的剩余耐久不足"));
        assertTrue(ConstructionController.autoRetryAllowedForPause("真实玩家进入岩浆"));
        assertFalse(ConstructionController.shouldAutoRetryPause(20 * 30 - 1));
        assertTrue(ConstructionController.shouldAutoRetryPause(20 * 30));
        assertFalse(ConstructionController.autoRetryAttemptsExhausted(9));
        assertTrue(ConstructionController.autoRetryAttemptsExhausted(10));
    }

    @Test
    void quickShulkerPausesAreRetryable() {
        assertTrue(ConstructionController.autoRetryAllowedForPause(
                "quickShulker 事务期间打开了背包界面，潜影盒已自动回滚"));
        assertTrue(ConstructionController.autoRetryAllowedForPause(
                "闭盒补货目标空格在取物前被占用"));
        assertTrue(ConstructionController.autoRetryAllowedForPause(
                "quickShulker 在 8 秒内没有得到稳定服务器确认；库存状态不符合自动回滚条件，请检查潜影盒原槽和临时格"));
    }

    @Test
    void onlyManualPauseIsNeverAutomaticallyResumed() {
        assertFalse(ConstructionController.autoRetryAllowedForPause("用户请求暂停"));
        assertFalse(ConstructionController.autoRetryAllowedForPause("用户主动暂停"));
        assertFalse(ConstructionController.autoRetryAllowedForPause(""));
        assertFalse(ConstructionController.autoRetryAllowedForPause(null));
    }

    @Test
    void trackedScaffoldToolSnapshotFailureRetriesAfterThirtySeconds() {
        assertTrue(ConstructionController.autoRetryAllowedForPause(
                "自有临时脚手清理缺少高于保留耐久的有效工具 -280, 62, -192"));
    }

    @Test
    void deterministicTopologyFailureDoesNotRetryWithoutAWorldStateChange() {
        assertFalse(ConstructionController.autoRetryAllowedForPause(
                "确定性拓扑阻塞：剩余脚手在当前起点、脚手账本下均无可逆清理路线"));
        assertFalse(ConstructionController.autoRetryAllowedForPause(
                "确定性清场高差阻塞：连续三次找不到天然、可逆脚手或水桶下降路线（NO_ROUTE）"));
    }
}
