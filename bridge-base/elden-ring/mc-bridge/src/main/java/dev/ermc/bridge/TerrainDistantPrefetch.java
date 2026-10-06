package dev.ermc.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded first-pass surface cache for background sampling up to ten chunks away. */
public final class TerrainDistantPrefetch {
    public static final int CHUNK_RADIUS = 10, MAX_SCAN = 256, MAX_CACHED = 16384;
    public record Point(int x, int z) {}
    public record Floor(double y, long at, double[] obstacles) {
        public Floor { obstacles = obstacles.clone(); }
        @Override public double[] obstacles() { return obstacles.clone(); }
    }
    private static final Point[] CHUNKS = chunks();
    private final LinkedHashMap<Long, Floor> floors = new LinkedHashMap<>();
    private final Map<Long, Long> attempted = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> entry) { return size() > MAX_CACHED; }
    };
    private int cx, cz, cursor, aheadCursor;
    private boolean initialized;
    private static Point[] chunks() {
        var points = new ArrayList<Point>();
        for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++)
            points.add(new Point(x, z));
        points.sort(Comparator.comparingInt(p -> p.x * p.x + p.z * p.z));
        return points.toArray(Point[]::new);
    }
    public static long key(int x, int z) { return (x & 0xffffffffL) | ((long)z << 32); }
    public Point next(int playerChunkX, int playerChunkZ) {
        if (!initialized || Math.abs(playerChunkX - cx) >= 4 || Math.abs(playerChunkZ - cz) >= 4) {
            cx = playerChunkX; cz = playerChunkZ; cursor = 0; initialized = true;
        }
        Point chunk = CHUNKS[cursor / 256];
        int cell = cursor % 256;
        cursor = (cursor + 1) % (CHUNKS.length * 256);
        return new Point((cx + chunk.x) * 16 + (cell & 15), (cz + chunk.z) * 16 + (cell >> 4));
    }
    public boolean due(long key, long now) { return now - attempted.getOrDefault(key, -10000L) >= 10000; }
    /** Start eight-to-ten-chunk lookahead immediately while the radial cursor advances. */
    public Point ahead(double px, double pz, double lookX, double lookZ) {
        double length = Math.hypot(lookX, lookZ);
        if (length < 1e-6) { lookX = 1; lookZ = 0; length = 1; }
        double dx = lookX / length, dz = lookZ / length;
        int index = aheadCursor++ % (33 * 17);
        double distance = 8 * 16 + index % 33, side = index / 33 - 8;
        return new Point((int)Math.floor(px + dx * distance - dz * side), (int)Math.floor(pz + dz * distance + dx * side));
    }
    public void attempted(long key, long now) { attempted.put(key, now); }
    public void store(long key, double y, long now) {
        store(key, y, now, new double[0]);
    }
    public void store(long key, double y, long now, double[] obstacles) {
        if (!Double.isFinite(y)) return;
        floors.put(key, new Floor(y, now, obstacles));
        if (floors.size() > MAX_CACHED) floors.remove(floors.keySet().iterator().next());
    }
    public Floor take(long key, long now) {
        Floor floor = floors.remove(key);
        return floor != null && now - floor.at <= 120000 ? floor : null;
    }
    public void invalidate(long key) { floors.remove(key); attempted.remove(key); }
    public int cachedCount() { return floors.size(); }
    public void clear() { floors.clear(); attempted.clear(); initialized = false; cursor = aheadCursor = 0; }
}
