package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Measures overlapping body-sized patches before walking reaches coarse cubes.
 * Uses the shared bounded ray mailbox and writes ordinary server block shapes. */
public final class TerrainTravelClearance {
    private TerrainTravelClearance() {}
    private static final int PATCHES = 32, BODY_RAYS = 53;
    private static final double HALF = .375, HEIGHT = 4;
    private static final float[] RAYS = new float[PATCHES * BODY_RAYS * 6];
    private static final float[] HITS = new float[RAYS.length];
    private static final int[] FLAGS = new int[PATCHES * BODY_RAYS], ATTRS = FLAGS.clone();
    private static final double[] X = new double[PATCHES], Z = X.clone(), FLOOR = X.clone();
    private static final int[] OFFSETS = new int[PATCHES];
    private static final double[] HEIGHTS = {.15, .9, TerrainClearance.BODY_HEIGHT};
    private static CoordMap.Mapping requestedMap;
    private static Vec3 previous, heading, lastLook;
    private static long submittedAt;
    private static double maxStep;
    private static long waves, lastWaveMs;
    private static int clearedPatches;
    private static int pending = -1, count, version, requestedVersion;
    private static boolean floors;

    public static boolean busy() { return pending >= 0; }
    public static void reset() { pending = -1; previous = heading = lastLook = null; requestedMap = null; submittedAt = 0; waves = lastWaveMs = 0; clearedPatches = 0; version++; }
    public static void invalidate() { version++; submittedAt = 0; }
    public static String describe() {
        return "travel waves " + waves + " last ms " + lastWaveMs + " clear patches " + clearedPatches
            + " pending ms " + (busy() ? System.currentTimeMillis() - submittedAt : 0);
    }

