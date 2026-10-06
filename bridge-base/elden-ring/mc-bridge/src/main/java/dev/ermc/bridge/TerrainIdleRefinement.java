package dev.ermc.bridge;

/** Precise refinement follows ordinary movement immediately, without an idle timer. */
public final class TerrainIdleRefinement {
    private double x, y, z;
    private long lastAt;
    private int blockX, blockY, blockZ;
    private boolean initialized, slow;
    public boolean update(double px, double py, double pz, long now) {
        double dx = px - x, dy = py - y, dz = pz - z;
        long elapsed = now - lastAt;
        boolean precise = !initialized || (elapsed > 0 && (dx * dx + dy * dy + dz * dz)
            <= Math.pow(8 * Math.min(elapsed, 250) / 1000.0, 2));
        int bx = (int)Math.floor(px), by = (int)Math.floor(py), bz = (int)Math.floor(pz);
        boolean refresh = precise && (!initialized || !slow || bx != blockX || by != blockY || bz != blockZ);
        x = px; y = py; z = pz; lastAt = now; initialized = true; slow = precise;
        blockX = bx; blockY = by; blockZ = bz;
        return refresh;
    }
    public boolean precise() { return slow; }
}
