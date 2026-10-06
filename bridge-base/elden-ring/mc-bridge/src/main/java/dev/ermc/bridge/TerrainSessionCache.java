package dev.ermc.bridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Each vertical band is sampled once until a door, prop or session invalidates it. */
public final class TerrainSessionCache {
    private final Map<Long, Set<Integer>> bands = new HashMap<>();
    private final Map<Long, Map<Integer, Long>> retries = new HashMap<>();
    public boolean needsSample(long column, double y) {
        return needsSample(column, y, System.currentTimeMillis());
    }
    public boolean needsSample(long column, double y, long now) {
        Set<Integer> checked = bands.get(column);
        int band = (int)Math.floor(y);
        if (checked != null && checked.contains(band)) return false;
        Map<Integer, Long> pending = retries.get(column);
        return pending == null || now >= pending.getOrDefault(band, 0L);
    }
    public void sampled(long column, double y) {
        bands.computeIfAbsent(column, key -> new HashSet<>()).add((int)Math.floor(y));
    }
    public void sampled(long column, double y, boolean measured, long now) {
        int band = (int)Math.floor(y);
        if (measured) {
            sampled(column, y);
            Map<Integer, Long> pending = retries.get(column);
            if (pending != null) { pending.remove(band); if (pending.isEmpty()) retries.remove(column); }
        } else {
            // Havok can be streaming this area. A miss is not permanent empty
            // terrain; retry at a bounded rate without monopolising the mailbox.
            retries.computeIfAbsent(column, key -> new HashMap<>()).put(band, now + 250);
        }
    }
    public void invalidate(long column) { bands.remove(column); retries.remove(column); }
    public void clear() { bands.clear(); retries.clear(); }
}
