package dev.ermc.bridge.mixin;

import dev.ermc.bridge.WorldEnvironmentBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TimeCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TimeCommand.class)
public abstract class TimeCommandMixin {
	@Inject(method = {"setTime", "addTime"}, at = @At("RETURN"))
	private static void erbridge$timeChanged(CommandSourceStack source, int ticks, CallbackInfoReturnable<Integer> ci) {
		WorldEnvironmentBridge.timeChanged(source);
	}
}
