package dev.ermc.bridge.client.mixin;

import dev.ermc.bridge.client.FramePassthrough;
import dev.ermc.bridge.client.Overlay;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Thin native props may not occupy a Minecraft terrain voxel: punch the native collision along the aim ray. */
	@Inject(method = "startAttack", at = @At("HEAD"))
	private void erbridge$attackNativeProp(CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		if (!Overlay.active() || mc.player == null || mc.hitResult == null
				|| mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) return;
		var map = dev.ermc.bridge.CoordMap.get();
		if (map == null || map.provisional()) return;
		var endpoint = mc.player.getEyePosition().add(mc.player.getViewVector(1.0F).scale(mc.player.blockInteractionRange()));
		double[] p = map.toHost(endpoint.x, endpoint.y, endpoint.z);
		dev.ermc.bridge.link.ErLink.get().pushDamage(0L, 1.0F, (float)p[0], (float)p[1], (float)p[2],
				dev.ermc.bridge.link.Protocol.DAMAGE_WORLD_RAY);
		dev.ermc.bridge.TerrainManager.resampleAround(endpoint.x, endpoint.z, 2);
	}
	@Inject(method = "runTick", at = @At("HEAD"))
	private void erbridge$frame(boolean tick, CallbackInfo ci) {
		Overlay.onFrame((Minecraft) (Object) this);
	}

	/** Leave GPU time for Elden Ring while both games render on the same device. */
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void erbridge$capFps(CallbackInfoReturnable<Integer> cir) {
		if (FramePassthrough.wanted()) {
			cir.setReturnValue(Math.min(cir.getReturnValue(), FramePassthrough.frameLimit()));
		}
	}
}
