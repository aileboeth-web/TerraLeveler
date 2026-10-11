package cn.terraleveler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Moves one kind of item between a shulker box carried in the inventory and the inventory
 * (0.9.08, replacing the Carpet Org Addition "quickShulker" cursor clicks). The box is opened
 * with QuickShulker and whole stacks are shift-clicked; nothing is ever held on the cursor,
 * so an interrupted transaction cannot lose items: every stack is in the box or in the
 * inventory. The box screen is not drawn ({@link BoxScreenHider}).
 *
 * <p>If the server never opens the box (no QuickShulker on the server), the transaction
 * fails with {@link #menuNeverOpened()} true.</p>
 */
final class ShulkerBoxMenuTransaction {
    enum Kind { EXTRACT, STORE }

    enum Result { RUNNING, SUCCESS, FAILED }

    static final int OPEN_TIMEOUT_TICKS = 60;
    static final int MOVE_CONFIRM_TICKS = 3;
    static final int MOVE_TIMEOUT_TICKS = 40;
    static final int CLOSE_CONFIRM_TICKS = 3;
    static final int CLOSE_TIMEOUT_TICKS = 60;
    static final int MAX_MOVES = 27;
    /** Ticks to wait for the box contents after the menu opened (0.9.09: the 0.9.08 test saw an empty box once). */
    static final int CONTENT_SYNC_TIMEOUT_TICKS = 20;
    /** An extraction that sees no stack at first looks this many more ticks before giving up. */
    static final int EMPTY_RECHECK_TICKS = 5;
    private static final int BOX_SLOTS = 27;

    private final QuickShulkerBridge bridge = new QuickShulkerBridge();
    private Phase phase = Phase.IDLE;
    private Kind kind = Kind.EXTRACT;
    private Item item;
    private int boxInventorySlot = -1;
    private int amount;
    private int protectedInventorySlot = -1;
    private int looseBefore;
    private int boxBefore;
    private int ownerMenuId = -1;
    private int pendingMenuSlot = -1;
    private int pendingCount;
    private int moves;
    private int emptyChecks;
    private int ticks;
    private int stableTicks;
    private boolean menuOpened;
    private String failure = "";
    private String detail = "";

    /**
     * Starts opening the box. {@code amount} is how many items should move (whole stacks,
     * so slightly more can move); {@code protectedInventorySlot} is never used as a store
     * source (the selected hotbar slot, which inventory mods like to refill).
     */
    boolean begin(Minecraft mc, Kind kind, Item item, int boxInventorySlot, int amount, int protectedInventorySlot) {
        reset();
        this.kind = kind;
        this.item = item;
        this.boxInventorySlot = boxInventorySlot;
        this.amount = Math.max(1, amount);
        this.protectedInventorySlot = protectedInventorySlot;
        if (mc == null || mc.player == null || mc.gameMode == null || item == null) return failBegin("missing-client-state");
        if (mc.player.containerMenu != mc.player.inventoryMenu) return failBegin("another-container-open");
        if (!mc.player.inventoryMenu.getCarried().isEmpty()) return failBegin("cursor-not-empty");
        Inventory inventory = mc.player.getInventory();
        ItemStack box = boxInventorySlot < 0 ? ItemStack.EMPTY : inventory.getItem(boxInventorySlot);
        if (!isShulkerBoxStack(box)) return failBegin("box-not-in-slot");
        Slot menuSlot = inventoryMenuSlot(mc, boxInventorySlot);
        if (menuSlot == null) return failBegin("box-menu-slot-not-found");
        looseBefore = inventoryCount(inventory, item);
        boxBefore = containedCount(box, item);
        BoxScreenHider.keepAlive();
        QuickShulkerBridge.OpenResult open = bridge.open(menuSlot.index);
        if (!open.sent()) {
            BoxScreenHider.release();
            return failBegin(open.failure());
        }
        phase = Phase.WAIT_MENU;
        detail = "等待 QuickShulker 打开潜影盒";
        TerraLevelerDiagnostics.log("MATERIAL", "box-open-request kind=" + kind + " item=" + itemName(item)
                + " boxSlot=" + boxInventorySlot + " menuSlot=" + menuSlot.index + " amount=" + this.amount
                + " looseBefore=" + looseBefore + " boxBefore=" + boxBefore);
        return true;
    }

    Result tick(Minecraft mc) {
        if (phase == Phase.IDLE || phase == Phase.FAILED) return Result.FAILED;
        if (phase == Phase.COMPLETE) return Result.SUCCESS;
        if (mc == null || mc.player == null || mc.gameMode == null) return fail(mc, "missing-client-state");
        BoxScreenHider.keepAlive();
        ticks++;
        AbstractContainerMenu menu = mc.player.containerMenu;
        return switch (phase) {
            case WAIT_MENU -> tickWaitMenu(mc, menu);
            case WAIT_CONTENTS -> tickWaitContents(mc, menu);
            case MOVE -> tickMove(mc, menu);
            case WAIT_MOVE -> tickWaitMove(mc, menu);
            case WAIT_CLOSE -> tickWaitClose(mc, menu);
            default -> fail(mc, "unexpected-phase-" + phase);
        };
    }

    /** Stops at once: the box is closed if it is open; nothing can be lost (no cursor use). */
    void cancel(Minecraft mc, String reason) {
        if (phase == Phase.IDLE || phase == Phase.COMPLETE || phase == Phase.FAILED) return;
        closeOwnedMenu(mc);
        failure = reason;
        phase = Phase.FAILED;
        BoxScreenHider.release();
        TerraLevelerDiagnostics.log("MATERIAL", "box-transaction-cancel kind=" + kind + " reason=" + reason);
    }

    boolean active() {
        return phase != Phase.IDLE && phase != Phase.COMPLETE && phase != Phase.FAILED;
    }

    boolean menuNeverOpened() {
        return !menuOpened;
    }

    String failure() { return failure; }

    String detail() { return detail; }

    String phaseName() { return phase.name(); }

    /** Items moved so far (positive), measured on the player's inventory. */
    int movedCount(Minecraft mc) {
        if (mc == null || mc.player == null || item == null) return 0;
        int loose = inventoryCount(mc.player.getInventory(), item);
        return kind == Kind.EXTRACT ? loose - looseBefore : looseBefore - loose;
    }

    void reset() {
        phase = Phase.IDLE;
        item = null;
        boxInventorySlot = -1;
        amount = 0;
        protectedInventorySlot = -1;
        looseBefore = 0;
        boxBefore = 0;
        ownerMenuId = -1;
        pendingMenuSlot = -1;
        pendingCount = 0;
        moves = 0;
        emptyChecks = 0;
        ticks = 0;
        stableTicks = 0;
        menuOpened = false;
        failure = "";
        detail = "";
    }

    private Result tickWaitMenu(Minecraft mc, AbstractContainerMenu menu) {
        if (menu == mc.player.inventoryMenu) {
            if (ticks > OPEN_TIMEOUT_TICKS) return fail(mc, "menu-open-timeout");
            detail = "等待 QuickShulker 打开潜影盒";
            return Result.RUNNING;
        }
        if (ownedBoxSlots(menu, mc.player.getInventory()).size() != BOX_SLOTS) {
            if (ticks > OPEN_TIMEOUT_TICKS) return fail(mc, "unexpected-container-layout");
            return Result.RUNNING;
        }
        ownerMenuId = menu.containerId;
        menuOpened = true;
        // The menu exists before the server has sent what is in the box: a fresh client menu
        // has state id 0 until its contents arrive.
        phase = Phase.WAIT_CONTENTS;
        ticks = 0;
        return tickWaitContents(mc, menu);
    }

    private Result tickWaitContents(Minecraft mc, AbstractContainerMenu menu) {
        if (menu.containerId != ownerMenuId) return fail(mc, "container-changed");
        if (menu.getStateId() == 0 && ticks <= CONTENT_SYNC_TIMEOUT_TICKS) {
            detail = "等待潜影盒内容同步";
            return Result.RUNNING;
        }
        phase = Phase.MOVE;
        TerraLevelerDiagnostics.log("MATERIAL", "box-menu-contents menu=" + ownerMenuId + " kind=" + kind
                + " stateId=" + menu.getStateId() + " waitedTicks=" + ticks);
        ticks = 0;
        return tickMove(mc, menu);
    }

    private Result tickMove(Minecraft mc, AbstractContainerMenu menu) {
        if (menu.containerId != ownerMenuId) return fail(mc, "container-changed");
        if (!menu.getCarried().isEmpty()) {
            detail = "等待鼠标上的物品放下";
            return ticks > MOVE_TIMEOUT_TICKS ? beginClose(mc, "cursor-busy") : Result.RUNNING;
        }
        if (movedCount(mc) >= amount || moves >= MAX_MOVES) return beginClose(mc, "amount-reached");
        Slot source = nextSource(mc, menu);
        if (source == null && kind == Kind.EXTRACT && moves == 0 && emptyChecks++ < EMPTY_RECHECK_TICKS) {
            detail = "等待潜影盒内容同步";
            return Result.RUNNING;
        }
        if (source == null) return beginClose(mc, "no-more-source");
        pendingMenuSlot = source.index;
        pendingCount = source.getItem().getCount();
        mc.gameMode.handleContainerInput(menu.containerId, source.index, 0, ContainerInput.QUICK_MOVE, mc.player);
        moves++;
        phase = Phase.WAIT_MOVE;
        ticks = 0;
        stableTicks = 0;
        detail = kind == Kind.EXTRACT ? "从潜影盒取出 " + itemName(item) : "把 " + itemName(item) + " 存入潜影盒";
        return Result.RUNNING;
    }

    private Result tickWaitMove(Minecraft mc, AbstractContainerMenu menu) {
        if (menu.containerId != ownerMenuId) return fail(mc, "container-changed-during-move");
        Slot slot = menuSlot(menu, pendingMenuSlot);
        int now = slot == null || !slot.getItem().is(item) ? 0 : slot.getItem().getCount();
        stableTicks = now < pendingCount ? stableTicks + 1 : 0;
        if (stableTicks >= MOVE_CONFIRM_TICKS) {
            phase = Phase.MOVE;
            ticks = 0;
            return Result.RUNNING;
        }
        // The stack did not move: no room left on the other side.
        if (ticks > MOVE_TIMEOUT_TICKS) return beginClose(mc, "no-room");
        return Result.RUNNING;
    }

    private Result beginClose(Minecraft mc, String reason) {
        closeOwnedMenu(mc);
        phase = Phase.WAIT_CLOSE;
        ticks = 0;
        stableTicks = 0;
        detail = "关闭潜影盒并确认库存";
        TerraLevelerDiagnostics.log("MATERIAL", "box-close kind=" + kind + " reason=" + reason
                + " moves=" + moves + " moved=" + movedCount(mc));
        return Result.RUNNING;
    }

    private Result tickWaitClose(Minecraft mc, AbstractContainerMenu menu) {
        if (menu != mc.player.inventoryMenu) {
            if (menu.containerId == ownerMenuId) closeOwnedMenu(mc);
            return ticks > CLOSE_TIMEOUT_TICKS ? fail(mc, "close-timeout") : Result.RUNNING;
        }
        stableTicks = menu.getCarried().isEmpty() ? stableTicks + 1 : 0;
        if (stableTicks < CLOSE_CONFIRM_TICKS) {
            return ticks > CLOSE_TIMEOUT_TICKS ? fail(mc, "close-confirm-timeout") : Result.RUNNING;
        }
        int moved = movedCount(mc);
        Inventory inventory = mc.player.getInventory();
        int boxAfter = boxInventorySlot >= 0 ? containedCount(inventory.getItem(boxInventorySlot), item) : -1;
        phase = Phase.COMPLETE;
        BoxScreenHider.release();
        detail = kind == Kind.EXTRACT ? "已从潜影盒取出 " + itemName(item) : "已把 " + itemName(item) + " 存入潜影盒";
        TerraLevelerDiagnostics.log("MATERIAL", "box-transaction-complete kind=" + kind + " item=" + itemName(item)
                + " moved=" + moved + " looseBefore=" + looseBefore + " looseNow=" + inventoryCount(inventory, item)
                + " boxBefore=" + boxBefore + " boxNow=" + boxAfter + " moves=" + moves);
        return Result.SUCCESS;
    }

    private Slot nextSource(Minecraft mc, AbstractContainerMenu menu) {
        Inventory inventory = mc.player.getInventory();
        List<Slot> candidates = new ArrayList<>();
        if (kind == Kind.EXTRACT) {
            candidates.addAll(ownedBoxSlots(menu, inventory));
        } else {
            for (Slot slot : menu.slots) {
                if (slot.container == inventory && slot.getContainerSlot() < 36) candidates.add(slot);
            }
        }
        int[] containerSlots = new int[candidates.size()];
        int[] counts = new int[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            Slot slot = candidates.get(index);
            containerSlots[index] = kind == Kind.EXTRACT ? -1 : slot.getContainerSlot();
            counts[index] = slot.getItem().is(item) ? slot.getItem().getCount() : 0;
        }
        int chosen = nextSourceIndex(kind, containerSlots, counts, protectedInventorySlot);
        return chosen < 0 ? null : candidates.get(chosen);
    }

    /**
     * Which candidate to shift-click next: EXTRACT takes the box's stacks in order; STORE
     * skips the protected slot and empties the main inventory (9-35) before the hotbar,
     * smallest stack first so a partial stack does not stay behind.
     */
    static int nextSourceIndex(Kind kind, int[] inventorySlots, int[] counts, int protectedInventorySlot) {
        int best = -1;
        for (int index = 0; index < counts.length; index++) {
            if (counts[index] <= 0) continue;
            if (kind == Kind.EXTRACT) return index;
            int slot = inventorySlots[index];
            if (slot == protectedInventorySlot) continue;
            if (best < 0) {
                best = index;
                continue;
            }
            boolean hotbar = inventorySlots[index] < 9;
            boolean bestHotbar = inventorySlots[best] < 9;
            if (hotbar != bestHotbar ? !hotbar : counts[index] < counts[best]) best = index;
        }
        return best;
    }

    private void closeOwnedMenu(Minecraft mc) {
        if (mc == null || mc.player == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != mc.player.inventoryMenu && menu.containerId == ownerMenuId && menu.getCarried().isEmpty()) {
            mc.player.closeContainer();
        }
    }

    private boolean failBegin(String reason) {
        failure = reason == null ? "box-begin-failed" : reason;
        phase = Phase.FAILED;
        TerraLevelerDiagnostics.log("MATERIAL", "box-begin-failed kind=" + kind + " reason=" + failure);
        return false;
    }

    private Result fail(Minecraft mc, String reason) {
        closeOwnedMenu(mc);
        failure = reason;
        phase = Phase.FAILED;
        BoxScreenHider.release();
        TerraLevelerDiagnostics.log("MATERIAL", "box-transaction-failed kind=" + kind + " reason=" + reason
                + " menuOpened=" + menuOpened + " moved=" + movedCount(mc));
        return Result.FAILED;
    }

    private static List<Slot> ownedBoxSlots(AbstractContainerMenu menu, Inventory inventory) {
        Map<Container, List<Slot>> groups = new IdentityHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory) continue;
            groups.computeIfAbsent(slot.container, ignored -> new ArrayList<>()).add(slot);
        }
        List<Slot> selected = List.of();
        for (List<Slot> group : groups.values()) {
            if (group.size() == BOX_SLOTS) {
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

    static int inventoryCount(Inventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    static int containedCount(ItemStack box, Item item) {
        if (!isShulkerBoxStack(box) || item == null || box.get(DataComponents.CONTAINER_LOOT) != null) return 0;
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents == null ? 0 : contents.nonEmptyItemCopyStream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    static boolean isShulkerBoxStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static String itemName(Item item) {
        return item == null ? "unknown" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private enum Phase { IDLE, WAIT_MENU, WAIT_CONTENTS, MOVE, WAIT_MOVE, WAIT_CLOSE, COMPLETE, FAILED }
}
