package dev.ermc.bridge.mixin;

import dev.ermc.bridge.NativeTerrainCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.MoverType;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Unique private Vec3 erbridge$traceStart, erbridge$traceRequested, erbridge$traceProjected;
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 erbridge$wallSliding(Vec3 movement) {
        Entity entity = (Entity)(Object)this;
        erbridge$traceStart = NativeTerrainCollision.tracingMovement() ? entity.position() : null;
        erbridge$traceRequested = movement;
        erbridge$traceProjected = NativeTerrainCollision.slide(entity, movement);
        return erbridge$traceProjected;
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void erbridge$finishMove(MoverType type, Vec3 movement, CallbackInfo callback) {
        Entity entity = (Entity)(Object)this;
        try {
            if (erbridge$traceStart != null)
                NativeTerrainCollision.traceMove(entity, erbridge$traceStart, erbridge$traceRequested, erbridge$traceProjected);
        } finally {
            NativeTerrainCollision.finishMove(entity);
            erbridge$traceStart = null;
        }
    }

}
