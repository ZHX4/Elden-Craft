package dev.ermc.bridge;

/** Prediction and refresh rules, independent of Minecraft's world/thread APIs. */
public final class TerrainPrefetch {
	private TerrainPrefetch() {}
	public static final int NEAR_RADIUS = 8;
	public static final long REFRESH_MS = 2000;
	public record Sample(double y, double fromX, double fromZ, long at) {}

	public static boolean nearby(Sample sample, int x, int z) {
		return Math.hypot(x + .5 - sample.fromX(), z + .5 - sample.fromZ()) <= NEAR_RADIUS + 1;
	}

	public static boolean needsSample(Sample sample, int x, int z, double px, double py, double pz,
		long now, boolean priority) {
		if (sample == null || Math.abs(sample.y() - py) >= (priority ? .5 : 2)) return true;
		boolean close = Math.hypot(x + .5 - px, z + .5 - pz) <= NEAR_RADIUS + 1;
		return priority && close && (!nearby(sample, x, z) || now - sample.at() >= REFRESH_MS);
	}

	/** Three seconds of travel, with a twelve metre minimum even while standing still. */
	public static double[] ahead(double x, double z, double vx, double vz, double lookX, double lookZ) {
		double speed = Math.hypot(vx, vz);
		double dx = speed > .1 ? vx / speed : lookX;
		double dz = speed > .1 ? vz / speed : lookZ;
		double length = Math.hypot(dx, dz);
		if (length < 1e-6) return new double[] {x, z};
		double distance = Math.max(12, Math.min(24, speed * 3));
		return new double[] {x + dx / length * distance, z + dz / length * distance};
	}

}
