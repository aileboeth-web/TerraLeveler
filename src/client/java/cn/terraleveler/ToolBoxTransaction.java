package cn.terraleveler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

final class ToolBoxTransaction {
    static final int CONFIRM_TICKS = 4;
    private static final int OPEN_TIMEOUT_TICKS = 80;
    private static final int TRANSACTION_TIMEOUT_TICKS = 160;
    private final QuickShulkerBridge bridge = new QuickShulkerBridge();
    private Phase phase = Phase.IDLE;
    private ToolBoxSelectionRules.ToolKind requiredKind = ToolBoxSelectionRules.ToolKind.OTHER;
    private int durabilityReserve;
    private int boxInventorySlot = -1;
    private int boxMenuSlot = -1;
    private int ownerMenuId = -1;
    private int sourceMenuSlot = -1;
    private int hotbarDestination = -1;
    private int evacuatedInventorySlot = -1;
    private String selectedBoxName;
    private int ticks;
    private int stableTicks;
    private boolean swapSubmitted;
    private BlockState targetState;
    private ItemStack newTool = ItemStack.EMPTY;
    private ItemStack oldTool = ItemStack.EMPTY;
    private ItemStack evacuatedStack = ItemStack.EMPTY;
    private String detail = "";
    private String failure = "";

    BeginResult begin(Minecraft mc, BlockState targetState, int reserve, List<String> allowedNames) {
        reset();
        if (mc == null || mc.player == null || mc.gameMode == null || targetState == null) {
            return failBegin("missing-client-state");
        }
        requiredKind = requiredKind(targetState);
        if (!requiredKind.managed()) return failBegin("target-does-not-use-managed-tool");
        if (mc.player.containerMenu != mc.player.inventoryMenu) return failBegin("another-container-open");
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) return failBegin("cursor-not-empty");

