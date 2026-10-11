package cn.terraleveler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

final class QuickShulkerBridge {
    private static final String MOD_ID = "quickshulker";
    private static final String OPEN_PACKET_CLASS = "net.kyrptonaught.quickshulker.network.OpenShulkerPacket";
    private Method openMethod;
    private boolean resolutionAttempted;
    private String resolutionFailure;

    OpenResult open(int menuSlot) {
        if (menuSlot < 0) return new OpenResult(false, "invalid-menu-slot");
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return new OpenResult(false, "quickshulker-not-loaded");
        }
        if (!resolve()) return new OpenResult(false, resolutionFailure);
        try {
            openMethod.invoke(null, menuSlot);
            return new OpenResult(true, null);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return new OpenResult(false, "quickshulker-open-failed:" + exception.getClass().getSimpleName());
        } catch (LinkageError error) {
            return new OpenResult(false, "quickshulker-linkage-failed:" + error.getClass().getSimpleName());
        }
    }

    private boolean resolve() {
        if (resolutionAttempted) return openMethod != null;
        resolutionAttempted = true;
        try {
            Class<?> packetClass = Class.forName(OPEN_PACKET_CLASS);
            openMethod = packetClass.getMethod("sendOpenPacket", int.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError exception) {
            resolutionFailure = "quickshulker-api-unavailable:" + exception.getClass().getSimpleName();
            return false;
        }
    }

    record OpenResult(boolean sent, String failure) { }
}
