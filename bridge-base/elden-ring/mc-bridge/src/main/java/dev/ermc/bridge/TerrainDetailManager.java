package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/** Refines nearby obstacles only where native rays prove body clearance and floor support. */
public final class TerrainDetailManager {
	private TerrainDetailManager() {}
	private static final int CELLS = TerrainClearance.GRID * TerrainClearance.GRID;
	private static final int PREFIX = 2; // Verify the origin's native floor and headroom too.
	private static final int COUNT = PREFIX + CELLS * TerrainClearance.RAYS_PER_CELL;
	private static final double RANGE = 5;
	private static final Long2ObjectOpenHashMap<Sample> COLUMNS = new Long2ObjectOpenHashMap<>();
	private static final float[] RAYS = new float[COUNT * 6];
	private static final float[] HITS = new float[COUNT * 6];
	private static final int[] FLAGS = new int[COUNT];
	private static final int[] ATTRS = new int[COUNT];
	private static int pending = -1;
	private static long pendingAt;
	private static int zone;
	private static Sample requested;
	private static Vec3 origin;
	private static int refined;
	private static long lastLog;

	private static final class Sample {
		final int x, z;
		final double y;
		final int top, bottom, floorBlock, height;
		final boolean wall;
		Vec3 checkedFrom;
		long checkedAt;
		Sample(int x, int z, double y, int top, int bottom, int floorBlock, int height, boolean wall) {
			this.x = x; this.z = z; this.y = y;
			this.top = top; this.bottom = bottom; this.floorBlock = floorBlock; this.height = height; this.wall = wall;
		}
		int coarseMask(int blockY) {
			if (blockY < bottom || blockY > top) return 0;
			return (1 << (!wall && blockY == floorBlock ? height : 16)) - 1;
		}
	}

	public static void reset() {
		COLUMNS.clear(); pending = -1; requested = null; refined = 0;
	}

	public static void noteColumn(int x, int z, double y, boolean obstacle, int top, int bottom, int floorBlock, int height, boolean wall) {
		CoordMap.Mapping map = CoordMap.get();
		if (map != null && zone != map.zone()) { reset(); zone = map.zone(); }
		long key = ChunkPos.asLong(x, z);
		if (obstacle) COLUMNS.put(key, new Sample(x, z, y, top, bottom, floorBlock, height, wall));
		else COLUMNS.remove(key);
	}

	public static boolean busy() { return pending >= 0; }

	/** Revokes pending refinement too: collectResults checks the sample's identity. */
	public static void invalidate(int x, int z) { COLUMNS.remove(ChunkPos.asLong(x, z)); }

	/** Complete refinement before the scheduler lends the shared mailbox to another request. */
	public static void collectResults(ServerLevel level, CoordMap.Mapping map) {
		if (zone != map.zone()) { reset(); zone = map.zone(); }
		ErLink link = ErLink.get();
		if (pending >= 0) {
			if (!link.raysDone(pending)) {
				// Keep the original collision and ownership of the mailbox during a stall.
				// Overwriting an unfinished batch could mix old and new native results.
				return;
			}
			link.readHits(COUNT, HITS, FLAGS, ATTRS);
			if (System.currentTimeMillis() - pendingAt < 3000
				&& COLUMNS.get(ChunkPos.asLong(requested.x, requested.z)) == requested) {
				apply(level, map);
				requested.checkedFrom = origin;
				requested.checkedAt = System.currentTimeMillis();
			}
			pending = -1; requested = null;
		}
	}

	/** Called only after the urgent coarse queue is empty and the mailbox is free. */
	public static boolean submit(CoordMap.Mapping map, List<ServerPlayer> players) {
		if (busy()) return true;
		ErLink link = ErLink.get();
		Sample best = null;
		Vec3 from = null;
		double distance = RANGE * RANGE;
		boolean bestWaiting = false;
		for (ServerPlayer player : players) {
			// Native prefix rays prove support and headroom. Minecraft's onGround can
			// be false while hovering at the floor or while an overlapping column waits.
			for (Sample sample : COLUMNS.values()) {
				if (Math.abs(sample.y - player.getY()) > 1) continue;
				Vec3 p = player.position();
				boolean waiting = TerrainManager.detailPending(sample.x, sample.z);
				if (sample.checkedFrom != null && sample.checkedFrom.distanceToSqr(p) < .125 * .125
					&& System.currentTimeMillis() - sample.checkedAt < TerrainPrefetch.REFRESH_MS && !waiting) continue;
				double dx = sample.x + .5 - p.x, dz = sample.z + .5 - p.z;
				double d = dx * dx + dz * dz;
				if (d <= RANGE * RANGE && (waiting && !bestWaiting || waiting == bestWaiting && d < distance)) {
					distance = d; best = sample; from = p; bestWaiting = waiting;
				}
			}
		}
		if (best == null) return false;
		requested = best;
		origin = from;
		int ray = 0;
		putRay(map, ray++, from.x, from.y + .35, from.z, from.x, from.y - .4, from.z);
		putRay(map, ray++, from.x, from.y + .1, from.z, from.x, from.y + TerrainClearance.HEAD, from.z);
		for (int z = 0; z < 8; z++) {
			for (int x = 0; x < 8; x++) {
				double cx = best.x + (x + .5) / 8, cz = best.z + (z + .5) / 8;
				putRay(map, ray++, cx, from.y + .35, cz, cx, from.y - .4, cz);
				for (int corner = 0; corner < 4; corner++) {
					double ex = best.x + (x + (corner & 1)) / 8.0;
					double ez = best.z + (z + (corner >> 1)) / 8.0;
					putRay(map, ray++, ex, from.y + .35, ez, ex, from.y - .4, ez);
				}
				for (double height : new double[] {.35, 1, TerrainClearance.HEAD}) {
					for (int corner = 0; corner < 4; corner++) {
						double ex = best.x + (x + (corner & 1)) / 8.0;
						double ez = best.z + (z + (corner >> 1)) / 8.0;
						putRay(map, ray++, from.x, from.y + height, from.z, ex, from.y + height, ez);
					}
				}
				// Verify the cell itself, not just visibility from an origin which could
				// already be inside a wall. Reverse casts also catch one-sided surfaces.
				for (double height : new double[] {.35, 1, TerrainClearance.HEAD}) {
					for (int diagonal = 2; diagonal < 4; diagonal++) {
						TerrainClearance.Segment segment = TerrainClearance.WALL_SEGMENTS.get(diagonal);
						double x0 = best.x + (x + segment.x0()) / 8, z0 = best.z + (z + segment.z0()) / 8;
						double x1 = best.x + (x + segment.x1()) / 8, z1 = best.z + (z + segment.z1()) / 8;
						putRay(map, ray++, x0, from.y + height, z0, x1, from.y + height, z1);
						putRay(map, ray++, x1, from.y + height, z1, x0, from.y + height, z0);
					}
				}
			}
		}
		pending = link.submitRays(RAYS, COUNT, 0, null);
		pendingAt = System.currentTimeMillis();
		return pending >= 0;
	}

