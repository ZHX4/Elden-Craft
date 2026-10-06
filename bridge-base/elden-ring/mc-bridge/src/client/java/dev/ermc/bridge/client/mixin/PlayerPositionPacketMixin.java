package dev.ermc.bridge.client.mixin;

import dev.ermc.bridge.client.MovingPlatformClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PlayerPositionPacketMixin {
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void erbridge$arrived(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        MovingPlatformClient.onTeleport();
    }
}
