package cn.terraleveler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

/**
 * A short, server-confirmed bulk drop performed while looking away from future work.
 * One transaction may throw stacks of several item types (0.8.72): in stone the
 * inventory refills within seconds, so every aimed stop throws all queued pickups.
 */
final class DirectedItemDiscardTransaction {
   enum Result {
      IN_PROGRESS,
      COMPLETE,
      FAILED
   }

   /** One inventory stack to throw; {@code item} null means the transaction's primary item. */
   record Source(int menuSlot, int count, Item item) {
      Source {
         if (menuSlot < 0 || count <= 0) throw new IllegalArgumentException("invalid discard source");
      }

      Source(int menuSlot, int count) {
         this(menuSlot, count, null);
      }
   }

   private static final int AIM_SETTLE_TICKS = 2;
   private static final int SERVER_CONFIRM_TICKS = 12;
   private static final int SERVER_EMPTY_STABLE_TICKS = 3;

   private final Item item;
   private List<Source> sources;
   private boolean[] clearedOnce;
   private int discardedCount;
   private final float discardYaw;
   private final float discardPitch;
   private float originalYaw;
   private float originalPitch;
   private int aimTicks = AIM_SETTLE_TICKS;
   private int confirmTicks;
   private int emptyStableTicks;
   private boolean aimed;
   private boolean submitted;
   private boolean viewRestored;
   private String failureReason;

   private DirectedItemDiscardTransaction(Item item, List<Source> sources, float yaw, float pitch) {
      if (item == null || sources == null || sources.isEmpty()) {
         throw new IllegalArgumentException("invalid directed discard");
      }
      this.item = item;
      this.sources = List.copyOf(sources);
      this.clearedOnce = new boolean[this.sources.size()];
      this.discardedCount = this.sources.stream().mapToInt(Source::count).sum();
      this.discardYaw = yaw;
      this.discardPitch = pitch;
   }

   static DirectedItemDiscardTransaction begin(Item item, List<Source> sources, float yaw, float pitch) {
      return new DirectedItemDiscardTransaction(item, sources, yaw, pitch);
   }

   Item item() {
      return this.item;
   }

   int discardedCount() {
      return this.discardedCount;
   }

   /** Thrown amount per item type, in source order. */
   Map<Item, Integer> discardedByItem() {
      Map<Item, Integer> result = new LinkedHashMap<>();
      for (Source source : this.sources) {
         result.merge(this.itemOf(source), source.count(), Integer::sum);
      }
      return result;
   }

   String failureReason() {
      return this.failureReason == null ? "服务器未确认定向丢弃" : this.failureReason;
   }

   Result tick(Minecraft mc) {
      if (mc == null || mc.player == null || mc.gameMode == null
         || mc.player.containerMenu != mc.player.inventoryMenu
         || !mc.player.inventoryMenu.getCarried().isEmpty()) {
         return fail(mc, "玩家库存菜单不可用");
      }

      if (!this.aimed) {
         this.originalYaw = mc.player.getYRot();
         this.originalPitch = mc.player.getXRot();
         this.aimed = true;
         TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-aim item=" + this.item
            + " yaw=" + this.discardYaw + " pitch=" + this.discardPitch);
      }
      mc.player.setYRot(this.discardYaw);
      mc.player.setXRot(this.discardPitch);

      if (!this.submitted) {
         if (this.aimTicks-- > 0) return Result.IN_PROGRESS;
         // Pickups keep merging into partial stacks while aiming. A stack that still holds
         // its item is thrown whole at its current size; only a slot whose item changed
         // is left out (0.8.71 failed the whole throw on any count change).
         List<Source> current = new ArrayList<>(this.sources.size());
         for (Source source : this.sources) {
            if (source.menuSlot() >= mc.player.inventoryMenu.slots.size()) continue;
            var stack = mc.player.inventoryMenu.getSlot(source.menuSlot()).getItem();
            if (!stack.isEmpty() && stack.is(this.itemOf(source))) {
               current.add(new Source(source.menuSlot(), stack.getCount(), this.itemOf(source)));
            }
         }
         if (current.isEmpty()) {
            return fail(mc, "瞄准期间待丢弃物品槽已变化");
         }
         this.sources = List.copyOf(current);
         this.clearedOnce = new boolean[this.sources.size()];
         this.discardedCount = this.sources.stream().mapToInt(Source::count).sum();
         for (Source source : this.sources) {
            mc.gameMode.handleContainerInput(
               mc.player.inventoryMenu.containerId, source.menuSlot(), 1, ContainerInput.THROW, mc.player
            );
         }
         this.submitted = true;
         TerraLevelerDiagnostics.log("MATERIAL", "directed-discard-submitted item=" + this.item
            + " stacks=" + this.sources.size() + " count=" + this.discardedCount
            + " types=" + this.discardedByItem().size());
         return Result.IN_PROGRESS;
      }

      if (sourcesCleared(mc)) {
         this.emptyStableTicks++;
      } else {
         this.emptyStableTicks = 0;
      }
      if (this.emptyStableTicks >= SERVER_EMPTY_STABLE_TICKS) {
         restoreView(mc);
         return Result.COMPLETE;
      }
      if (++this.confirmTicks > SERVER_CONFIRM_TICKS) {
         return fail(mc, "服务器未在 " + SERVER_CONFIRM_TICKS + " tick 内确认物品槽清空");
      }
      return Result.IN_PROGRESS;
   }

   void cancel(Minecraft mc) {
      restoreView(mc);
   }

   private Item itemOf(Source source) {
      return source.item() == null ? this.item : source.item();
   }

   /**
    * A source counts as thrown once the server has shown it changed. A pickup may refill
    * the freed slot right away (0.8.71 reported ten false failures that way), so a later
    * refill does not undo the confirmation.
    */
   private boolean sourcesCleared(Minecraft mc) {
      for (int index = 0; index < this.sources.size(); index++) {
         Source source = this.sources.get(index);
         if (source.menuSlot() >= mc.player.inventoryMenu.slots.size()) return false;
         var stack = mc.player.inventoryMenu.getSlot(source.menuSlot()).getItem();
         if (sourceCleared(stack.isEmpty(), stack.is(this.itemOf(source)), stack.getCount(), source.count())) {
            this.clearedOnce[index] = true;
         }
         if (!this.clearedOnce[index]) return false;
      }
      return true;
   }

   static boolean sourceCleared(boolean empty, boolean sameItem, int currentCount, int thrownCount) {
      return empty || !sameItem || currentCount != thrownCount;
   }

   private Result fail(Minecraft mc, String reason) {
      this.failureReason = reason;
      restoreView(mc);
      return Result.FAILED;
   }

   private void restoreView(Minecraft mc) {
      if (this.viewRestored || !this.aimed || mc == null || mc.player == null) return;
      mc.player.setYRot(this.originalYaw);
      mc.player.setXRot(this.originalPitch);
      this.viewRestored = true;
   }
}
