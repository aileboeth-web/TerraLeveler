package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class InventoryDiscardTransaction {
   enum Result {
      IN_PROGRESS,
      COMPLETE,
      FAILED
   }

   private enum Phase {
      PICKUP_SOURCE,
      PLACE_BASELINE,
      PLACE_NEW,
      THROW_NEW,
      COMPLETE,
      FAILED
   }

   private enum PendingAction {
      NONE,
      PICKUP_SOURCE,
      PLACE_BASELINE,
      PLACE_NEW,
      THROW_NEW
   }

   private final Item item;
   private final int sourceMenuSlot;
   private final int destinationMenuSlot;
   private final int originalStackCount;
   private final int baselineToPreserve;
   private final int newStackCount;
   private Phase phase = Phase.PICKUP_SOURCE;
   private PendingAction pendingAction = PendingAction.NONE;
   private int baselineRemaining;
   private int expectedCarriedCount;
   private int expectedSourceCount;
   private int expectedDestinationCount;
   private int settleTicks;
   private int waitTicks;
   private int restoreTicks;
   private String failureReason;
   private static final int MAX_CURSOR_RESTORE_TICKS = 40;

   private InventoryDiscardTransaction(
      Item item,
      int sourceMenuSlot,
      int destinationMenuSlot,
      int originalStackCount,
      int baselineToPreserve
   ) {
      this.item = item;
      this.sourceMenuSlot = sourceMenuSlot;
      this.destinationMenuSlot = destinationMenuSlot;
      this.originalStackCount = originalStackCount;
      this.baselineToPreserve = baselineToPreserve;
      this.newStackCount = originalStackCount - baselineToPreserve;
      this.baselineRemaining = baselineToPreserve;
   }

   static InventoryDiscardTransaction begin(
      Item item,
      int sourceMenuSlot,
      int destinationMenuSlot,
      int originalStackCount,
      int baselineToPreserve
   ) {
      if (item == null || sourceMenuSlot < 0 || destinationMenuSlot < 0
         || originalStackCount <= 1 || baselineToPreserve <= 0
         || baselineToPreserve >= originalStackCount) {
         throw new IllegalArgumentException("invalid inventory discard split");
      }
      return new InventoryDiscardTransaction(item, sourceMenuSlot, destinationMenuSlot, originalStackCount, baselineToPreserve);
   }

   Item item() {
      return this.item;
   }

   boolean canCancelBeforeSubmission() {
      return this.phase == Phase.PICKUP_SOURCE && this.pendingAction == PendingAction.NONE;
   }

   int discardedCount() {
      return this.newStackCount;
   }

   String failureReason() {
      return this.failureReason == null ? "服务器未确认库存整理" : this.failureReason;
   }

   Result tick(Minecraft mc) {
      if (this.phase == Phase.COMPLETE) return Result.COMPLETE;
      if (this.phase == Phase.FAILED) return Result.FAILED;
      if (mc == null || mc.player == null || mc.gameMode == null
         || mc.player.containerMenu != mc.player.inventoryMenu) {
         return fail("玩家库存菜单不可用");
      }

      if (this.pendingAction != PendingAction.NONE) {
         if (this.settleTicks > 0) {
            this.settleTicks--;
            return Result.IN_PROGRESS;
         }
         if (this.confirmPending(mc)) {
            PendingAction confirmedAction = this.pendingAction;
            this.pendingAction = PendingAction.NONE;
            this.waitTicks = 0;
            this.advanceAfterConfirmation(confirmedAction);
         } else if (++this.waitTicks > 20) {
            return fail("服务器未确认库存拆分动作；" + this.actualState(mc));
         }
         return this.phase == Phase.COMPLETE ? Result.COMPLETE : Result.IN_PROGRESS;
      }

      switch (this.phase) {
         case PICKUP_SOURCE -> this.sendPickupSource(mc);
         case PLACE_BASELINE -> {
            if (this.baselineRemaining <= 0) {
               this.phase = Phase.PLACE_NEW;
            } else {
               this.sendBaselinePlace(mc);
            }
         }
         case PLACE_NEW -> this.sendPlaceNew(mc);
         case THROW_NEW -> this.sendThrowNew(mc);
         case COMPLETE -> {
            return Result.COMPLETE;
         }
         case FAILED -> {
            return Result.FAILED;
         }
      }
      return this.phase == Phase.FAILED ? Result.FAILED : Result.IN_PROGRESS;
   }

   private void sendPickupSource(Minecraft mc) {
      Slot source = mc.player.inventoryMenu.getSlot(this.sourceMenuSlot);
      if (!mc.player.inventoryMenu.getCarried().isEmpty()
         || !source.getItem().is(this.item)
         || source.getItem().getCount() != this.originalStackCount) {
         fail("源物品组在拆分前已变化");
         return;
      }
      this.logAction("pickup-source", mc);
      this.send(mc, this.sourceMenuSlot, 0, ContainerInput.PICKUP);
      this.pendingAction = PendingAction.PICKUP_SOURCE;
      this.expectedCarriedCount = this.originalStackCount;
      this.expectedSourceCount = 0;
   }

   private void sendBaselinePlace(Minecraft mc) {
      ItemStack carried = mc.player.inventoryMenu.getCarried();
      ItemStack destination = mc.player.inventoryMenu.getSlot(this.destinationMenuSlot).getItem();
      if (!carried.is(this.item) || carried.getCount() <= 0
         || (!destination.isEmpty() && !destination.is(this.item))) {
         fail("无法把开工基线放入临时槽位");
         return;
      }
      this.logAction("place-baseline", mc);
      this.send(mc, this.destinationMenuSlot, 1, ContainerInput.PICKUP);
      this.pendingAction = PendingAction.PLACE_BASELINE;
      this.expectedCarriedCount = carried.getCount() - 1;
      this.expectedDestinationCount = destination.isEmpty() ? 1 : destination.getCount() + 1;
   }

   private void sendPlaceNew(Minecraft mc) {
      ItemStack carried = mc.player.inventoryMenu.getCarried();
      if (!carried.is(this.item) || carried.getCount() != this.newStackCount
         || !mc.player.inventoryMenu.getSlot(this.sourceMenuSlot).getItem().isEmpty()) {
         fail("无法恢复待丢弃物品组");
         return;
      }
      this.logAction("place-new", mc);
      this.send(mc, this.sourceMenuSlot, 0, ContainerInput.PICKUP);
      this.pendingAction = PendingAction.PLACE_NEW;
      this.expectedCarriedCount = 0;
      this.expectedSourceCount = this.newStackCount;
   }

   private void sendThrowNew(Minecraft mc) {
      ItemStack source = mc.player.inventoryMenu.getSlot(this.sourceMenuSlot).getItem();
      if (!mc.player.inventoryMenu.getCarried().isEmpty()
         || !source.is(this.item)
         || source.getCount() != this.newStackCount) {
         fail("待丢弃物品组恢复后数量不一致");
         return;
      }
      this.logAction("throw-new", mc);
      this.send(mc, this.sourceMenuSlot, 1, ContainerInput.THROW);
      this.pendingAction = PendingAction.THROW_NEW;
      this.expectedCarriedCount = 0;
      this.expectedSourceCount = 0;
   }

   private boolean confirmPending(Minecraft mc) {
      ItemStack carried = mc.player.inventoryMenu.getCarried();
      ItemStack source = mc.player.inventoryMenu.getSlot(this.sourceMenuSlot).getItem();
      ItemStack destination = mc.player.inventoryMenu.getSlot(this.destinationMenuSlot).getItem();
      return switch (this.pendingAction) {
         case PICKUP_SOURCE -> carried.is(this.item)
            && carried.getCount() == this.expectedCarriedCount
            && source.isEmpty();
         case PLACE_BASELINE -> carried.is(this.item)
            && carried.getCount() == this.expectedCarriedCount
            && destination.is(this.item)
            && destination.getCount() == this.expectedDestinationCount;
         case PLACE_NEW -> carried.isEmpty()
            && source.is(this.item)
            && source.getCount() == this.expectedSourceCount;
         case THROW_NEW -> carried.isEmpty() && source.isEmpty();
         case NONE -> false;
      };
   }

   private void advanceAfterConfirmation(PendingAction confirmedAction) {
      switch (confirmedAction) {
         case PICKUP_SOURCE -> this.phase = Phase.PLACE_BASELINE;
         case PLACE_BASELINE -> {
            this.baselineRemaining--;
            if (this.baselineRemaining <= 0) this.phase = Phase.PLACE_NEW;
         }
         case PLACE_NEW -> this.phase = Phase.THROW_NEW;
         case THROW_NEW -> this.phase = Phase.COMPLETE;
         case NONE -> {
         }
      }
   }

   private void send(Minecraft mc, int menuSlot, int button, ContainerInput input) {
      mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, button, input, mc.player);
      this.settleTicks = 2;
      this.waitTicks = 0;
   }

   private Result fail(String reason) {
      this.phase = Phase.FAILED;
      this.failureReason = reason;
      return Result.FAILED;
   }

   private void logAction(String action, Minecraft mc) {
      TerraLevelerDiagnostics.log(
         "MATERIAL",
         "discard-txn-action=" + action
            + " item=" + this.item
            + " sourceMenu=" + this.sourceMenuSlot
            + " destinationMenu=" + this.destinationMenuSlot
            + " state=" + this.actualState(mc)
      );
   }

   /**
    * After a failed split the stack may still sit on the cursor. Puts it back
    * into the source slot (or any compatible inventory slot) and keeps
    * reporting {@code true} until the cursor is observed empty or a short
    * bound expires; it never drops anything.
    */
   boolean restoreCursor(Minecraft mc) {
      if (mc == null || mc.player == null || mc.gameMode == null
         || mc.player.containerMenu != mc.player.inventoryMenu) {
         return false;
      }
      ItemStack carried = mc.player.inventoryMenu.getCarried();
      if (carried.isEmpty() || ++this.restoreTicks > MAX_CURSOR_RESTORE_TICKS) {
         return false;
      }
      if (this.restoreTicks % 5 == 1) {
         int slot = cursorReturnSlot(mc, carried);
         if (slot >= 0) {
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
            this.logAction("restore-cursor", mc);
         }
      }
      return true;
   }

   private int cursorReturnSlot(Minecraft mc, ItemStack carried) {
      int[] preferred = {this.sourceMenuSlot, this.destinationMenuSlot};
      for (int slot : preferred) {
         if (acceptsCursor(mc, slot, carried)) return slot;
      }
      // Player inventory and hotbar slots of the inventory menu.
      for (int slot = 9; slot <= 44; slot++) {
         if (acceptsCursor(mc, slot, carried)) return slot;
      }
      return -1;
   }

   private static boolean acceptsCursor(Minecraft mc, int menuSlot, ItemStack carried) {
      if (menuSlot < 0 || menuSlot >= mc.player.inventoryMenu.slots.size()) return false;
      ItemStack existing = mc.player.inventoryMenu.getSlot(menuSlot).getItem();
      return existing.isEmpty()
         || ItemStack.isSameItemSameComponents(existing, carried)
            && existing.getCount() + carried.getCount() <= existing.getMaxStackSize();
   }

   private String actualState(Minecraft mc) {
      if (mc == null || mc.player == null || mc.player.containerMenu == null) {
         return "menu=unavailable";
      }
      ItemStack carried = mc.player.inventoryMenu.getCarried();
      ItemStack source = this.sourceMenuSlot >= 0 && this.sourceMenuSlot < mc.player.inventoryMenu.slots.size()
         ? mc.player.inventoryMenu.getSlot(this.sourceMenuSlot).getItem()
         : ItemStack.EMPTY;
      ItemStack destination = this.destinationMenuSlot >= 0 && this.destinationMenuSlot < mc.player.inventoryMenu.slots.size()
         ? mc.player.inventoryMenu.getSlot(this.destinationMenuSlot).getItem()
         : ItemStack.EMPTY;
      return "phase=" + this.phase
         + " pending=" + this.pendingAction
         + " carried=" + stackState(carried)
         + " source=" + stackState(source)
         + " destination=" + stackState(destination)
         + " wait=" + this.waitTicks;
   }

   private static String stackState(ItemStack stack) {
      return stack == null || stack.isEmpty() ? "empty" : stack.getItem() + "x" + stack.getCount();
   }
}
