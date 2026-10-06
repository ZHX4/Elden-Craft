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
import java.util.Arrays;

/** 16x16 horizontal cells, each with 16 vertical occupancy bits. */
public final class TerrainShapeBlockEntity extends BlockEntity {
	private int[] cells = new int[TerrainGeometry.GRID * TerrainGeometry.GRID];
	private VoxelShape shape = Shapes.block();

	public TerrainShapeBlockEntity(BlockPos pos, BlockState state) {
		super(ErBridgeMod.TERRAIN_DETAIL_ENTITY, pos, state);
		// Retain support until the saved shape or its update packet arrives.
		Arrays.fill(cells, 0xFFFF);
	}

	public int[] cells() { return TerrainShapeCells.coarse(cells); }
	public int[] fineCells() { return cells.clone(); }

	public void setCells(int[] updated) {
		int[] expanded = TerrainGeometry.upgrade(updated);
		if (expanded == null || Arrays.equals(cells, expanded)) return;
		cells = expanded;
		shape = TerrainVoxelShape.build(cells);
		setChanged();
		if (level != null && !level.isClientSide())
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	public VoxelShape shape() {
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
		int[] loaded = TerrainGeometry.upgrade(tag.getIntArray("cells"));
		if (loaded != null) { cells = loaded; shape = TerrainVoxelShape.build(cells); }
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
