package dev.ermc.bridge.mixin;

import dev.ermc.bridge.NativeTerrainCollision;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
    @Inject(method = {"teleportTo(DDD)V", "teleportRelative(DDD)V",
        "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"}, at = @At("RETURN"))
    private void erbridge$arrivedDirect(CallbackInfo callback) {
        NativeTerrainCollision.teleported((ServerPlayer)(Object)this);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("RETURN"))
    private void erbridge$arrived(ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative,
            float yaw, float pitch, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) NativeTerrainCollision.teleported((ServerPlayer)(Object)this);
    }
}