    public static boolean submit(CoordMap.Mapping map, List<ServerPlayer> players) {
        if (busy() || players.isEmpty()) return false;
        ServerPlayer player = players.getFirst();
        maxStep = player.maxUpStep();
        Vec3 feet = player.position(), look = player.getViewVector(1).multiply(1, 0, 1).normalize();
        Vec3 movement = previous == null ? Vec3.ZERO : feet.subtract(previous);
        boolean moved = movement.horizontalDistanceSqr() > .12 * .12 || previous == null || Math.abs(movement.y) > .125;
        boolean turned = lastLook == null || look.dot(lastLook) < .95;
        long now = System.currentTimeMillis();
        if (map.equals(requestedMap) && now - submittedAt < (moved || turned ? 100 : 500)) return false;
        if (movement.horizontalDistanceSqr() > .01 && movement.lengthSqr() < 256)
            heading = movement.multiply(1, 0, 1).normalize();
        else heading = look.horizontalDistanceSqr() > .01 ? look : heading;
        if (heading == null) heading = new Vec3(0, 0, 1);
        int patch = 0;
        // Near coverage in every direction permits an immediate turn or reverse.
        for (int ring = 1; ring <= 3; ring++) for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            X[patch] = feet.x + Math.cos(angle) * .5 * ring;
            Z[patch++] = feet.z + Math.sin(angle) * .5 * ring;
        }
        // Overlapping patches form a continuous four-metre movement corridor.
        for (int step = 1; step <= 8; step++) {
            X[patch] = feet.x + heading.x * step * .5;
            Z[patch++] = feet.z + heading.z * step * .5;
        }
        int ray = 0;
        for (int i = 0; i < PATCHES; i++) for (int p = 0; p < 5; p++) {
            double x = point(X[i], p, false), z = point(Z[i], p, true);
            ray(map, ray++, x, feet.y + TerrainClearance.DETAIL_RISE, z,
                x, feet.y - TerrainClearance.DETAIL_DROP, z);
        }
        count = ray; pending = ErLink.get().submitRays(RAYS, count, 0, null);
        if (pending < 0) return false;
        requestedMap = map; requestedVersion = version; floors = true;
        previous = feet; lastLook = look; submittedAt = now;
        return true;
    }

    private static double point(double centre, int p, boolean z) {
        if (p == 0) return centre;
        return centre + (((p - 1) >> (z ? 1 : 0)) & 1) * HALF * 2 - HALF;
    }

    public static void collect(ServerLevel level, CoordMap.Mapping map) {
        ErLink link = ErLink.get();
        if (!busy() || !link.raysDone(pending)) return;
        if (!map.equals(requestedMap) || version != requestedVersion) { pending = -1; return; }
        link.readHits(count, HITS, FLAGS, ATTRS);
        if (floors) {
            int ray = 0;
            Arrays.fill(OFFSETS, -1);
            for (int i = 0; i < PATCHES; i++) {
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                boolean supported = true;
                for (int p = 0; p < 5; p++) {
                    int at = (i * 5 + p) * 6;
                    supported &= FLAGS[i * 5 + p] != 0 && HITS[at + 4] > .3;
                    double y = map.toMc(HITS[at], HITS[at + 1], HITS[at + 2]).y;
                    min = Math.min(min, y); max = Math.max(max, y);
                }
                // A broad patch must not turn sloped ground or stairs into a
                // raised flat platform. Their per-cell floors use the refiner.
                if (!supported || max - min > .25 || !TerrainClearance.supportedCell(min, max, previous.y)
                        || Math.ceil(max * 16 - 1e-4) / 16 - previous.y > maxStep) continue;
                FLOOR[i] = max; OFFSETS[i] = ray;
                for (int p = 0; p < 5; p++) {
                    double x = point(X[i], p, false), z = point(Z[i], p, true);
                    ray(map, ray++, x, max + .02, z, x, max + HEIGHT, z);
                }
                for (double h : HEIGHTS) {
                    for (double cross : new double[] {-HALF, 0, HALF}) {
                        ray = pair(map, ray, X[i] - HALF, max + h, Z[i] + cross, X[i] + HALF, max + h, Z[i] + cross);
                        ray = pair(map, ray, X[i] + cross, max + h, Z[i] - HALF, X[i] + cross, max + h, Z[i] + HALF);
                    }
                    ray = pair(map, ray, X[i] - HALF, max + h, Z[i] - HALF, X[i] + HALF, max + h, Z[i] + HALF);
                    ray = pair(map, ray, X[i] - HALF, max + h, Z[i] + HALF, X[i] + HALF, max + h, Z[i] - HALF);
                }
            }
            floors = false; count = ray;
            pending = count == 0 ? -1 : link.submitRays(RAYS, count, 0, null);
            return;
        }
        Map<BlockPos, int[]> updates = new HashMap<>();
        clearedPatches = 0;
        for (int i = 0; i < PATCHES; i++) {
            int at = OFFSETS[i]; if (at < 0) continue;
            double ceiling = FLOOR[i] + HEIGHT;
            for (int p = 0; p < 5; p++) if (FLAGS[at + p] != 0) {
                int hit = (at + p) * 6;
                ceiling = Math.min(ceiling, map.toMc(HITS[hit], HITS[hit + 1], HITS[hit + 2]).y);
            }
            boolean clear = ceiling > FLOOR[i] + .02;
            for (int probe = 5; probe < BODY_RAYS; probe++)
                if (FLOOR[i] + HEIGHTS[(probe - 5) / 16] < ceiling - .001 && FLAGS[at + probe] != 0) clear = false;
            if (!clear) continue;
            clearedPatches++;
            open(level, updates, X[i] - HALF, X[i] + HALF, Z[i] - HALF, Z[i] + HALF, FLOOR[i], ceiling);
        }
        for (var entry : updates.entrySet()) TerrainDetailManager.writeMeasured(level, entry.getKey(), entry.getValue());
        lastWaveMs = System.currentTimeMillis() - submittedAt; waves++;
        pending = -1;
    }

    private static void open(ServerLevel level, Map<BlockPos, int[]> updates,
            double x0, double x1, double z0, double z1, double floor, double ceiling) {
        for (int x = Mth.floor(x0); x <= Mth.floor(x1); x++) for (int z = Mth.floor(z0); z <= Mth.floor(z1); z++) {
            if (!level.hasChunk(x >> 4, z >> 4) || TerrainManager.movingColumn(x, z)) continue;
            int left = Math.max(0, (int)Math.ceil((x0 - x) * 16 - 1e-5));
            int right = Math.min(16, (int)Math.floor((x1 - x) * 16 + 1e-5));
            int near = Math.max(0, (int)Math.ceil((z0 - z) * 16 - 1e-5));
            int far = Math.min(16, (int)Math.floor((z1 - z) * 16 + 1e-5));
            if (left >= right || near >= far) continue;
            for (int y = Mth.floor(floor); y <= Mth.floor(ceiling); y++) {
                if (TerrainClearance.carveBetween(0xFFFF, y, floor, ceiling) == 0xFFFF) continue;
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !ErBridgeMod.isTerrain(state)) continue;
                int[] cells = updates.get(pos);
                if (cells == null) {
                    cells = level.getBlockEntity(pos) instanceof TerrainShapeBlockEntity detail ? detail.fineCells() : new int[256];
                    if (state.is(ErBridgeMod.TERRAIN)) Arrays.fill(cells, (1 << state.getValue(TerrainBlock.HEIGHT)) - 1);
                    updates.put(pos, cells);
                }
                for (int cz = near; cz < far; cz++) for (int cx = left; cx < right; cx++) {
                    int cell = cz * 16 + cx;
                    cells[cell] = TerrainClearance.carveBetween(cells[cell], y, floor, ceiling);
                }
            }
        }
    }

    private static int pair(CoordMap.Mapping map, int i, double x,double y,double z,double a,double b,double c) {
        ray(map,i++,x,y,z,a,b,c);ray(map,i++,a,b,c,x,y,z);return i;
    }
    private static void ray(CoordMap.Mapping map,int i,double x,double y,double z,double a,double b,double c) {
        double[] s=map.toHost(x,y,z), e=map.toHost(a,b,c);
        for(int k=0;k<3;k++){RAYS[i*6+k]=(float)s[k];RAYS[i*6+3+k]=(float)e[k];}
    }
}
