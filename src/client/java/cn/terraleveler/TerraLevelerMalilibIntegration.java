package cn.terraleveler;

import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

final class TerraLevelerMalilibIntegration {
    /** Name of the entry in malilib's mod menu (A+C, then the drop-down at the top). */
    static final String DISPLAY_NAME = "TerraLeveler";

    private TerraLevelerMalilibIntegration() { }

    static void register() {
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(
                TerraLeveler.MOD_ID,
                DISPLAY_NAME,
                () -> new TerraLevelerMalilibConfigScreen(null)));
    }
}
