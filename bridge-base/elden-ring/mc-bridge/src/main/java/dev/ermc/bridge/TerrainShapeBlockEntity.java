package dev.ermc.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 8x8 horizontal cells, each with 16 vertical occupancy bits. Unknown shapes stay solid. */
public final class TerrainShapeBlockEntity extends BlockEntity {
	private int[] cells = new int[64];
	private VoxelShape shape;

	public TerrainShapeBlockEntity(BlockPos pos, BlockState state) {
		super(ErBridgeMod.TERRAIN_DETAIL_ENTITY, pos, state);
		Arrays.fill(cells, 0xFFFF);
	}

	public int[] cells() { return cells.clone(); }

	public void setCells(int[] updated) {
		if (updated.length != 64 || Arrays.equals(cells, updated)) return;
		cells = updated.clone();
		shape = null;
		setChanged();
		if (level != null && !level.isClientSide())
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	public VoxelShape shape() {
		if (shape != null) return shape;
		List<VoxelShape> boxes = new ArrayList<>();
		for (int z = 0; z < 8; z++) {
			for (int x = 0; x < 8;) {
				int mask = cells[z * 8 + x] & 0xFFFF;
				int end = x + 1;
				while (end < 8 && (cells[z * 8 + end] & 0xFFFF) == mask) end++;
				for (int y = 0; y < 16;) {
					if ((mask & (1 << y)) == 0) { y++; continue; }
					int top = y + 1;
					while (top < 16 && (mask & (1 << top)) != 0) top++;
					boxes.add(Block.box(x * 2, y, z * 2, end * 2, top, z * 2 + 2));
					y = top;
				}
				x = end;
			}
		}
		shape = Shapes.or(Shapes.empty(), boxes.toArray(VoxelShape[]::new)).optimize();
		return shape;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putIntArray("cells", cells);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		int[] loaded = tag.getIntArray("cells");
		if (loaded.length == 64) { cells = loaded; shape = null; }
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
