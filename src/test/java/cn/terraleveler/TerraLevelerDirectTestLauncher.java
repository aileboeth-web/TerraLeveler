package cn.terraleveler;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.platform.console.ConsoleLauncher;

public final class TerraLevelerDirectTestLauncher {
    private TerraLevelerDirectTestLauncher() { }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ConsoleLauncher.main(args);
    }
}
