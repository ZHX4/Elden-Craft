package dev.ermc.bridge.mixin;

import dev.ermc.bridge.WorldEnvironmentBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WeatherCommand.class)
public abstract class WeatherCommandMixin {
	@Inject(method = {"setClear", "setRain", "setThunder"}, at = @At("RETURN"))
	private static void erbridge$weatherChanged(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> ci) {
		WorldEnvironmentBridge.weatherChanged(source);
	}
}