	private static void putRay(CoordMap.Mapping map, int index, double x, double y, double z, double ex, double ey, double ez) {
		double[] s = map.toHost(x, y, z), e = map.toHost(ex, ey, ez);
		for (int axis = 0; axis < 3; axis++) {
			RAYS[index * 6 + axis] = (float) s[axis];
			RAYS[index * 6 + axis + 3] = (float) e[axis];
		}
	}

	private static void apply(ServerLevel level, CoordMap.Mapping map) {
		if (TerrainManager.movingColumn(requested.x, requested.z)) return;
		double originFloor = map.toMc(HITS[0], HITS[1], HITS[2]).y;
		boolean validOrigin = FLAGS[0] != 0 && FLAGS[1] == 0 && Math.abs(originFloor - origin.y) <= TerrainClearance.FLOOR_RANGE;
		if (!validOrigin) return; // Keep the existing support while an overlapping coarse column waits.
		double[] floors = new double[CELLS];
		boolean[] clear = new boolean[CELLS];
		int free = 0;
		for (int cell = 0; cell < CELLS; cell++) {
			int ray = PREFIX + cell * TerrainClearance.RAYS_PER_CELL;
			double minFloor = Double.POSITIVE_INFINITY, maxFloor = Double.NEGATIVE_INFINITY;
			for (int point = 0; point < TerrainClearance.FLOOR_RAYS; point++) {
				int hit = (ray + point) * 6;
				double y = map.toMc(HITS[hit], HITS[hit + 1], HITS[hit + 2]).y;
				minFloor = Math.min(minFloor, y); maxFloor = Math.max(maxFloor, y);
			}
			floors[cell] = maxFloor;
			clear[cell] = validOrigin && TerrainClearance.clear(FLAGS, ray, minFloor, maxFloor, origin.y);
			if (clear[cell]) free++;
		}
		boolean changed = false;
		for (int y = Mth.floor(origin.y - .4) - 1; y <= Mth.floor(origin.y + TerrainClearance.HEAD); y++) {
			BlockPos pos = new BlockPos(requested.x, y, requested.z);
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && !ErBridgeMod.isTerrain(state)) continue;
			int[] cells;
			if (level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) cells = detail.cells();
			else {
				cells = new int[CELLS];
				if (ErBridgeMod.isTerrain(state)) Arrays.fill(cells, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
			}
			int[] old = cells.clone();
			for (int cell = 0; cell < CELLS; cell++) {
				cells[cell] = TerrainClearance.refreshBody(cells[cell], requested.coarseMask(y), y, origin.y);
				if (!clear[cell]) continue;
				cells[cell] = TerrainClearance.carve(cells[cell], y, floors[cell], origin.y);
				int floorBlock = Mth.floor(floors[cell] - 1e-4);
				if (y == floorBlock || y == floorBlock - 1) cells[cell] |= TerrainClearance.floorMask(y, floors[cell]);
			}
			if (Arrays.equals(old, cells)) continue;
			store(level, pos, cells);
			changed = true;
		}
		if (changed) refined++;
		TerrainManager.detailReady(requested.x, requested.z);
		long now = System.currentTimeMillis();
		if (changed && now - lastLog >= 3000) {
			LoggerFactory.getLogger("erbridge").info("Terrain detail: {} columns refined; last {}, {} has {}/64 supported clear cells",
				refined, requested.x, requested.z, free);
			lastLog = now;
		}
	}

	private static void store(ServerLevel level, BlockPos pos, int[] cells) {
		boolean empty = true, full = true;
		for (int cell : cells) { empty &= cell == 0; full &= cell == 0xFFFF; }
		BlockState state = empty ? Blocks.AIR.defaultBlockState() : full ? ErBridgeMod.TERRAIN.defaultBlockState()
			: ErBridgeMod.TERRAIN_DETAIL.defaultBlockState();
		level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		if (!empty && !full && level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) detail.setCells(cells);
	}
}
