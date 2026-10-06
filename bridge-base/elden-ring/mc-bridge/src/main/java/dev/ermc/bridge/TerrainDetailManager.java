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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Refines nearby obstacles only where native rays prove body clearance and floor support. */
public final class TerrainDetailManager {
	private TerrainDetailManager() {}
	private static final int GRID = 16, CELLS = GRID * GRID;
	private static final int CELLS_PER_BATCH = 64;
	private static final int BODY_RAYS_PER_CELL = 17; // 5 headroom, 12 local diagonals.
	private static final int FLOOR_COUNT = CELLS * TerrainClearance.FLOOR_RAYS;
	private static final int COUNT = Math.max(FLOOR_COUNT, CELLS_PER_BATCH * BODY_RAYS_PER_CELL);
	private static final double CLEAR_HEIGHT = 4;
	private static final double[] BODY_HEIGHTS = {.15, .9, TerrainClearance.BODY_HEIGHT};
	private static int bodyCursor;
	private static final double[] FLOORS = new double[CELLS];
	private static final int[] OFFSETS = new int[CELLS];
	private static boolean samplingFloors;
	private static int pendingCount;
	private static CoordMap.Mapping pendingMap;
	private static final double RANGE = 5;
	private static final Long2ObjectOpenHashMap<Sample> COLUMNS = new Long2ObjectOpenHashMap<>();
	// Air blocks have no block entity. Remember their measured shape too, so
	// a coarse refresh cannot silently recreate cubes in an opened passage.
	private static final Long2ObjectOpenHashMap<Map<Integer, int[]>> VERIFIED = new Long2ObjectOpenHashMap<>();
	private static final int[] BODY_ORDER = new int[CELLS];
	private static final float[] RAYS = new float[COUNT * 6];
	private static final float[] HITS = new float[COUNT * 6];
	private static final int[] FLAGS = new int[COUNT];
	private static final int[] ATTRS = new int[COUNT];
	private static int pending = -1;
	private static long pendingAt;
	private static int zone;
	private static Sample requested;
	private static Vec3 origin;
	private static Vec3 observer;
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
		COLUMNS.clear(); VERIFIED.clear(); pending = -1; requested = null; refined = 0;
	}

	public static void noteColumn(int x, int z, double y, boolean obstacle, int top, int bottom, int floorBlock, int height, boolean wall) {
		CoordMap.Mapping map = CoordMap.get();
		if (map != null && zone != map.zone()) { reset(); zone = map.zone(); }
		long key = ChunkPos.asLong(x, z);
		if (obstacle) COLUMNS.put(key, new Sample(x, z, y, top, bottom, floorBlock, height, wall));
		else COLUMNS.remove(key);
	}

	public static boolean busy() { return pending >= 0; }

	/** Discover actual blocking terrain even when coarse rays labelled it ground.
	 * This is bounded by the player's next 1.5 metres, not the whole loaded map. */
	public static void discover(ServerLevel level, List<ServerPlayer> players) {
		for (ServerPlayer player : players) {
			Vec3 feet = player.position();
			Vec3 ahead = player.getViewVector(1).multiply(1, 0, 1).normalize().scale(1.5);
			AABB body = player.getBoundingBox().expandTowards(ahead);
			AABB area = new AABB(body.minX - .1, feet.y + .02, body.minZ - .1,
				body.maxX + .1, body.maxY + .65, body.maxZ + .1);
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
					BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
				long key = ChunkPos.asLong(pos.getX(), pos.getZ());
				if (COLUMNS.containsKey(key) || TerrainManager.movingColumn(pos.getX(), pos.getZ())) continue;
				BlockState state = level.getBlockState(pos);
				if (!ErBridgeMod.isTerrain(state)) continue;
				var shape = state.getCollisionShape(level, pos, CollisionContext.of(player));
				if (shape.isEmpty() || !shape.bounds().move(pos).intersects(area)) continue;
				COLUMNS.put(key, new Sample(pos.getX(), pos.getZ(), feet.y,
					Mth.floor(area.maxY), Mth.floor(feet.y - 1), pos.getY(),
					state.getValue(TerrainBlock.HEIGHT), true));
			}
		}
	}

	/** Read on the server thread by the terrain diagnostics command. */
	public static String describe(ServerPlayer player) {
		return "16x16 local passage refinement; columns " + COLUMNS.size() + " refined " + refined
			+ " pending " + (pending < 0 ? 0 : System.currentTimeMillis() - pendingAt)
			+ " requested " + (requested == null ? "none" : requested.x + "," + requested.z);
	}

	/** Revokes pending refinement too: collectResults checks the sample's identity. */
	public static void invalidate(int x, int z) {
		long key = ChunkPos.asLong(x, z);
		COLUMNS.remove(key); VERIFIED.remove(key);
	}

	/** Only a fresh local hit or explicit door/prop invalidation revokes measured air. */
	public static boolean preserveVerified(ServerLevel level, BlockPos pos) {
		Map<Integer, int[]> column = VERIFIED.get(ChunkPos.asLong(pos.getX(), pos.getZ()));
		int[] cells = column == null ? null : column.get(pos.getY());
		if (cells == null) return false;
		store(level, pos, cells);
		return true;
	}

	/** Install a measured travel patch without sending unchanged block shapes. */
	static void writeMeasured(ServerLevel level, BlockPos pos, int[] cells) {
		BlockState state = level.getBlockState(pos);
		if (!state.isAir() && !ErBridgeMod.isTerrain(state)) return;
		int[] old = level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail ? detail.fineCells() : new int[CELLS];
		if (state.is(ErBridgeMod.TERRAIN)) Arrays.fill(old, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
		if (Arrays.equals(old, cells)) remember(pos, cells);
		else store(level, pos, cells);
	}

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
			link.readHits(pendingCount, HITS, FLAGS, ATTRS);
			boolean current = map.equals(pendingMap) && System.currentTimeMillis() - pendingAt < 3000
				&& COLUMNS.get(ChunkPos.asLong(requested.x, requested.z)) == requested;
			if (current && samplingFloors) {
				readFloors(map);
				samplingFloors = false;
				bodyCursor = 0;
				pending = -1; // Lend the mailbox to urgent travel/footing before the next part.
				return;
			} else if (current) {
				apply(level, map);
				if (bodyCursor < CELLS) { pending = -1; return; }
			}
			if (current) {
				requested.checkedFrom = observer;
				requested.checkedAt = System.currentTimeMillis();
			}
			pending = -1; requested = null;
		}
	}

	private static boolean submitBodyBatch(CoordMap.Mapping map) {
		while (bodyCursor < CELLS) {
			pendingCount = bodyRays(map);
			if (pendingCount == 0) continue;
			pending = ErLink.get().submitRays(RAYS, pendingCount, 0, null);
			pendingAt = System.currentTimeMillis(); // Timeout belongs to this batch, not the entire chain.
			return pending >= 0;
		}
		return false;
	}

	/** Runs one background part when urgent travel and footing lend the mailbox. */
	public static boolean submit(CoordMap.Mapping map, List<ServerPlayer> players) {
		if (busy()) return true;
		if (requested != null) {
			if (map.equals(pendingMap) && COLUMNS.get(ChunkPos.asLong(requested.x, requested.z)) == requested) {
				if (submitBodyBatch(map)) return true;
				if (bodyCursor >= CELLS) {
					requested.checkedFrom = observer;
					requested.checkedAt = System.currentTimeMillis();
				}
			}
			requested = null;
		}
		ErLink link = ErLink.get();
		Sample best = null;
		Vec3 from = null;
		double distance = RANGE * RANGE;
		boolean bestWaiting = false;
		for (ServerPlayer player : players) {
			// Local geometry can be prepared before the player stands on it.
			for (Sample sample : COLUMNS.values()) {
				if (sample.top < player.getY() - 1 || sample.bottom > player.getY() + CLEAR_HEIGHT) continue;
				Vec3 p = player.position();
				boolean waiting = TerrainManager.detailPending(sample.x, sample.z);
				if (sample.checkedFrom != null && Math.abs(sample.checkedFrom.y - p.y) < .25
					&& System.currentTimeMillis() - sample.checkedAt < TerrainPrefetch.REFRESH_MS) continue;
				double dx = sample.x + .5 - p.x, dz = sample.z + .5 - p.z;
				double d = dx * dx + dz * dz;
				if (d <= RANGE * RANGE && (waiting && !bestWaiting || waiting == bestWaiting && d < distance)) {
					distance = d; best = sample; from = p; bestWaiting = waiting;
				}
			}
		}
		if (best == null) return false;
		requested = best;
		observer = from;
		// Higher sampled ground must be refined before the player climbs onto
		// it, rather than waiting for the player's feet to reach that height.
		double queryY = best.floorBlock == Integer.MIN_VALUE ? from.y
			: Math.max(from.y, Math.min(from.y + CLEAR_HEIGHT, best.floorBlock + best.height / 16.0));
		origin = new Vec3(from.x, queryY, from.z);
		Integer[] order = new Integer[CELLS];
		for (int cell = 0; cell < CELLS; cell++) order[cell] = cell;
		final int bx = best.x, bz = best.z;
		final Vec3 feet = from;
		Arrays.sort(order, java.util.Comparator.comparingDouble(cell -> {
			double dx = bx + (cell % GRID + .5) / GRID - feet.x;
			double dz = bz + (cell / GRID + .5) / GRID - feet.z;
			return dx * dx + dz * dz;
		}));
		for (int cell = 0; cell < CELLS; cell++) BODY_ORDER[cell] = order[cell];
		pendingMap = map;
		samplingFloors = true;
		int ray = 0;
		for (int z = 0; z < GRID; z++) {
			for (int x = 0; x < GRID; x++) {
				double cx = best.x + (x + .5) / GRID, cz = best.z + (z + .5) / GRID;
				putRay(map, ray++, cx, queryY + TerrainClearance.DETAIL_RISE, cz,
					cx, queryY - TerrainClearance.DETAIL_DROP, cz);
				for (int corner = 0; corner < 4; corner++) {
					double ex = best.x + (x + (corner & 1)) / (double)GRID;
					double ez = best.z + (z + (corner >> 1)) / (double)GRID;
					putRay(map, ray++, ex, queryY + TerrainClearance.DETAIL_RISE, ez,
						ex, queryY - TerrainClearance.DETAIL_DROP, ez);
				}
			}
		}
		pendingCount = FLOOR_COUNT;
		pending = link.submitRays(RAYS, pendingCount, 0, null);
		pendingAt = System.currentTimeMillis();
		return pending >= 0;
	}

	private static void readFloors(CoordMap.Mapping map) {
		for (int cell = 0; cell < CELLS; cell++) {
			double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			boolean supported = true;
			for (int point = 0; point < TerrainClearance.FLOOR_RAYS; point++) {
				int ray = cell * TerrainClearance.FLOOR_RAYS + point, hit = ray * 6;
				supported &= FLAGS[ray] != 0 && HITS[hit + 4] > .3f;
				double y = map.toMc(HITS[hit], HITS[hit + 1], HITS[hit + 2]).y;
				min = Math.min(min, y); max = Math.max(max, y);
			}
			FLOORS[cell] = supported && TerrainClearance.supportedCell(min, max, origin.y) ? max : Double.NaN;
		}
	}

	/** Only supported cells need body queries; each batch stays below 2,048 rays. */
	private static int bodyRays(CoordMap.Mapping map) {
		Arrays.fill(OFFSETS, -1);
		int ray = 0;
		int end = Math.min(CELLS, bodyCursor + CELLS_PER_BATCH);
		while (bodyCursor < end) {
			int cell = BODY_ORDER[bodyCursor++], z = cell / GRID, x = cell % GRID;
			if (!Double.isFinite(FLOORS[cell])) continue;
			OFFSETS[cell] = ray;
			double floor = FLOORS[cell];
			for (int point = 0; point < TerrainClearance.FLOOR_RAYS; point++) {
				double ex = requested.x + (x + (point == 0 ? .5 : ((point - 1) & 1))) / (double)GRID;
				double ez = requested.z + (z + (point == 0 ? .5 : ((point - 1) >> 1))) / (double)GRID;
				putRay(map, ray++, ex, floor + .02, ez, ex, floor + CLEAR_HEIGHT, ez);
			}
			for (double height : BODY_HEIGHTS) {
				for (int diagonal = 2; diagonal < 4; diagonal++) {
					TerrainClearance.Segment segment = TerrainClearance.WALL_SEGMENTS.get(diagonal);
					double x0 = requested.x + (x + segment.x0()) / GRID, z0 = requested.z + (z + segment.z0()) / GRID;
					double x1 = requested.x + (x + segment.x1()) / GRID, z1 = requested.z + (z + segment.z1()) / GRID;
					putRay(map, ray++, x0, floor + height, z0, x1, floor + height, z1);
					putRay(map, ray++, x1, floor + height, z1, x0, floor + height, z0);
				}
			}
		}
		return ray;
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
		double[] ceilings = new double[CELLS];
		boolean[] clear = new boolean[CELLS];
		boolean[] obstructed = new boolean[CELLS];
		int free = 0;
		double lowest = origin.y - .4, highest = origin.y + CLEAR_HEIGHT;
		for (int cell = 0; cell < CELLS; cell++) {
			int ray = OFFSETS[cell];
			if (ray < 0) continue;
			double ceiling = FLOORS[cell] + CLEAR_HEIGHT;
			for (int point = 0; point < TerrainClearance.FLOOR_RAYS; point++) {
				if (FLAGS[ray + point] == 0) continue;
				int hit = (ray + point) * 6;
				double y = map.toMc(HITS[hit], HITS[hit + 1], HITS[hit + 2]).y;
				ceiling = Math.min(ceiling, y);
			}
			ceilings[cell] = ceiling;
			// Keep a real low ceiling at its measured height, rather than making
			// the entire column solid because a standing body would not fit.
			clear[cell] = ceiling > FLOORS[cell] + .02;
			for (int probe = TerrainClearance.FLOOR_RAYS; probe < BODY_RAYS_PER_CELL; probe++) {
				if (FLAGS[ray + probe] == 0) continue;
				int localHeight = (probe - TerrainClearance.FLOOR_RAYS) / 4;
				if (FLOORS[cell] + BODY_HEIGHTS[localHeight] >= ceiling - .001) continue;
				clear[cell] = false;
				obstructed[cell] = true;
			}
			if (clear[cell]) free++;
			lowest = Math.min(lowest, FLOORS[cell]);
			highest = Math.max(highest, ceiling);
		}
		boolean changed = false;
		for (int y = Mth.floor(lowest) - 1; y <= Mth.floor(highest); y++) {
			BlockPos pos = new BlockPos(requested.x, y, requested.z);
			BlockState state = level.getBlockState(pos);
			if (!state.isAir() && !ErBridgeMod.isTerrain(state)) continue;
			int[] cells;
			if (level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) cells = detail.fineCells();
			else {
				cells = new int[CELLS];
				if (ErBridgeMod.isTerrain(state)) Arrays.fill(cells, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
			}
			int[] old = cells.clone();
			boolean measuredAir = false;
			for (int cell = 0; cell < CELLS; cell++) {
				if (obstructed[cell]) {
					int body = ~TerrainClearance.carveBetween(0xFFFF, y, FLOORS[cell],
						FLOORS[cell] + CLEAR_HEIGHT) & 0xFFFF;
					cells[cell] = (cells[cell] & ~body) | (requested.coarseMask(y) & body);
				}
				if (!clear[cell]) continue;
				measuredAir |= TerrainClearance.carveBetween(0xFFFF, y, FLOORS[cell], ceilings[cell]) != 0xFFFF;
				cells[cell] = TerrainClearance.carveBetween(cells[cell], y, FLOORS[cell], ceilings[cell]);
				int floorBlock = Mth.floor(FLOORS[cell] - 1e-4);
				if (y == floorBlock || y == floorBlock - 1) cells[cell] |= TerrainClearance.floorMask(y, FLOORS[cell]);
			}
			if (Arrays.equals(old, cells)) {
				// A successful read is evidence even when no world write is needed.
				// In particular, loaded air has no entity and must still be protected.
				if (measuredAir) remember(pos, cells);
				continue;
			}
			store(level, pos, cells);
			changed = true;
		}
		if (changed) refined++;
		TerrainManager.detailReady(requested.x, requested.z);
		long now = System.currentTimeMillis();
		if (changed && now - lastLog >= 3000) {
			LoggerFactory.getLogger("erbridge").info("Terrain detail: {} parts refined; last {}, {} has {} clear cells in this batch",
				refined, requested.x, requested.z, free);
			lastLog = now;
		}
	}

	private static void remember(BlockPos pos, int[] cells) {
		boolean full = true;
		for (int cell : cells) full &= cell == 0xFFFF;
		long key = ChunkPos.asLong(pos.getX(), pos.getZ());
		if (full) {
			Map<Integer, int[]> column = VERIFIED.get(key);
			if (column != null) column.remove(pos.getY());
		} else VERIFIED.computeIfAbsent(key, ignored -> new HashMap<>()).put(pos.getY(), cells.clone());
	}

	private static void store(ServerLevel level, BlockPos pos, int[] cells) {
		boolean empty = true, full = true;
		for (int cell : cells) { empty &= cell == 0; full &= cell == 0xFFFF; }
		remember(pos, cells);
		BlockState state = empty ? Blocks.AIR.defaultBlockState() : full ? ErBridgeMod.TERRAIN.defaultBlockState()
			: ErBridgeMod.TERRAIN_DETAIL.defaultBlockState();
		level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		if (!empty && !full && level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail) detail.setCells(cells);
	}
}
