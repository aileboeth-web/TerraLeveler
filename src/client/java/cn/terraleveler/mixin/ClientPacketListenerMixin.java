package cn.terraleveler.mixin;

import cn.terraleveler.ConstructionController;
import cn.terraleveler.TerraLeveler;
import cn.terraleveler.TerraLevelerDiagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void terraleveler$recordServerPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        // handleTakeItemEntity first runs on the network thread, where vanilla's
        // ensureRunningOnSameThread re-queues it for the client thread. HEAD runs
        // before that hop, so only the client-thread invocation may touch the
        // controller's (non thread-safe) inventory bookkeeping.
        if (!minecraft.isSameThread()) return;
        LocalPlayer player = minecraft.player;
        if (player == null || packet.getPlayerId() != player.getId()) return;
        boolean active = TerraLeveler.CONTROLLER.isConstructionActive();
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(packet.getItemId());
        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            if (active) {
                String itemId = stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                TerraLevelerDiagnostics.log("PICKUP", "server-confirmed item=" + itemId
                        + " amount=" + packet.getAmount()
                        + " entity=" + packet.getItemId()
                        + " pos=" + itemEntity.blockPosition().toShortString()
                        + " managed=" + (!stack.isEmpty() && ConstructionController.isManagedPickupMaterial(stack.getItem())));
            }
            if (!stack.isEmpty()) {
                TerraLeveler.CONTROLLER.onServerConfirmedPickup(
                        stack.getItem(), packet.getAmount(), packet.getItemId());
            }
        } else if (active) {
            TerraLevelerDiagnostics.log("PICKUP", "server-confirmed entity-missing entity=" + packet.getItemId()
                    + " amount=" + packet.getAmount());
        }
    }
}
