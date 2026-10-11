package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Takes torches out of a carried shulker box for area lighting. Since 0.9.08 the box is
 * opened with QuickShulker and the torches are shift-clicked out
 * ({@link ShulkerBoxMenuTransaction}); before, this used Carpet Org Addition's
 * "quickShulker" cursor clicks.
 */
final class LightingTorchShulkerExtractor {
    /** One opening takes up to this many torches. */
    static final int TORCHES_PER_EXTRACTION = 64;

    private final ShulkerBoxMenuTransaction transaction = new ShulkerBoxMenuTransaction();
    private Status status = Status.IDLE;
    private String detail = "尚未开始潜影盒取火把";
    private boolean safeFailure = true;

    StartResult start(Minecraft mc) {
        if (status == Status.RUNNING) return StartResult.STARTED;
        reset();
        if (mc.player == null || mc.gameMode == null) {
            detail = "真实玩家或游戏交互暂不可用";
            return StartResult.BLOCKED;
        }
        if (mc.screen instanceof AbstractContainerScreen) {
            detail = "请先关闭背包界面；TerraLeveler 不会在打开的背包界面上取火把";
            return StartResult.WAITING_FOR_CLOSED_GUI;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            detail = "当前容器或鼠标物品状态不允许安全取火把";
            return StartResult.BLOCKED;
        }

        Inventory inventory = mc.player.getInventory();
        int boxSlot = findTorchBox(inventory);
        if (boxSlot < 0) {
            detail = "背包里没有装着火把的潜影盒";
            return StartResult.NO_BOX;
        }
        int destination = ConstructionController.materialExtractionDestination(
                boxSlot, slot -> inventory.getItem(slot).isEmpty());
        if (destination < 0) {
            detail = "从潜影盒取火把需要至少一个空背包格";
            return StartResult.NO_SPACE;
        }
        if (!transaction.begin(mc, ShulkerBoxMenuTransaction.Kind.EXTRACT, Items.TORCH, boxSlot,
                TORCHES_PER_EXTRACTION, -1)) {
            detail = "无法打开火把潜影盒：" + transaction.failure();
            return StartResult.BLOCKED;
        }
        status = Status.RUNNING;
        detail = "打开潜影盒取火把";
        TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-start boxSlot=" + boxSlot
                + " contained=" + ShulkerBoxMenuTransaction.containedCount(inventory.getItem(boxSlot), Items.TORCH)
                + " loose=" + ShulkerBoxMenuTransaction.inventoryCount(inventory, Items.TORCH));
        return StartResult.STARTED;
    }

    Status tick(Minecraft mc) {
        if (status != Status.RUNNING) return status;
        AutoNavigator.release(mc);
        if (mc.player == null || mc.gameMode == null) {
            fail(mc, "取火把期间真实玩家或游戏交互不可用");
            return status;
        }
        if (mc.screen instanceof AbstractContainerScreen) {
            fail(mc, "取火把期间打开了背包界面，已关闭潜影盒");
            return status;
        }
        ShulkerBoxMenuTransaction.Result result = transaction.tick(mc);
        detail = transaction.detail();
        if (result == ShulkerBoxMenuTransaction.Result.RUNNING) return status;
        if (result == ShulkerBoxMenuTransaction.Result.SUCCESS && transaction.movedCount(mc) > 0) {
            status = Status.SUCCESS;
            detail = "已从潜影盒取出 " + transaction.movedCount(mc) + " 个火把";
            TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-complete moved=" + transaction.movedCount(mc));
        } else {
            fail(mc, result == ShulkerBoxMenuTransaction.Result.SUCCESS
                    ? "潜影盒已打开，但没有取出火把（背包可能没有空位）"
                    : transaction.menuNeverOpened()
                    ? "服务器没有打开潜影盒（服务器可能没装 QuickShulker）"
                    : "取火把失败：" + transaction.failure());
        }
        return status;
    }

    /** Stops at once: the box is closed; nothing can be lost (no cursor use). */
    void requestRollback(String reason) {
        if (status != Status.RUNNING) return;
        TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-rollback-request reason=" + reason);
        fail(Minecraft.getInstance(), reason);
    }

    void reset() {
        if (transaction.active()) transaction.cancel(Minecraft.getInstance(), "torch-reset");
        transaction.reset();
        status = Status.IDLE;
        detail = "尚未开始潜影盒取火把";
        safeFailure = true;
    }

    Status status() { return status; }
    String detail() { return detail; }
    boolean safeFailure() { return safeFailure; }

    private void fail(Minecraft mc, String reason) {
        transaction.cancel(mc, reason);
        status = Status.FAILED;
        safeFailure = true;
        detail = reason;
        TerraLevelerDiagnostics.log("LIGHTING", "torch-shulker-failed reason=" + reason);
    }

    /** The carried box holding the most torches, or -1. */
    private static int findTorchBox(Inventory inventory) {
        int best = -1;
        int bestCount = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            int count = ShulkerBoxMenuTransaction.containedCount(stack, Items.TORCH);
            if (count > bestCount) {
                best = slot;
                bestCount = count;
            }
        }
        return best;
    }

    enum StartResult {
        STARTED,
        WAITING_FOR_CLOSED_GUI,
        NO_BOX,
        NO_SPACE,
        BLOCKED
    }

    enum Status {
        IDLE,
        RUNNING,
        SUCCESS,
        FAILED
    }
}
