package dev.ermc.bridge;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A terrain voxel with a sampled shape, shared by client and server through its block entity. */
public final class TerrainShapeBlock extends TerrainBlock implements EntityBlock {
	public static final MapCodec<TerrainShapeBlock> CODEC = simpleCodec(TerrainShapeBlock::new);

	public TerrainShapeBlock(Properties properties) { super(properties); }

	@Override
	protected MapCodec<? extends TerrainBlock> codec() { return CODEC; }

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TerrainShapeBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) return detail.shape();
		// Until the shape's update packet arrives, retain the coarse collision.
		return super.getShape(state, level, pos, context);
	}
}
