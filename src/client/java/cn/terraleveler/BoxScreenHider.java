package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Keeps the shulker box screen that TerraLeveler opens through QuickShulker off the screen
 * (0.9.08). Vanilla sets {@code player.containerMenu} before it calls
 * {@code Minecraft.setScreen}, so skipping only the setScreen call leaves the menu open
 * and usable for clicks while nothing is drawn and the mouse stays captured.
 *
 * <p>Only container screens are hidden, and only while a box transaction keeps this
 * alive. If a transaction disappears without closing its box, {@link #tick} closes the
 * hidden menu after {@link #ORPHAN_CLOSE_MILLIS}.</p>
 */
public final class BoxScreenHider {
    /** A transaction must call {@link #keepAlive} at least this often. */
    static final long ALIVE_MILLIS = 1_000L;
    static final long ORPHAN_CLOSE_MILLIS = 2_000L;

    private static long aliveUntilMillis;
    private static int hiddenMenuId = -1;
    private static long hiddenSinceMillis;

    private BoxScreenHider() { }

    /** Called every tick by a box transaction while it may have a box open. */
    static void keepAlive() {
        aliveUntilMillis = System.currentTimeMillis() + ALIVE_MILLIS;
    }

    /** Called when a box transaction is finished; a box it left open is closed by {@link #tick}. */
    static void release() {
        aliveUntilMillis = 0L;
    }

    static boolean active() {
        return System.currentTimeMillis() < aliveUntilMillis;
    }

    /** Mixin hook on {@code Minecraft.setScreen}: true when this screen must not be shown. */
    public static boolean shouldHide(Screen screen) {
        if (!active() || !(screen instanceof AbstractContainerScreen<?> containerScreen)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || containerScreen.getMenu() != mc.player.containerMenu
                || containerScreen.getMenu() == mc.player.inventoryMenu) {
            return false;
        }
        hiddenMenuId = containerScreen.getMenu().containerId;
        hiddenSinceMillis = System.currentTimeMillis();
        TerraLevelerDiagnostics.log("MATERIAL", "box-screen-hidden menu=" + hiddenMenuId
                + " screen=" + screen.getClass().getSimpleName());
        return true;
    }

    /** Safety net, every client tick: close a hidden box nobody is using any more. */
    static void tick(Minecraft mc) {
        if (hiddenMenuId < 0 || mc.player == null) return;
        if (mc.player.containerMenu.containerId != hiddenMenuId || mc.player.containerMenu == mc.player.inventoryMenu) {
            hiddenMenuId = -1;
            return;
        }
        if (active() || System.currentTimeMillis() - hiddenSinceMillis < ORPHAN_CLOSE_MILLIS) return;
        TerraLevelerDiagnostics.log("MATERIAL", "box-screen-orphan-closed menu=" + hiddenMenuId);
        if (mc.player.containerMenu.getCarried().isEmpty()) mc.player.closeContainer();
        hiddenMenuId = -1;
    }
}
