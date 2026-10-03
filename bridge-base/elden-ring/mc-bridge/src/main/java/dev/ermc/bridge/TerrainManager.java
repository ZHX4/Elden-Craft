package dev.ermc.bridge;

import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Server side of the bridge (runs on the integrated server thread).
 *
 * <p>Owns the host game (Elden Ring)&lt;-&gt;Minecraft anchor and keeps invisible terrain blocks
 * under and around the players. Terrain comes from the host game itself: batches of downward
 * rays are cast against its collision geometry (by the DLL, on the host game's thread) and
 * every hit becomes a column of {@link TerrainBlock}s whose top matches the host game's ground
 * to 1/16 of a block. If
 * the DLL can't answer ray queries, a flat floor at the hunter's feet is used instead.
 */
public final class TerrainManager {
	private TerrainManager() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	/** Only worlds created by the bridge are touched, never the player's normal worlds. */
	public static final String BRIDGE_LEVEL_NAME = "ER Bridge";
	private static final String ANCHOR_FILE = "erbridge-anchor.properties";
	private static final int FLOOR_Y = (int) CoordMap.MC_Y - 1;
	private static final int FLOOR_CHUNK_RADIUS = 2;

	/** Columns within this many blocks of a player are kept sampled. */
	private static final int SAMPLE_RADIUS = 24;
	/**
	 * Rays start this far above the player's feet and reach this far below them. Just over head
	 * height: from 4 m, the ray under a doorway landed on top of its arch, which became a pillar at
	 * head height with no floor under it (the chapel door). Ground rising higher than
	 * this is found by the high ray.
	 */
	private static final double RAY_ABOVE = 2.2;
	private static final double RAY_BELOW = 40.0;
	/** Solid terrain thickness below the host game's surface. */
	private static final int THICKNESS = 2;
	/** Probe feet, body and head, including both diagonals and both segment directions. */
	private static final double[] PROBE_HEIGHTS = {.35, 1, TerrainClearance.HEAD};
	/** Obstacles fill their column up to this far above the player's feet. */
	private static final double WALL_HEIGHT = 3.0;
	private static final int RAYS_PER_COLUMN = TerrainClearance.COARSE_RAYS;
	private static final int BATCH = 2048;
	/** High ray: finds ground that rises above the player's head (hills, cliffs ahead). */
	private static final double RAY_HIGH = 40.0;
	/** Hills/cliffs above the player are filled at most this far above the player's feet. */
	private static final double CLIFF_FILL = 6.0;

	/**
	 * Collision filter for terrain rays (Param::setAttr a/b/c), null = the host game's default
	 * (everything). Elden Ring's equivalent of the MHW hunter-movement-mesh filter (which
	 * excluded the invisible walls that only stop monsters, the Palico or the camera) is TBD,
	 * so this starts unfiltered; use the "terrain filter ..." dev command to set one.
	 */
	private static volatile int[] rayFilter = null;
	/** Surfaces with any of these attribute bits never count as walls. */
	private static volatile int wallIgnoreMask;
	private static volatile boolean resetRequested;
	private static final java.util.Map<Integer, Integer> GROUND_ATTRS = new java.util.HashMap<>();
	private static final java.util.Map<Integer, Integer> WALL_ATTRS = new java.util.HashMap<>();
	private static long lastAttrLog;
	private static final long RAY_TIMEOUT_MS = 3000;

	/** What was placed in a column: terrain blocks from bottom..top (inclusive). */
	private record Column(TerrainPrefetch.Sample sample, int top, int bottom, int floorBlock, int height, boolean obstacle) {
	}
	private record PendingColumn(long key, double y, double fromX, double fromZ) {}
	private record Forecast(ServerPlayer player, double aheadX, double aheadZ) {}

	private static final GameState STATE = new GameState();
	/** Anchors per host-game zone id; each zone gets its own Minecraft region. */
	private static final java.util.Map<Integer, CoordMap.Mapping> ANCHORS = new java.util.HashMap<>();
	/** Anchor from before per-zone regions existed; adopted by the first zone seen. */
	private static CoordMap.Mapping legacyAnchor;
	/**
	 * Recalls: requests to put every player next to the Tarnished (world opened, control back
	 * from Elden Ring, Minecraft respawn, Elden Ring placed the Tarnished anew). A generation
	 * number rather than a flag, so the client can tell whether the latest request was served
	 * without racing the server thread.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger RECALL_GEN = new java.util.concurrent.atomic.AtomicInteger();
	private static volatile int recallDone = -1;
	private static volatile long recallDoneAtMs;
	/** Last Elden Ring "life" (Protocol.H_HOST_LIFE) seen; a change means the Tarnished was placed anew. */
	private static int lastHostLife = Integer.MIN_VALUE;
	private static final LongOpenHashSet FLOORED_CHUNKS = new LongOpenHashSet();
	private static final Long2ObjectOpenHashMap<Column> COLUMNS = new Long2ObjectOpenHashMap<>();
	private static volatile boolean bridgeWorld;
	private static final java.util.Set<Long> WAITING_DETAIL = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.Map<java.util.UUID, Vec3> LAST_POSITIONS = new java.util.HashMap<>();
	private static boolean preferBackground;
	private static long coarseBatches, lastBatchMs, maxBatchMs;
	private static final java.util.Map<java.util.UUID, MovingPlatformMotion> PLATFORM_MOTION = new java.util.HashMap<>();
	private static final LongOpenHashSet MOVING_COLUMNS = new LongOpenHashSet();
	private static final MovingPlatformBoarding PLATFORM_BOARDING = new MovingPlatformBoarding();

	// Ray batch in flight
	private static int pendingSeq = -1;
	private static long pendingSince;
	private static CoordMap.Mapping pendingMap;
	private static boolean pendingStallReported;
	private static final List<PendingColumn> PENDING_COLUMNS = new ArrayList<>();
	/** A door/prop changed after these columns' rays were submitted. */
	private static final LongOpenHashSet INVALIDATED_PENDING = new LongOpenHashSet();
	private static final float[] RAYS = new float[BATCH * 6];
	private static final float[] HITS = new float[BATCH * 6];
	private static final int[] HIT_FLAGS = new int[BATCH];
	private static final int[] HIT_ATTRS = new int[BATCH];
	/** 0 = unknown, 1 = rays work, -1 = no ray support (fall back to a flat floor). */
	private static int rayMode;

	public static boolean isBridgeWorld() {
		return bridgeWorld;
	}

	public static void reset() {
		TerrainDetailManager.reset();
		bridgeWorld = false;
		WAITING_DETAIL.clear();
		LAST_POSITIONS.clear();
		PLATFORM_MOTION.clear();
		MOVING_COLUMNS.clear();
		preferBackground = false;
		coarseBatches = lastBatchMs = maxBatchMs = 0;
		FLOORED_CHUNKS.clear();
		COLUMNS.clear();
		PENDING_COLUMNS.clear();
		INVALIDATED_PENDING.clear();
		pendingSeq = -1;
		rayMode = 0;
		ANCHORS.clear();
		legacyAnchor = null;
		CoordMap.set(null);
	}

	/** Columns to sample again soon: {x, z, due time ms[, radius]} (a struck prop may have broken, a door opened). */
	private static final java.util.concurrent.ConcurrentLinkedQueue<long[]> RESAMPLE = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/**
	 * The player punched Elden Ring terrain at {@code pos}: strike the looked-at point there (the DLL
	 * fires the player's attack at it, which breaks breakable objects), then resample around it.
	 */
	public static void strike(net.minecraft.world.entity.player.Player player, BlockPos pos) {
		CoordMap.Mapping map = CoordMap.get();
		if (map == null || map.provisional()) {
			return;
		}
		// A terrain voxel can start up to a block before the real prop surface.
		// Trace the entire reach in Elden Ring rather than stopping at that proxy voxel.
		Vec3 hit = player.getEyePosition().add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
		double[] h = map.toHost(hit.x, hit.y, hit.z);
		ErLink.get().pushDamage(0L, 1.0F, (float) h[0], (float) h[1], (float) h[2], dev.ermc.bridge.link.Protocol.DAMAGE_WORLD_RAY);
		RESAMPLE.add(new long[] {pos.getX(), pos.getZ(), System.currentTimeMillis() + 900});
	}

	/**
	 * Elden Ring performed an action for the player (a door swung open, a lever moved a gate):
	 * sample the terrain around them again while things move and once they have stopped, or the
	 * old collision stays behind as an invisible wall. Any thread.
	 */
	public static void resampleAround(double x, double z, int radius) {
		long now = System.currentTimeMillis();
		for (long delay : new long[] {1500, 3500, 7000}) {
			RESAMPLE.add(new long[] {Mth.floor(x), Mth.floor(z), now + delay, radius});
		}
	}

	private static void resampleStruck() {
		long now = System.currentTimeMillis();
		for (java.util.Iterator<long[]> it = RESAMPLE.iterator(); it.hasNext(); ) {
			long[] r = it.next();
			if (r[2] > now) {
				continue;
			}
			it.remove();
			int radius = r.length > 3 ? (int) r[3] : 2;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					invalidate(ChunkPos.asLong((int) r[0] + dx, (int) r[1] + dz));
				}
			}
		}
	}

	/** Put the Minecraft players next to the Tarnished as soon as Elden Ring reports it usable. */
	public static void requestRecall() {
		RECALL_GEN.incrementAndGet();
	}

	/** True when no recall is pending and the last one was served at least {@code settleMs} ago (so the teleport reached the client). */
	public static boolean recallSettled(long settleMs) {
		return recallDone == RECALL_GEN.get() && System.currentTimeMillis() - recallDoneAtMs >= settleMs;
	}

	/** Dev: collision filter for terrain rays ({a, b, c} for Param::setAttr), or null for none. */
	public static void setRayFilter(int[] filter) {
		rayFilter = filter;
	}

	public static void setWallIgnoreMask(int mask) {
		wallIgnoreMask = mask;
	}

	/** Dev: remove all sampled terrain and sample again. */
	public static void requestReset() {
		resetRequested = true;
	}

	public static String describeSettings() {
		int[] f = rayFilter;
		return "filter " + (f == null ? "none" : String.format("(%d, %#x, %d)", f[0], f[1], f[2]))
			+ String.format(" wallIgnore %#x columns %d", wallIgnoreMask, COLUMNS.size());
	}

	private static void invalidate(long key) {
		if (pendingSeq >= 0 && BATCH_KEYS.contains(key)) INVALIDATED_PENDING.add(key);
		COLUMNS.remove(key);
		WAITING_DETAIL.remove(key);
		TerrainDetailManager.invalidate(ChunkPos.getX(key), ChunkPos.getZ(key));
	}

	public static void detailReady(int x, int z) { WAITING_DETAIL.remove(ChunkPos.asLong(x, z)); }
	public static boolean detailPending(int x, int z) { return WAITING_DETAIL.contains(ChunkPos.asLong(x, z)); }

	/** Dev diagnostics run on the server thread, so counts and mailbox timing form one snapshot. */
	public static String describePrefetch(ServerPlayer player) {
		int sampled = 0, missing = 0;
		long now = System.currentTimeMillis();
		for (int dx = -TerrainPrefetch.NEAR_RADIUS; dx <= TerrainPrefetch.NEAR_RADIUS; dx++) {
			for (int dz = -TerrainPrefetch.NEAR_RADIUS; dz <= TerrainPrefetch.NEAR_RADIUS; dz++) {
				if (dx * dx + dz * dz > TerrainPrefetch.NEAR_RADIUS * TerrainPrefetch.NEAR_RADIUS) continue;
				int x = Mth.floor(player.getX()) + dx, z = Mth.floor(player.getZ()) + dz;
				Column column = COLUMNS.get(ChunkPos.asLong(x, z));
				if (!TerrainPrefetch.needsSample(column == null ? null : column.sample(), x, z,
					player.getX(), player.getY(), player.getZ(), now, true)) sampled++;
				else missing++;
			}
		}
		Vec3 look = player.getViewVector(1).multiply(1, 0, 1).normalize();
		StringBuilder ahead = new StringBuilder();
		for (int d : new int[] {4, 8, 12, 16, 20}) {
			int x = Mth.floor(player.getX() + look.x * d), z = Mth.floor(player.getZ() + look.z * d);
			Column column = COLUMNS.get(ChunkPos.asLong(x, z));
			ahead.append(d).append("m=").append(column == null ? "pending" : "sampled").append(' ');
		}
		return "movement normal; nearby sampled " + sampled + " awaitingRefresh " + missing + " waitingDetail " + WAITING_DETAIL.size()
			+ " ahead " + ahead + "batches " + coarseBatches + " last/max ms " + lastBatchMs + "/" + maxBatchMs
			+ " coarsePending " + (pendingSeq < 0 ? 0 : now - pendingSince) + " detailPending " + TerrainDetailManager.busy();
	}

	public static void onServerStarted(MinecraftServer server) {
		reset();
		bridgeWorld = BRIDGE_LEVEL_NAME.equals(server.getWorldData().getLevelName());
		if (!bridgeWorld) {
			return;
		}
		ServerLevel overworld = server.overworld();
		// Midnight, for good: undead mobs don't burn, so they can fight Elden Ring's enemies. Minecraft
		// still renders full daylight (ClientLevelMixin); Elden Ring's light is applied on top.
		overworld.setDayTime(18000);
		server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
		server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOINSOMNIA).set(false, server);
		// One life for both games: dying costs the runes in Elden Ring, not the Minecraft inventory,
		// which would be left behind in an area the story may never return to.
		server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(true, server);
		overworld.setDefaultSpawnPos(BlockPos.containing(CoordMap.MC_X, CoordMap.MC_Y, CoordMap.MC_Z), 0.0F);
		loadAnchors(server);
		lastHostLife = Integer.MIN_VALUE;
		// The hunter is where the player really is when the world opens.
		requestRecall();
	}

	/** Minecraft respawned a player (at the world spawn, over the void): keep them standing until the recall moves them. */
	public static void onRespawn(ServerPlayer player) {
		placeFloor(player.serverLevel(), player.position());
		requestRecall();
	}

	public static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (!bridgeWorld) {
			return;
		}
		// Brand-new worlds spawn players in the void; put them next to the hunter instead.
		CoordMap.Mapping map = CoordMap.get();
		if (player.getY() < 0 && map != null) {
			teleportToHunter(server.overworld(), player, map);
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (!bridgeWorld) {
			return;
		}
		ErLink link = ErLink.get();
		boolean alive = link.poll();
		if (alive && link.snapshot(STATE)) {
			int life = link.hostLife();
			if (life != lastHostLife) {
				if (lastHostLife != Integer.MIN_VALUE) {
					LOG.info("Elden Ring placed the Tarnished anew (life {}): players go to it", life);
				}
				lastHostLife = life;
				requestRecall();
			}
			updateAnchor(server);
			int gen = RECALL_GEN.get();
			if (gen != recallDone && STATE.has(Protocol.STATE_PLAYER_VALID) && CoordMap.get() != null
				&& !server.getPlayerList().getPlayers().isEmpty()) {
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					teleportToHunter(server.overworld(), p, CoordMap.get());
				}
				recallDoneAtMs = System.currentTimeMillis();
				recallDone = gen;
			}
		}
		CoordMap.Mapping map = CoordMap.get();
		ServerLevel level = server.overworld();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (map == null || map.provisional() || !alive || rayMode < 0) {
			for (ServerPlayer player : players) {
				floorAround(level, player.chunkPosition());
			}
			return;
		}
		if (resetRequested) {
			resetRequested = false;
			for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<Column> en : COLUMNS.long2ObjectEntrySet()) {
				Column c = en.getValue();
				// Around the players the old blocks stay until the new sample replaces them (a
				// column's first sample clears what it doesn't place): clearing them at once
				// dropped Steve into the void.
				if (nearPlayer(players, ChunkPos.getX(en.getLongKey()), ChunkPos.getZ(en.getLongKey()), 4)) {
					continue;
				}
				if (c.top() != Integer.MIN_VALUE) {
					for (int y = c.bottom(); y <= c.top(); y++) {
						clearTerrain(level, ChunkPos.getX(en.getLongKey()), y, ChunkPos.getZ(en.getLongKey()));
					}
				}
			}
			COLUMNS.clear();
			WAITING_DETAIL.clear();
			TerrainDetailManager.reset();
			pendingSeq = -1;
			PENDING_COLUMNS.clear();
			LOG.info("Terrain reset ({})", describeSettings());
		}
		updatePassages(map);
		updateMovingSupport(level, map, players);
		collectResults(level, map);
		resampleStruck();
		TerrainDetailManager.collectResults(level, map);
		List<Forecast> forecasts = new ArrayList<>();
		for (ServerPlayer player : players) {
			Vec3 previous = LAST_POSITIONS.put(player.getUUID(), player.position());
			Vec3 velocity = previous == null ? Vec3.ZERO : player.position().subtract(previous).scale(20);
			Vec3 look = player.getViewVector(1);
			double[] ahead = TerrainPrefetch.ahead(player.getX(), player.getZ(), velocity.x, velocity.z, look.x, look.z);
			forecasts.add(new Forecast(player, ahead[0], ahead[1]));
		}
		if (pendingSeq < 0 && !TerrainDetailManager.busy() && !players.isEmpty()) {
			// No refinement batch may postpone unverified terrain under or ahead of a player.
			int n = prepareRays(map, players, forecasts, true);
			if (n > 0) submitRays(map, n);
			else if (!preferBackground && TerrainDetailManager.submit(map, players)) preferBackground = true;
			else {
				n = prepareRays(map, players, forecasts, false);
				if (n > 0) { submitRays(map, n); preferBackground = false; }
				else if (TerrainDetailManager.submit(map, players)) preferBackground = true;
			}
		}
		logAttrStats();
	}

	private static void updateMovingSupport(ServerLevel level, CoordMap.Mapping map, List<ServerPlayer> players) {
		LongOpenHashSet previous = new LongOpenHashSet(MOVING_COLUMNS);
		MOVING_COLUMNS.clear();
		Vec3 support = map.toMc(STATE.supportPos[0], STATE.supportPos[1], STATE.supportPos[2]);
		if (map.zone() == STATE.stageId && recallSettled(400)) PLATFORM_BOARDING.refresh(level, map, MOVING_COLUMNS);
		for (ServerPlayer player : players) {
			MovingPlatformMotion motion = PLATFORM_MOTION.computeIfAbsent(player.getUUID(), id -> new MovingPlatformMotion());
			boolean valid = STATE.has(Protocol.STATE_SUPPORT_VALID) && map.zone() == STATE.stageId && recallSettled(400)
				&& Math.abs(player.getX() - support.x) < .75 && Math.abs(player.getZ() - support.z) < .75;
			var step = motion.update(valid, STATE.supportEpoch, STATE.supportTravelY / map.unitsPerMeter(), support.y,
				player.getY(), player.onGround(), player.getAbilities().flying, player.getDeltaMovement().y, System.currentTimeMillis());
			if (!step.moving()) continue;
			MovingPlatformTerrain.move(level, player.position(), step.previousFloor(), step.floor());
			for (int x = Mth.floor(player.getX() - .3); x <= Mth.floor(player.getX() + .3); x++)
				for (int z = Mth.floor(player.getZ() - .3); z <= Mth.floor(player.getZ() + .3); z++)
					MOVING_COLUMNS.add(ChunkPos.asLong(x, z));
			if (step.dy() != 0 || step.landed()) {
				// The client predicts the same absolute support height. Its movement
				// packet may already have arrived: never add the travel a second time.
				player.setPos(player.getX(), MovingPlatformMotion.collisionHeight(step.floor()), player.getZ());
				player.setOnGround(true);
				player.fallDistance = 0;
			}
		}
		for (long key : previous) if (!MOVING_COLUMNS.contains(key)) invalidate(key);
	}

	public static boolean movingColumn(int x, int z) { return MOVING_COLUMNS.contains(ChunkPos.asLong(x, z)); }

	// -- ray-sampled terrain ----------------------------------------------------------------------

	/** Queues the unsampled columns within {@code radius} of a point, nearest first. Returns the new ray count. */
	private static int sampleAround(CoordMap.Mapping map, double ex, double ey, double ez, int radius, int n,
		boolean priority, double fromX, double fromZ) {
		int px = Mth.floor(ex);
		int pz = Mth.floor(ez);
		double py = ey;
		// Nearest columns first, so the ground under the entity appears immediately.
		for (int r = 0; r <= radius && n + RAYS_PER_COLUMN <= BATCH; r++) {
			for (int dx = -r; dx <= r && n + RAYS_PER_COLUMN <= BATCH; dx++) {
				for (int dz = -r; dz <= r && n + RAYS_PER_COLUMN <= BATCH; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r || dx * dx + dz * dz > radius * radius) {
						continue;
					}
					int x = px + dx;
					int z = pz + dz;
					long key = ChunkPos.asLong(x, z);
					if (MOVING_COLUMNS.contains(key)) continue;
					Column c = COLUMNS.get(key);
					if (!TerrainPrefetch.needsSample(c == null ? null : c.sample(), x, z, fromX, py, fromZ,
						System.currentTimeMillis(), priority) || !BATCH_KEYS.add(key)) {
						continue;
					}
					putRay(map, n++, x + 0.5, py + RAY_ABOVE, z + 0.5, x + 0.5, py - RAY_BELOW, z + 0.5);
					putRay(map, n++, x + 0.5, py + RAY_HIGH, z + 0.5, x + 0.5, py + RAY_ABOVE, z + 0.5);
					for (double height : PROBE_HEIGHTS) {
						for (TerrainClearance.Segment segment : TerrainClearance.WALL_SEGMENTS) {
							double x0 = x + segment.x0(), z0 = z + segment.z0(), x1 = x + segment.x1(), z1 = z + segment.z1();
							putRay(map, n++, x0, py + height, z0, x1, py + height, z1);
							putRay(map, n++, x1, py + height, z1, x0, py + height, z0);
						}
					}
					PENDING_COLUMNS.add(new PendingColumn(key, Math.round(py * 16) / 16.0, fromX, fromZ));
				}
			}
		}
		return n;
	}

	/** Ground is also kept under Minecraft mobs this close to a player (e.g. a zombie walking to an Elden Ring enemy). */
	private static final double MOB_SAMPLE_RANGE = 64.0;
	private static final int MOB_SAMPLE_RADIUS = 6;
	private static final int MAX_SAMPLED_MOBS = 24;
	private static final LongOpenHashSet BATCH_KEYS = new LongOpenHashSet();

	private static int prepareRays(CoordMap.Mapping map, List<ServerPlayer> players, List<Forecast> forecasts, boolean priority) {
		PENDING_COLUMNS.clear();
		INVALIDATED_PENDING.clear();
		BATCH_KEYS.clear();
		int n = 0;
		for (ServerPlayer player : players) {
			n = sampleAround(map, player.getX(), player.getY(), player.getZ(), priority ? TerrainPrefetch.NEAR_RADIUS : SAMPLE_RADIUS,
				n, priority, player.getX(), player.getZ());
		}
		if (priority) {
			for (Forecast forecast : forecasts) {
				ServerPlayer player = forecast.player();
				n = sampleAround(map, forecast.aheadX(), player.getY(), forecast.aheadZ(), TerrainPrefetch.NEAR_RADIUS,
					n, true, player.getX(), player.getZ());
			}
		}
		if (!priority && !players.isEmpty() && n + RAYS_PER_COLUMN <= BATCH) {
			ServerPlayer first = players.get(0);
			AABB range = first.getBoundingBox().inflate(MOB_SAMPLE_RANGE);
			List<net.minecraft.world.entity.Mob> mobs = first.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
				range, m -> m.isAlive());
			mobs.sort((a, b) -> Double.compare(a.distanceToSqr(first), b.distanceToSqr(first)));
			for (int i = 0; i < mobs.size() && i < MAX_SAMPLED_MOBS && n + RAYS_PER_COLUMN <= BATCH; i++) {
				net.minecraft.world.entity.Mob m = mobs.get(i);
				n = sampleAround(map, m.getX(), m.getY(), m.getZ(), MOB_SAMPLE_RADIUS, n, false, m.getX(), m.getZ());
			}
		}
		return n;
	}

	private static void submitRays(CoordMap.Mapping map, int n) {
		int[] filter = rayFilter;
		int seq = ErLink.get().submitRays(RAYS, n, filter != null ? dev.ermc.bridge.link.Protocol.RAYS_CUSTOM_FILTER : 0, filter);
		if (seq >= 0) {
			pendingSeq = seq;
			pendingMap = map;
			pendingSince = System.currentTimeMillis();
			pendingStallReported = false;
		} else {
			PENDING_COLUMNS.clear();
		}
	}

	private static void putRay(CoordMap.Mapping map, int i, double x0, double y0, double z0, double x1, double y1, double z1) {
		double[] s = map.toHost(x0, y0, z0);
		double[] e = map.toHost(x1, y1, z1);
		for (int k = 0; k < 3; k++) {
			RAYS[i * 6 + k] = (float) s[k];
			RAYS[i * 6 + 3 + k] = (float) e[k];
		}
	}

	/** A horizontal probe hit counts as an obstacle if the surface is steep and stands above the ground. */
	private static boolean isObstacle(int ray, double groundY, double probeY, CoordMap.Mapping map) {
		if (HIT_FLAGS[ray] == 0) {
			return false;
		}
		WALL_ATTRS.merge(HIT_ATTRS[ray], 1, Integer::sum);
		if ((HIT_ATTRS[ray] & wallIgnoreMask) != 0) {
			return false;
		}
		double ny = HITS[ray * 6 + 4];
		return Math.abs(ny) < 0.7 && TerrainClearance.wallNeedsColumn(true, Double.isFinite(groundY), groundY, probeY);
	}

	private static boolean columnObstacle(int down, double groundY, double sampleY, CoordMap.Mapping map) {
		int raysPerHeight = TerrainClearance.WALL_SEGMENTS.size() * 2;
		boolean obstacle = false;
		for (int ray = down + 2; ray < down + RAYS_PER_COLUMN; ray++) {
			double height = PROBE_HEIGHTS[(ray - down - 2) / raysPerHeight];
			obstacle |= isObstacle(ray, groundY, sampleY + height, map);
		}
		return obstacle;
	}

	private static void logAttrStats() {
		long now = System.currentTimeMillis();
		if (now - lastAttrLog < 30000 || (GROUND_ATTRS.isEmpty() && WALL_ATTRS.isEmpty())) {
			return;
		}
		lastAttrLog = now;
		LOG.info("Surface attributes - ground: {} walls: {}", fmtAttrs(GROUND_ATTRS), fmtAttrs(WALL_ATTRS));
	}

	private static String fmtAttrs(java.util.Map<Integer, Integer> m) {
		StringBuilder b = new StringBuilder();
		m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8)
			.forEach(e -> b.append(String.format("%#x=%d ", e.getKey(), e.getValue())));
		return b.toString();
	}

	private static void collectResults(ServerLevel level, CoordMap.Mapping map) {
		if (pendingSeq < 0) {
			return;
		}
		ErLink link = ErLink.get();
		if (!link.raysDone(pendingSeq)) {
			if (!pendingStallReported && System.currentTimeMillis() - pendingSince > RAY_TIMEOUT_MS) {
				pendingStallReported = true;
				LOG.warn("Terrain ray batch stalled; retaining mailbox ownership until native processing finishes");
			}
			return;
		}
		if (!map.equals(pendingMap)) {
			PENDING_COLUMNS.clear();
			pendingSeq = -1;
			return;
		}
		int n = PENDING_COLUMNS.size();
		link.readHits(n * RAYS_PER_COLUMN, HITS, HIT_FLAGS, HIT_ATTRS);
		if (rayMode == 0) {
			rayMode = 1;
			LOG.info("Terrain ray queries are working");
		}
		BlockState terrain = ErBridgeMod.TERRAIN.defaultBlockState();
		long now = System.currentTimeMillis();
		lastBatchMs = now - pendingSince;
		maxBatchMs = Math.max(maxBatchMs, lastBatchMs);
		coarseBatches++;
		for (int i = 0; i < n; i++) {
			PendingColumn request = PENDING_COLUMNS.get(i);
			long key = request.key();
			// Never reinsert a closed door's old collision after an opening event,
			// or carve a newly closed door using pre-event clearance. Sample again.
			if (INVALIDATED_PENDING.contains(key)) continue;
			double sampleY = request.y();
			int x = ChunkPos.getX(key);
			int z = ChunkPos.getZ(key);
			// These rays were submitted before the latest lift position. The live
			// support cells own this column until motion stops or the player steps off.
			if (MOVING_COLUMNS.contains(key)) continue;
			Column old = COLUMNS.get(key);
			if (old == null) {
				// First sample of this column since the world opened (or since a terrain reset):
				// terrain left by earlier sessions, sampled with other settings, isn't tracked.
				// Clear everything the rays could have produced, except what this sample places.
				int oldTop = Mth.floor(sampleY + RAY_HIGH);
				int oldBottom = Mth.floor(sampleY - RAY_BELOW) - THICKNESS;
				if (FLOORED_CHUNKS.contains(ChunkPos.asLong(x >> 4, z >> 4))) {
					// Also replace the temporary flat floor.
					oldTop = Math.max(oldTop, FLOOR_Y);
					oldBottom = Math.min(oldBottom, FLOOR_Y);
				}
				old = new Column(null, oldTop, oldBottom, 0, 0, false);
			}
			int top = Integer.MIN_VALUE;
			int bottom = Integer.MIN_VALUE;
			int down = i * RAYS_PER_COLUMN;
			int high = down + 1;
			boolean lowHit = HIT_FLAGS[down] != 0;
			// A high ray also hits bridges, ceilings and another wheel platform far
			// overhead. Extrude a cliff only when the body probes confirm solid
			// geometry here; otherwise leave the verified air below it open.
			boolean highSurface = HIT_FLAGS[high] != 0 && HITS[high * 6 + 4] > 0.3F;
			boolean highBlocksBody = !lowHit && highSurface && columnObstacle(down, Double.NaN, sampleY, map);
			boolean hillHit = TerrainClearance.hillNeedsColumn(lowHit, highSurface, highBlocksBody);
			double groundY = Double.NaN;
			int topBlock = Integer.MIN_VALUE, height = 16;
			boolean obstacle;
			if (lowHit || hillHit) {
				int g = lowHit ? down : high;
				GROUND_ATTRS.merge(HIT_ATTRS[g], 1, Integer::sum);
				Vec3 hit = map.toMc(HITS[g * 6], HITS[g * 6 + 1], HITS[g * 6 + 2]);
				groundY = hit.y;
				if (hillHit) {
					groundY = Math.min(groundY, sampleY + CLIFF_FILL);
				}
				topBlock = Mth.floor(groundY - 1e-4);
				height = Mth.clamp((int) Math.ceil((groundY - topBlock) * 16.0 - 1e-3), 1, 16);
				obstacle = columnObstacle(down, groundY, sampleY, map);
				top = topBlock;
				bottom = hillHit ? Math.min(topBlock, Mth.floor(sampleY) - THICKNESS) : topBlock - THICKNESS;
				if (obstacle) {
					top = Math.max(topBlock, Mth.floor(sampleY + WALL_HEIGHT));
				}
			} else {
				obstacle = columnObstacle(down, groundY, sampleY, map);
				if (obstacle) {
					// A downward ray inside a wall can miss every horizontal surface. The
					// horizontal hit must still produce a barrier; refinement can open only
					// the neighbouring subcells with native floor support and clearance.
					top = Mth.floor(sampleY + WALL_HEIGHT);
					bottom = Mth.floor(sampleY) - THICKNESS;
				}
			}
			// Refresh evidence without replacing an unchanged, already refined arch with full cubes.
			boolean changed = old.sample() == null || old.top() != top || old.bottom() != bottom
				|| old.floorBlock() != topBlock || old.height() != height || old.obstacle() != obstacle
				|| Math.abs(old.sample().y() - sampleY) >= .25;
			if (changed) {
				boolean needsDetail = obstacle || groundY > sampleY + .3;
				TerrainDetailManager.noteColumn(x, z, sampleY, needsDetail,
					top, bottom, topBlock, height, obstacle);
				WAITING_DETAIL.remove(key);
				if (top != Integer.MIN_VALUE) {
					for (int y = bottom; y <= top; y++) {
						BlockState state = !obstacle && y == topBlock ? terrain.setValue(TerrainBlock.HEIGHT, height) : terrain;
						if (needsDetail && occupied(level, x, y, z, state)) {
							// Keep the player's existing support and body space until the
							// native subcells arrive; a newly discovered wall must not entomb them.
							WAITING_DETAIL.add(key);
							continue;
						}
						setTerrain(level, x, y, z, state);
					}
				}
				if (old.top() != Integer.MIN_VALUE) {
					for (int y = old.bottom(); y <= old.top(); y++) {
						if ((top == Integer.MIN_VALUE || y < bottom || y > top)
							&& !(WAITING_DETAIL.contains(key) && occupied(level, x, y, z, terrain))) clearTerrain(level, x, y, z);
					}
				}
			}
			TerrainPrefetch.Sample sample = new TerrainPrefetch.Sample(sampleY, request.fromX(), request.fromZ(), now);
			COLUMNS.put(key, new Column(sample, top, bottom, topBlock, height, obstacle));
		}
		PENDING_COLUMNS.clear();
		pendingSeq = -1;
	}

	// -- open doorways (the DLL's ErmcPassageTable) ------------------------------------------------

	/** Doubles per passage, in Minecraft coordinates: x, z, floor y, along x, along z, half width, half depth. */
	private static final int PF = 7;
	private static final float[] PASS_RAW = new float[Protocol.MAX_PASSAGES * Protocol.PASSAGE_FLOATS];
	private static final int[] PASS_ZONE = new int[1];
	private static double[] passages = new double[0];
	private static it.unimi.dsi.fastutil.ints.IntOpenHashSet passageIds = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

	/**
	 * Elden Ring's open doorways near the player. A 1 m opening at an angle to the block grid can't
	 * be represented by 1-block wall columns. Opening or closing a door invalidates its coarse
	 * samples; nearby wall voxels are then refined using verified native clearance and floors.
	 */
	private static void updatePassages(CoordMap.Mapping map) {
		int n = ErLink.get().readPassages(PASS_RAW, PASS_ZONE);
		if (n < 0) {
			return;
		}
		if (PASS_ZONE[0] != map.zone()) {
			n = 0;
		}
		double s = 1.0 / map.unitsPerMeter();
		double[] p = new double[n * PF];
		it.unimi.dsi.fastutil.ints.IntOpenHashSet ids = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
		for (int i = 0; i < n; i++) {
			int o = i * Protocol.PASSAGE_FLOATS;
			Vec3 c = map.toMc(PASS_RAW[o], PASS_RAW[o + 1], PASS_RAW[o + 2]);
			double yaw = PASS_RAW[o + 3];
			p[i * PF] = c.x;
			p[i * PF + 1] = c.z;
			p[i * PF + 2] = c.y;
			p[i * PF + 3] = Math.sin(yaw);
			p[i * PF + 4] = map.flipZ() ? -Math.cos(yaw) : Math.cos(yaw);
			p[i * PF + 5] = PASS_RAW[o + 4] * s;
			p[i * PF + 6] = PASS_RAW[o + 5] * s;
			ids.add(Float.floatToRawIntBits(PASS_RAW[o + 7]));
		}
		if (!ids.equals(passageIds)) {
			resamplePassages(passages);
			resamplePassages(p);
			LOG.info("Open doorways: {} (resampling collision)", n);
			passageIds = ids;
		}
		passages = p;
	}

	private static void resamplePassages(double[] p) {
		for (int i = 0; i + PF <= p.length; i += PF) {
			int r = (int) Math.ceil(p[i + 5] + p[i + 6]) + 1;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					invalidate(ChunkPos.asLong(Mth.floor(p[i]) + dx, Mth.floor(p[i + 1]) + dz));
				}
			}
		}
	}

	private static boolean nearPlayer(List<ServerPlayer> players, int x, int z, int radius) {
		for (ServerPlayer p : players) {
			if (Math.abs(Mth.floor(p.getX()) - x) <= radius && Math.abs(Mth.floor(p.getZ()) - z) <= radius) {
				return true;
			}
		}
		return false;
	}

	private static void setTerrain(ServerLevel level, int x, int y, int z, BlockState state) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState cur = level.getBlockState(pos);
		if (cur.isAir() || ErBridgeMod.isTerrain(cur)) {
			if (cur != state) {
				level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}

	private static boolean occupied(ServerLevel level, int x, int y, int z, BlockState state) {
		AABB block = new AABB(x, y, z, x + 1, y + state.getValue(TerrainBlock.HEIGHT) / 16.0, z + 1);
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator() && player.getBoundingBox().deflate(1e-5).intersects(block)) return true;
		}
		return false;
	}

	private static void clearTerrain(ServerLevel level, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (ErBridgeMod.isTerrain(level.getBlockState(pos))) {
			level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	// -- anchor ------------------------------------------------------------------------------------

	private static void updateAnchor(MinecraftServer server) {
		CoordMap.Mapping m = CoordMap.get();
		double upm = STATE.unitsPerMeter > 0 ? STATE.unitsPerMeter : 100.0;
		int zone = STATE.stageId;
		if (zone == -1 || zone == 0) {
			return;  // loading screen: not a place
		}
		if (STATE.has(Protocol.STATE_PLAYER_VALID)) {
			if (m == null || m.provisional() || m.zone() != zone) {
				CoordMap.Mapping z = ANCHORS.get(zone);
				if (z == null && legacyAnchor != null) {
					z = new CoordMap.Mapping(legacyAnchor.ax(), legacyAnchor.ay(), legacyAnchor.az(), legacyAnchor.unitsPerMeter(),
						legacyAnchor.flipZ(), false, zone, 0);
					legacyAnchor = null;
				}
				if (z == null) {
					int region = ANCHORS.values().stream().mapToInt(CoordMap.Mapping::region).max().orElse(-1) + 1;
					z = new CoordMap.Mapping(STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2], upm, CoordMap.HOST_FLIP_Z, false, zone, region);
					LOG.info("New Elden Ring zone {}: anchored to hunter at {}, {}, {} (region {})", zone,
						STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2], region);
				} else {
					LOG.info("Elden Ring zone {} (region {})", zone, z.region());
				}
				ANCHORS.put(zone, z);
				CoordMap.set(z);
				saveAnchors(server);
				boolean moved = false;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					// Only move players who aren't already in this zone's region.
					if (!z.contains(p.getX())) {
						teleportToHunter(server.overworld(), p, z);
						moved = true;
					}
				}
			}
		} else if (m == null) {
			// The host game is running but the hunter isn't known yet: map its origin so rendering
			// can already be tested. Replaced as soon as the hunter's position is available.
			CoordMap.set(new CoordMap.Mapping(0, 0, 0, upm, CoordMap.HOST_FLIP_Z, true, 0, 0));
		}
	}

	private static void teleportToHunter(ServerLevel level, ServerPlayer player, CoordMap.Mapping map) {
		boolean known = STATE.has(Protocol.STATE_PLAYER_VALID);
		Vec3 target = known
			? map.toMc(STATE.playerPos[0], STATE.playerPos[1], STATE.playerPos[2])
			: new Vec3(map.originX(), CoordMap.MC_Y, CoordMap.MC_Z);
		// Something to stand on before the terrain rays for this spot come back.
		placeFloor(level, target);
		// Take the Tarnished's place, looking where it looks (it stands in for this player
		// from now on). Its quaternion rotates the character's local forward, -Z.
		float yaw = player.getYRot();
		if (known) {
			double a = 2.0 * Math.atan2(STATE.playerQuat[1], STATE.playerQuat[3]);
			Vec3 f = map.dirToMc(-Math.sin(a), 0.0, -Math.cos(a));
			yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
		}
		player.teleportTo(level, target.x, Math.ceil(target.y * 16 - 1e-4) / 16 + 0.01, target.z, yaw, 0.0F);
		LOG.info("Moved {} to the Tarnished at {} (yaw {})", player.getName().getString(), target, Math.round(yaw));
	}

	/** A small floor of terrain blocks under {@code feet}. */
	private static void placeFloor(ServerLevel level, Vec3 feet) {
		BlockState terrain = ErBridgeMod.TERRAIN.defaultBlockState();
		int fy = Mth.floor(feet.y - 1e-4);
		int height = Mth.clamp((int) Math.ceil((feet.y - fy) * 16 - 1e-3), 1, 16);
		terrain = terrain.setValue(TerrainBlock.HEIGHT, height);
		for (int dx = -1; dx <= 2; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				setTerrain(level, Mth.floor(feet.x) + dx, fy, Mth.floor(feet.z) + dz, terrain);
			}
		}
	}

	/** Fallback ground: a flat floor at the hunter's feet (exact in flat areas like the Training Area). */
	private static void floorAround(ServerLevel level, ChunkPos center) {
		for (int dx = -FLOOR_CHUNK_RADIUS; dx <= FLOOR_CHUNK_RADIUS; dx++) {
			for (int dz = -FLOOR_CHUNK_RADIUS; dz <= FLOOR_CHUNK_RADIUS; dz++) {
				int cx = center.x + dx;
				int cz = center.z + dz;
				if (FLOORED_CHUNKS.add(ChunkPos.asLong(cx, cz))) {
					BlockState terrain = ErBridgeMod.TERRAIN.defaultBlockState();
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							long key = ChunkPos.asLong(cx * 16 + x, cz * 16 + z);
							if (!COLUMNS.containsKey(key)) {
								setTerrain(level, cx * 16 + x, FLOOR_Y, cz * 16 + z, terrain);
							}
						}
					}
				}
			}
		}
	}

	// -- persistence -------------------------------------------------------------------------------

	private static Path anchorPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(ANCHOR_FILE);
	}

	private static void loadAnchors(MinecraftServer server) {
		Path p = anchorPath(server);
		if (!Files.exists(p)) {
			return;
		}
		Properties props = new Properties();
		try (Reader r = Files.newBufferedReader(p)) {
			props.load(r);
			String zones = props.getProperty("zones");
			if (zones == null && props.getProperty("ax") != null) {
				legacyAnchor = new CoordMap.Mapping(
					Double.parseDouble(props.getProperty("ax")), Double.parseDouble(props.getProperty("ay")),
					Double.parseDouble(props.getProperty("az")), Double.parseDouble(props.getProperty("unitsPerMeter", "100")),
					Boolean.parseBoolean(props.getProperty("flipZ", Boolean.toString(CoordMap.HOST_FLIP_Z))), false, 0, 0);
				LOG.info("Loaded legacy anchor; it becomes region 0 of the first zone seen");
				return;
			}
			if (zones == null || zones.isBlank()) {
				return;
			}
			for (String zs : zones.split(",")) {
				int zone = Integer.parseInt(zs.trim());
				if (zone == -1 || zone == 0) {
					continue;  // a loading screen once got anchored as a place; forget it
				}
				String k = "zone." + zone + ".";
				ANCHORS.put(zone, new CoordMap.Mapping(
					Double.parseDouble(props.getProperty(k + "ax")), Double.parseDouble(props.getProperty(k + "ay")),
					Double.parseDouble(props.getProperty(k + "az")), Double.parseDouble(props.getProperty(k + "unitsPerMeter", "100")),
					Boolean.parseBoolean(props.getProperty(k + "flipZ", Boolean.toString(CoordMap.HOST_FLIP_Z))), false, zone,
					Integer.parseInt(props.getProperty(k + "region", "0"))));
			}
			LOG.info("Loaded anchors for zones {}", ANCHORS.keySet());
		} catch (IOException | RuntimeException e) {
			LOG.warn("Ignoring unreadable anchor file {}: {}", p, e.toString());
		}
	}

	private static void saveAnchors(MinecraftServer server) {
		Properties props = new Properties();
		StringBuilder zones = new StringBuilder();
		for (CoordMap.Mapping m : ANCHORS.values()) {
			if (zones.length() > 0) {
				zones.append(',');
			}
			zones.append(m.zone());
			String k = "zone." + m.zone() + ".";
			props.setProperty(k + "ax", Double.toString(m.ax()));
			props.setProperty(k + "ay", Double.toString(m.ay()));
			props.setProperty(k + "az", Double.toString(m.az()));
			props.setProperty(k + "unitsPerMeter", Double.toString(m.unitsPerMeter()));
			props.setProperty(k + "flipZ", Boolean.toString(m.flipZ()));
			props.setProperty(k + "region", Integer.toString(m.region()));
		}
		props.setProperty("zones", zones.toString());
		try (Writer w = Files.newBufferedWriter(anchorPath(server))) {
			props.store(w, "Per zone: the host game position pinned to Minecraft (0.5 + region * 8192, 100, 0.5)");
		} catch (IOException e) {
			LOG.warn("Could not save anchors: {}", e.toString());
		}
	}
}
