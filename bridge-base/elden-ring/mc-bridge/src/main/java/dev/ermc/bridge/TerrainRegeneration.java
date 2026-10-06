package dev.ermc.bridge;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Keep saved collision available while the normal terrain sampler refreshes it. */
public final class TerrainRegeneration {
	private TerrainRegeneration() {}
	private static final LongOpenHashSet FRESH_CHUNKS = new LongOpenHashSet();

	/** Called before spawn chunks load, rather than at SERVER_STARTED. */
	public static void reset() { FRESH_CHUNKS.clear(); }

	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		if (level.dimension() != Level.OVERWORLD
			|| !TerrainManager.BRIDGE_LEVEL_NAME.equals(level.getServer().getWorldData().getLevelName())) return;
		if (!FRESH_CHUNKS.add(chunk.getPos().toLong())) return;
		// Clearing an entire chunk here opens floors before native queries or
		// shape packets arrive. Replace only freshly measured cells later, on
		// the regular tick; retain block entities and their persisted hitboxes.
	}
}
