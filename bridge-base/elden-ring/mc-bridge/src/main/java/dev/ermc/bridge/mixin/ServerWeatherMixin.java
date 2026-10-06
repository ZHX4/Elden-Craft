package dev.ermc.bridge.mixin;

import dev.ermc.bridge.TerrainManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLevel.class)
public abstract class ServerWeatherMixin {
	/** Keep the thunder timer/state for Elden Ring, without vanilla lightning entities or sounds. */
	@Redirect(method = "tickChunk", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
	private boolean erbridge$nativeThunderOnly(ServerLevel world) {
		return !TerrainManager.isBridgeWorld() && world.isThundering();
	}
}
