package dev.ermc.bridge.mixin;

import dev.ermc.bridge.NativeTerrainCollision;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pearls and other projectiles use ray clipping rather than Entity.move. */
@Mixin(BlockGetter.class)
public interface BlockGetterMixin {
    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void erbridge$nativeRay(ClipContext context, CallbackInfoReturnable<BlockHitResult> callback) {
        if ((Object)this instanceof Level level)
            callback.setReturnValue(NativeTerrainCollision.clip(level, context, callback.getReturnValue()));
    }
}
