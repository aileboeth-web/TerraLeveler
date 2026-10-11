package cn.terraleveler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Tells the player when a job pauses or finishes (0.9.10): a chat line that says what
 * happened and whether TerraLeveler continues by itself, and a sound only the player hears
 * when something needs them (setting "提示音"). Before, pauses only changed the status bar.
 */
final class TerraLevelerAlerts {
    /** The same pause reason is announced again only after this long (auto-retry loops). */
    static final long REPEAT_MILLIS = 60_000L;

    enum Resume { AUTO_RETRY, AFTER_HEALTH, BY_PLAYER }

    private static String lastPauseText;
    private static long lastPauseMillis;

    private TerraLevelerAlerts() { }

    static void paused(Minecraft mc, String job, String reason, Resume resume) {
        if (mc == null || mc.player == null) return;
        String text = pauseMessage(job, reason, resume);
        long now = System.currentTimeMillis();
        if (text.equals(lastPauseText) && now - lastPauseMillis < REPEAT_MILLIS) return;
        lastPauseText = text;
        lastPauseMillis = now;
        mc.player.sendSystemMessage(Component.literal(text));
        if (resume == Resume.BY_PLAYER) play(mc, Sound.ATTENTION);
    }

    static void finished(Minecraft mc) {
        play(mc, Sound.FINISHED);
    }

    static String pauseMessage(String job, String reason, Resume resume) {
        String next = switch (resume) {
            case AUTO_RETRY -> "§7（30 秒后自动重试）";
            case AFTER_HEALTH -> "§7（生命值回复后自动继续）";
            case BY_PLAYER -> "§e（处理后按" + KeyHintText.hint(KeyHintText.PAUSE_RESUME) + "继续）";
        };
        return "§c" + job + " 已暂停§r：" + reason + next;
    }

    private enum Sound { ATTENTION, FINISHED }

    private static void play(Minecraft mc, Sound sound) {
        TerraLevelerConfig config = TerraLeveler.CONFIG;
        if (mc == null || mc.getSoundManager() == null || config == null || !config.soundAlerts) return;
        mc.getSoundManager().play(sound == Sound.ATTENTION
                ? SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0F)
                : SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
    }
}
