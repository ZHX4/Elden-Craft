package dev.ermc.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Move only the verified support cells; neighbouring shaft walls keep their collision. */
public final class MovingPlatformTerrain {
	private MovingPlatformTerrain() {}

	/** The native sampler has checked both the floor footprint and this body volume. */
	public static void refresh(Level level, Vec3 centre, double radius, double low, double high, boolean floorPresent) {
		int floorBlock = Mth.floor(centre.y - 1e-4);
		int height = Mth.clamp((int)Math.ceil((centre.y - floorBlock) * 16 - 1e-3), 1, 16);
		for (int x = Mth.floor(centre.x - radius); x <= Mth.floor(centre.x + radius); x++)
			for (int z = Mth.floor(centre.z - radius); z <= Mth.floor(centre.z + radius); z++)
				for (int y = Mth.floor(low); y <= Mth.floor(high); y++) {
					BlockPos pos = new BlockPos(x, y, z);
					var state = level.getBlockState(pos);
					if (!ErBridgeMod.isTerrain(state) && !state.isAir()) continue;
					int[] cells = level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail ? detail.cells() : new int[64];
					if (state.is(ErBridgeMod.TERRAIN)) java.util.Arrays.fill(cells, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
					int clear = 0;
					for (int bit = 0; bit < 16; bit++) if (y + (bit + 1) / 16.0 > low && y + bit / 16.0 < high) clear |= 1 << bit;
					boolean changed = false, empty = true;
					for (int cz = 0; cz < 8; cz++) for (int cx = 0; cx < 8; cx++) {
						int cell = cz * 8 + cx;
						if (Math.abs(x + (cx + .5) / 8.0 - centre.x) < radius && Math.abs(z + (cz + .5) / 8.0 - centre.z) < radius) {
							int next = cells[cell] & ~clear;
							if (floorPresent && y == floorBlock) next |= 1 << (height - 1);
							changed |= next != cells[cell]; cells[cell] = next;
						}
						empty &= cells[cell] == 0;
					}
					if (!changed) continue;
					if (empty) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					else {
						if (!state.is(ErBridgeMod.TERRAIN_DETAIL)) level.setBlock(pos, ErBridgeMod.TERRAIN_DETAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
						if (level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) detail.setCells(cells);
					}
				}
	}

	public static void move(Level level, Vec3 feet, double previousFloor, double floor) {
		int floorBlock = Mth.floor(floor - 1e-4);
		int height = Mth.clamp((int)Math.ceil((floor - floorBlock) * 16 - 1e-3), 1, 16);
		int low = Math.min(Mth.floor(previousFloor - 1e-4) - 2, floorBlock);
		int high = Math.max(Mth.floor(previousFloor - 1e-4), floorBlock);
		for (int x = Mth.floor(feet.x - .3); x <= Mth.floor(feet.x + .3); x++) {
			for (int z = Mth.floor(feet.z - .3); z <= Mth.floor(feet.z + .3); z++) {
				for (int y = low; y <= high; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					var state = level.getBlockState(pos);
					if (!ErBridgeMod.isTerrain(state) && !state.isAir()) continue;
					int[] cells = level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail ? detail.cells() : new int[64];
					if (state.is(ErBridgeMod.TERRAIN))
						java.util.Arrays.fill(cells, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
					boolean changed = false, empty = true;
					for (int cz = 0; cz < 8; cz++) for (int cx = 0; cx < 8; cx++) {
						int cell = cz * 8 + cx;
						if (x + (cx + 1) / 8.0 > feet.x - .3 && x + cx / 8.0 < feet.x + .3
							&& z + (cz + 1) / 8.0 > feet.z - .3 && z + cz / 8.0 < feet.z + .3) {
							int next = y == floorBlock ? 1 << (height - 1) : 0;
							changed |= cells[cell] != next;
							cells[cell] = next;
						}
						empty &= cells[cell] == 0;
					}
					if (!changed) continue;
					if (empty) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					else {
						if (!state.is(ErBridgeMod.TERRAIN_DETAIL))
							level.setBlock(pos, ErBridgeMod.TERRAIN_DETAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
						if (level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) detail.setCells(cells);
					}
				}
			}
		}
	}
}