        durabilityReserve = reserve;
        this.targetState = targetState;
        ToolBoxSelectionRules.BoxSelection boxSelection = findToolBox(mc, mc.player.getInventory(), targetState,
                reserve, allowedNames);
        if (boxSelection.status() != ToolBoxSelectionRules.BoxStatus.FOUND) {
            return failBegin("tool-box-" + boxSelection.status().name().toLowerCase());
        }
        boxInventorySlot = boxSelection.inventorySlot();
        selectedBoxName = boxSelection.customName();
        hotbarDestination = chooseHotbarDestination(mc.player.getInventory(), reserve);
        if (hotbarDestination < 0) {
            HotbarEvacuation evacuation = findHotbarEvacuation(mc.player.getInventory(), boxInventorySlot);
            if (evacuation == null) {
                return failBegin("no-safe-hotbar-destination", BeginResult.NEEDS_HOTBAR_SPACE);
            }
            Slot destination = inventoryMenuSlot(mc, evacuation.inventorySlot());
            if (destination == null) return failBegin("hotbar-evacuation-slot-not-found");
            hotbarDestination = evacuation.hotbarSlot();
            evacuatedInventorySlot = evacuation.inventorySlot();
            evacuatedStack = mc.player.getInventory().getItem(hotbarDestination).copy();
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, destination.index,
                    hotbarDestination, ContainerInput.SWAP, mc.player);
            phase = Phase.WAIT_HOTBAR_SPACE;
            detail = "waiting for server hotbar evacuation confirmation";
            TerraLevelerDiagnostics.log("TOOLBOX", "hotbar-evacuation-request hotbar=" + hotbarDestination
                    + " inventory=" + evacuatedInventorySlot + " item=" + evacuatedStack);
            return BeginResult.STARTED;
        }
        String openFailure = requestOpen(mc);
        if (openFailure != null) return failBegin(openFailure);
        return BeginResult.STARTED;
    }

    Result tick(Minecraft mc) {
        if (phase == Phase.IDLE) return Result.FAILED_CONTINUE;
        if (phase == Phase.COMPLETE) return Result.SUCCESS;
        if (phase == Phase.FAILED) return Result.FAILED_CONTINUE;
        if (mc == null || mc.player == null || mc.gameMode == null) return finishFailure("missing-client-state");
        BoxScreenHider.keepAlive();

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            detail = "waiting for an empty cursor before tool-box recovery";
            return Result.RUNNING;
        }
        ticks++;
        if (ticks > TRANSACTION_TIMEOUT_TICKS) return beginFailure(mc, "transaction-timeout");

        return switch (phase) {
            case WAIT_HOTBAR_SPACE -> tickWaitHotbarSpace(mc, menu);
            case WAIT_MENU -> tickWaitMenu(mc, menu);
            case WAIT_SWAP -> tickWaitSwap(mc, menu);
            case WAIT_CLOSE -> tickWaitClose(mc, menu);
            case WAIT_ROLLBACK -> tickWaitRollback(mc, menu);
            case IDLE, COMPLETE, FAILED -> throw new IllegalStateException("unexpected tool-box phase " + phase);
        };
    }

    void cancel(Minecraft mc, String reason) {
        if (phase == Phase.IDLE) return;
        failure = reason;
        if (mc != null && mc.player != null && mc.player.containerMenu.containerId == ownerMenuId
                && mc.player.containerMenu.getCarried().isEmpty()) {
            mc.player.closeContainer();
        }
        BoxScreenHider.release();
        TerraLevelerDiagnostics.log("TOOLBOX", "cancel phase=" + phase + " reason=" + reason);
        phase = Phase.FAILED;
    }

    boolean active() {
        return phase != Phase.IDLE && phase != Phase.COMPLETE && phase != Phase.FAILED;
    }

    String detail() {
        return detail;
    }

    String failure() {
        return failure;
    }

    void reset() {
        phase = Phase.IDLE;
        requiredKind = ToolBoxSelectionRules.ToolKind.OTHER;
        durabilityReserve = 0;
        boxInventorySlot = -1;
        boxMenuSlot = -1;
        ownerMenuId = -1;
        sourceMenuSlot = -1;
        hotbarDestination = -1;
        evacuatedInventorySlot = -1;
        selectedBoxName = null;
        ticks = 0;
        stableTicks = 0;
        swapSubmitted = false;
        targetState = null;
        newTool = ItemStack.EMPTY;
        oldTool = ItemStack.EMPTY;
        evacuatedStack = ItemStack.EMPTY;
        detail = "";
        failure = "";
    }

    private Result tickWaitHotbarSpace(Minecraft mc, AbstractContainerMenu menu) {
        if (menu != mc.player.inventoryMenu) return finishFailure("another-container-open-during-hotbar-evacuation");
        Inventory inventory = mc.player.getInventory();
        boolean matched = inventory.getItem(hotbarDestination).isEmpty()
                && sameStack(inventory.getItem(evacuatedInventorySlot), evacuatedStack);
        stableTicks = nextStableTicks(stableTicks, matched);
        if (!stableConfirmed(stableTicks)) {
            detail = "waiting for server hotbar evacuation confirmation";
            return Result.RUNNING;
        }
        TerraLevelerDiagnostics.log("TOOLBOX", "hotbar-evacuation-confirmed hotbar=" + hotbarDestination
                + " inventory=" + evacuatedInventorySlot + " item=" + evacuatedStack);
        String openFailure = requestOpen(mc);
        return openFailure == null ? Result.RUNNING : finishFailure(openFailure);
    }

    private String requestOpen(Minecraft mc) {
        if (!sameSelectedToolBox(mc.player.getInventory().getItem(boxInventorySlot))) {
            return "tool-box-moved-before-open";
        }
        Slot menuSlot = inventoryMenuSlot(mc, boxInventorySlot);
        if (menuSlot == null) return "tool-box-menu-slot-not-found";
        boxMenuSlot = menuSlot.index;
        BoxScreenHider.keepAlive();
        QuickShulkerBridge.OpenResult openResult = bridge.open(boxMenuSlot);
        if (!openResult.sent()) {
            BoxScreenHider.release();
            return openResult.failure();
        }
        phase = Phase.WAIT_MENU;
        ticks = 0;
        stableTicks = 0;
        detail = "waiting for QuickShulker tool menu";
        TerraLevelerDiagnostics.log("TOOLBOX", "open-request inventorySlot=" + boxInventorySlot
                + " menuSlot=" + boxMenuSlot + " kind=" + requiredKind
                + " hotbar=" + hotbarDestination);
        return null;
    }

    private Result tickWaitMenu(Minecraft mc, AbstractContainerMenu menu) {
        if (menu == mc.player.inventoryMenu) {
            if (ticks > OPEN_TIMEOUT_TICKS) return beginFailure(mc, "menu-open-timeout");
            return Result.RUNNING;
        }

        List<Slot> boxSlots = ownedBoxSlots(menu, mc.player.getInventory());
        if (boxSlots.size() != 27) {
            if (ticks > OPEN_TIMEOUT_TICKS) return beginFailure(mc, "unexpected-container-layout:" + boxSlots.size());
            detail = "waiting for the 27-slot tool container";
            return Result.RUNNING;
        }
        if (!sameSelectedToolBox(mc.player.getInventory().getItem(boxInventorySlot))) {
            return beginFailure(mc, "tool-box-moved-before-swap");
        }

        ownerMenuId = menu.containerId;
        List<ToolBoxSelectionRules.ToolCandidate> candidates = new ArrayList<>();
        for (Slot slot : boxSlots) {
            ItemStack stack = slot.getItem();
            candidates.add(new ToolBoxSelectionRules.ToolCandidate(slot.index, toolKind(stack),
                    ServerActionExecutor.effectiveToolScore(mc, stack, targetState),
                    ServerActionExecutor.remainingDurability(stack)));
        }
        sourceMenuSlot = ToolBoxSelectionRules.bestToolSlot(candidates, requiredKind, durabilityReserve);
        Slot source = menuSlot(menu, sourceMenuSlot);
        if (source == null) return beginFailure(mc, "no-safe-tool-in-box");

        hotbarDestination = chooseHotbarDestination(mc.player.getInventory(), durabilityReserve);
        if (hotbarDestination < 0) return beginFailure(mc, "hotbar-destination-became-unsafe");
        newTool = source.getItem().copy();
        oldTool = mc.player.getInventory().getItem(hotbarDestination).copy();
        mc.gameMode.handleContainerInput(menu.containerId, source.index, hotbarDestination,
                ContainerInput.SWAP, mc.player);
        swapSubmitted = true;
        phase = Phase.WAIT_SWAP;
        stableTicks = 0;
        detail = "waiting for server tool swap confirmation";
        TerraLevelerDiagnostics.log("TOOLBOX", "swap-request menu=" + menu.containerId
                + " source=" + source.index + " hotbar=" + hotbarDestination
                + " new=" + newTool + " old=" + oldTool);
        return Result.RUNNING;
    }

    private Result tickWaitSwap(Minecraft mc, AbstractContainerMenu menu) {
        if (menu == mc.player.inventoryMenu) {
            phase = Phase.WAIT_CLOSE;
            stableTicks = 0;
            return tickWaitClose(mc, menu);
        }
        if (menu.containerId != ownerMenuId) return finishFailure("container-changed-after-swap");

        Slot source = menuSlot(menu, sourceMenuSlot);
        boolean matched = source != null
                && sameStack(mc.player.getInventory().getItem(hotbarDestination), newTool)
                && sameStack(source.getItem(), oldTool);
        stableTicks = nextStableTicks(stableTicks, matched);
        if (!stableConfirmed(stableTicks)) return Result.RUNNING;

        mc.player.closeContainer();
        phase = Phase.WAIT_CLOSE;
        stableTicks = 0;
        detail = "waiting for closed tool-box inventory confirmation";
        TerraLevelerDiagnostics.log("TOOLBOX", "swap-confirmed menu=" + ownerMenuId
                + " source=" + sourceMenuSlot + " hotbar=" + hotbarDestination);
        return Result.RUNNING;
    }

    private Result tickWaitClose(Minecraft mc, AbstractContainerMenu menu) {
        if (menu != mc.player.inventoryMenu) {
            if (menu.containerId == ownerMenuId) return Result.RUNNING;
            return finishFailure("another-container-open-after-close");
        }

        ItemStack returnedBox = mc.player.getInventory().getItem(boxInventorySlot);
        boolean matched = sameSelectedToolBox(returnedBox)
                && sameStack(mc.player.getInventory().getItem(hotbarDestination), newTool)
                && boxContains(returnedBox, oldTool)
                && menu.getCarried().isEmpty();
        stableTicks = nextStableTicks(stableTicks, matched);
        if (!stableConfirmed(stableTicks)) return Result.RUNNING;

        phase = Phase.COMPLETE;
        detail = "tool-box swap complete";
        BoxScreenHider.release();
        TerraLevelerDiagnostics.log("TOOLBOX", "complete boxSlot=" + boxInventorySlot
                + " hotbar=" + hotbarDestination + " new=" + newTool + " old=" + oldTool);
        return Result.SUCCESS;
    }

    private Result tickWaitRollback(Minecraft mc, AbstractContainerMenu menu) {
        if (menu == mc.player.inventoryMenu) return finishFailure(failure);
        if (menu.containerId != ownerMenuId) return finishFailure(failure + ":rollback-menu-changed");
        Slot source = menuSlot(menu, sourceMenuSlot);
        boolean matched = source != null
                && sameStack(mc.player.getInventory().getItem(hotbarDestination), oldTool)
                && sameStack(source.getItem(), newTool);
        stableTicks = nextStableTicks(stableTicks, matched);
        if (!stableConfirmed(stableTicks)) return Result.RUNNING;
        mc.player.closeContainer();
        return finishFailure(failure);
    }

    private Result beginFailure(Minecraft mc, String reason) {
        failure = reason == null ? "tool-box-failed" : reason;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (swapSubmitted && menu.containerId == ownerMenuId && menu.getCarried().isEmpty()) {
            Slot source = menuSlot(menu, sourceMenuSlot);
            if (source != null && sameStack(mc.player.getInventory().getItem(hotbarDestination), newTool)
                    && sameStack(source.getItem(), oldTool)) {
                mc.gameMode.handleContainerInput(menu.containerId, source.index, hotbarDestination,
                        ContainerInput.SWAP, mc.player);
                phase = Phase.WAIT_ROLLBACK;
                stableTicks = 0;
                ticks = 0;
                detail = "rolling back tool-box swap";
                TerraLevelerDiagnostics.log("TOOLBOX", "rollback-request reason=" + failure
                        + " source=" + source.index + " hotbar=" + hotbarDestination);
                return Result.RUNNING;
            }
        }
        if (menu.containerId == ownerMenuId && menu.getCarried().isEmpty()) mc.player.closeContainer();
        return finishFailure(failure);
    }

    private BeginResult failBegin(String reason) {
        return failBegin(reason, BeginResult.FAILED_CONTINUE);
    }

    private BeginResult failBegin(String reason, BeginResult result) {
        failure = reason == null ? "tool-box-begin-failed" : reason;
        phase = Phase.FAILED;
        TerraLevelerDiagnostics.log("TOOLBOX", "begin-failed reason=" + failure);
        return result;
    }

    private Result finishFailure(String reason) {
        failure = reason == null ? "tool-box-failed" : reason;
        detail = "tool-box unavailable after safe rollback";
        phase = Phase.FAILED;
        BoxScreenHider.release();
        TerraLevelerDiagnostics.log("TOOLBOX", "failed reason=" + failure + " swapSubmitted=" + swapSubmitted);
        return Result.FAILED_CONTINUE;
    }

    private static ToolBoxSelectionRules.ToolKind requiredKind(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return ToolBoxSelectionRules.ToolKind.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return ToolBoxSelectionRules.ToolKind.SHOVEL;
        return ToolBoxSelectionRules.ToolKind.OTHER;
    }

    static ToolBoxSelectionRules.ToolKind toolKind(ItemStack stack) {
        if (stack != null && stack.is(ItemTags.PICKAXES)) return ToolBoxSelectionRules.ToolKind.PICKAXE;
        if (stack != null && stack.is(ItemTags.SHOVELS)) return ToolBoxSelectionRules.ToolKind.SHOVEL;
        return ToolBoxSelectionRules.ToolKind.OTHER;
    }

    private static ToolBoxSelectionRules.BoxSelection findToolBox(Minecraft mc, Inventory inventory,
                                                                  BlockState targetState, int reserve,
                                                                  List<String> allowedNames) {
        List<ToolBoxSelectionRules.BoxCandidate> candidates = new ArrayList<>();
        int size = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            candidates.add(new ToolBoxSelectionRules.BoxCandidate(slot, isShulkerBoxStack(stack), customName(stack),
                    boxHasSafeTool(mc, stack, targetState, reserve)));
        }
        ToolBoxSelectionRules.BoxSelection selection = ToolBoxSelectionRules.uniqueToolBox(candidates, allowedNames);
        if (selection.status() != ToolBoxSelectionRules.BoxStatus.FOUND) {
            StringBuilder boxes = new StringBuilder();
            for (ToolBoxSelectionRules.BoxCandidate candidate : candidates) {
                if (!candidate.shulker()) continue;
                boxes.append(' ').append(candidate.inventorySlot()).append(':').append(candidate.customName())
                        .append(candidate.hasRequestedTool() ? "(tool)" : "(none)");
            }
            TerraLevelerDiagnostics.log("TOOLBOX", "selection status=" + selection.status() + " required="
                    + requiredKind(targetState) + " names=" + allowedNames + " boxes=[" + boxes.toString().trim() + "]");
        }
        return selection;
    }

    private static int chooseHotbarDestination(Inventory inventory, int reserve) {
        return ToolBoxSelectionRules.hotbarDestination(inventory.getSelectedSlot(), hotbarCandidates(inventory), reserve);
    }

    private static HotbarEvacuation findHotbarEvacuation(Inventory inventory, int boxInventorySlot) {
        int emptyInventorySlot = -1;
        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                emptyInventorySlot = slot;
                break;
            }
        }
        if (emptyInventorySlot < 0) return null;
        int hotbarSlot = ToolBoxSelectionRules.hotbarEvacuationSlot(
                inventory.getSelectedSlot(), boxInventorySlot, hotbarCandidates(inventory));
        return hotbarSlot < 0 ? null : new HotbarEvacuation(hotbarSlot, emptyInventorySlot);
    }

    private static List<ToolBoxSelectionRules.HotbarCandidate> hotbarCandidates(Inventory inventory) {
        List<ToolBoxSelectionRules.HotbarCandidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            candidates.add(new ToolBoxSelectionRules.HotbarCandidate(slot, toolKind(stack), stack.isEmpty(),
                    ServerActionExecutor.remainingDurability(stack)));
        }
        return candidates;
    }

    private static List<Slot> ownedBoxSlots(AbstractContainerMenu menu, Inventory inventory) {
        Map<Container, List<Slot>> groups = new IdentityHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory) continue;
            groups.computeIfAbsent(slot.container, ignored -> new ArrayList<>()).add(slot);
        }
        List<Slot> selected = List.of();
        for (List<Slot> group : groups.values()) {
            if (group.size() == 27) {
                if (!selected.isEmpty()) return List.of();
                selected = group;
            }
        }
        return selected;
    }

    private static Slot inventoryMenuSlot(Minecraft mc, int inventorySlot) {
        for (Slot slot : mc.player.inventoryMenu.slots) {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventorySlot) return slot;
        }
        return null;
    }

    private static Slot menuSlot(AbstractContainerMenu menu, int menuSlot) {
        for (Slot slot : menu.slots) {
            if (slot.index == menuSlot) return slot;
        }
        return null;
    }

    private static String customName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    private boolean sameSelectedToolBox(ItemStack stack) {
        return isShulkerBoxStack(stack) && selectedBoxName != null && selectedBoxName.equals(customName(stack));
    }

    private static boolean boxHasSafeTool(Minecraft mc, ItemStack box, BlockState targetState, int reserve) {
        if (!isShulkerBoxStack(box)) return false;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) return false;
        ToolBoxSelectionRules.ToolKind required = requiredKind(targetState);
        return contents.nonEmptyItemCopyStream().anyMatch(stack -> toolKind(stack) == required
                && ServerActionExecutor.remainingDurability(stack) > reserve
                && ServerActionExecutor.isEffectiveMiningTool(
                ServerActionExecutor.effectiveToolScore(mc, stack, targetState)));
    }

    private static boolean isShulkerBoxStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean boxContains(ItemStack box, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return true;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents != null && contents.nonEmptyItemCopyStream().anyMatch(stack -> sameStack(stack, expected));
    }

    private static boolean sameStack(ItemStack actual, ItemStack expected) {
        boolean actualEmpty = actual == null || actual.isEmpty();
        boolean expectedEmpty = expected == null || expected.isEmpty();
        if (actualEmpty || expectedEmpty) return actualEmpty == expectedEmpty;
        return actual.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(actual, expected);
    }

    static int nextStableTicks(int current, boolean matched) {
        return matched ? Math.min(CONFIRM_TICKS, Math.max(0, current) + 1) : 0;
    }

    static boolean stableConfirmed(int stableTicks) {
        return stableTicks >= CONFIRM_TICKS;
    }

    enum BeginResult { STARTED, NEEDS_HOTBAR_SPACE, FAILED_CONTINUE }

    enum Result { RUNNING, SUCCESS, FAILED_CONTINUE }

    private record HotbarEvacuation(int hotbarSlot, int inventorySlot) { }

    private enum Phase { IDLE, WAIT_HOTBAR_SPACE, WAIT_MENU, WAIT_SWAP, WAIT_CLOSE, WAIT_ROLLBACK, COMPLETE, FAILED }
}
