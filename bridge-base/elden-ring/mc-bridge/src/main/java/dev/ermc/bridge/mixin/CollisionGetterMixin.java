package dev.ermc.bridge.mixin;

import com.google.common.collect.Iterables;
import dev.ermc.bridge.NativeTerrainCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Includes native surfaces in movement AND destination-clearance checks. */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void erbridge$worldContacts(Entity entity, AABB area, CallbackInfoReturnable<Iterable<VoxelShape>> callback) {
        if ((Object)this instanceof Level level) {
            var contacts = NativeTerrainCollision.append(entity, level, area, List.of());
            if (!contacts.isEmpty()) callback.setReturnValue(Iterables.concat(callback.getReturnValue(), contacts));
        }
    }
}
