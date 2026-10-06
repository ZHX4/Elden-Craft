package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Vanilla commands retain their permissions, arguments, durations and feedback. */
public final class WorldEnvironmentBridge {
	private static int flags, timeRevision, weatherRevision;

	private WorldEnvironmentBridge() {}

	public static void reset(MinecraftServer server) {
		flags = timeRevision = weatherRevision = 0;
		ErLink.get().writeEnvironment(0, 0, 0, 0, 0);
	}

	public static void onServerStarted(MinecraftServer server) {
		reset(server);
		if (TerrainManager.isBridgeWorld()) {
			flags = Protocol.ENV_TIME | Protocol.ENV_WEATHER;
			onServerTick(server);
		}
	}

	public static void timeChanged(CommandSourceStack source) {
		if (TerrainManager.isBridgeWorld()) {
			flags |= Protocol.ENV_TIME;
			timeRevision++;
			onServerTick(source.getServer());
		}
	}

	public static void weatherChanged(CommandSourceStack source) {
		if (TerrainManager.isBridgeWorld() && source.getLevel() == source.getServer().overworld()) {
			flags |= Protocol.ENV_WEATHER;
			weatherRevision++;
			onServerTick(source.getServer());
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (!TerrainManager.isBridgeWorld()) return;
		ServerLevel world = server.overworld();
		ErLink.get().writeEnvironment(flags, timeRevision, (int) Math.floorMod(world.getDayTime(), 24000L),
			weatherRevision, world.getLevelData().isThundering() ? 2 : world.getLevelData().isRaining() ? 1 : 0);
	}
}
