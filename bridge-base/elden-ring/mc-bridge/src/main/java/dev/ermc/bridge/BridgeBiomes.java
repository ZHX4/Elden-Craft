package dev.ermc.bridge;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Updates old saves as chunks load, before their biome palettes reach the client. */
public final class BridgeBiomes {
	private BridgeBiomes() {}

	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		// Chunk loading begins before SERVER_STARTED establishes TerrainManager's flag.
		if (!TerrainManager.BRIDGE_LEVEL_NAME.equals(level.getServer().getWorldData().getLevelName())) return;
		Holder<Biome> plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
		boolean changed = false;
		for (int i = 0; i < chunk.getSections().length; i++) {
			LevelChunkSection section = chunk.getSections()[i];
			if (section.getBiomes().maybeHas(biome -> !biome.is(Biomes.PLAINS))) {
				section.fillBiomesFromNoise((x, y, z, sampler) -> plains, null,
					chunk.getPos().x * 4, chunk.getSectionYFromSectionIndex(i) * 4, chunk.getPos().z * 4);
				changed = true;
			}
		}
		if (changed) chunk.setUnsaved(true);
	}
}
