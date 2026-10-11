package cn.terraleveler;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu's "Config" button for TerraLeveler (0.9.15). Hotkeys are unbound by default, so a
 * player who does not know malilib's A+C menu could not reach the settings at all. Only loaded
 * when ModMenu is installed; ModMenu asks for the factory while the game starts, so this must
 * not touch anything that is not ready yet.
 */
public final class TerraLevelerModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TerraLevelerMalilibConfigScreen::new;
    }
}
