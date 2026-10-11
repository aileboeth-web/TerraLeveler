package cn.terraleveler.mixin;

import cn.terraleveler.BoxScreenHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the shulker box screen TerraLeveler opened through QuickShulker from being shown (0.9.08). */
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void terraleveler$hideBoxScreen(Screen screen, CallbackInfo ci) {
        if (BoxScreenHider.shouldHide(screen)) ci.cancel();
    }
}
