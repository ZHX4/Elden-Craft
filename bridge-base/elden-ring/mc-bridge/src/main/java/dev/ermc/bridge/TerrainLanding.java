package dev.ermc.bridge;

/** Recover only a measured downward crossing of a freshly verified native floor. */
public final class TerrainLanding {
    private TerrainLanding() {}
    public static boolean crossed(double previousY, double feetY, double floor, boolean flying) {
        return !flying && Double.isFinite(previousY) && Double.isFinite(feetY) && Double.isFinite(floor)
            && previousY >= floor - 1e-4 && feetY < floor - 1e-4
            && previousY - feetY > 0 && previousY - feetY <= 16;
    }
}
