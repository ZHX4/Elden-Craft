package dev.ermc.bridge;

/** Geometry rules for conservative refinement of the host's coarse terrain voxels. */
public final class TerrainClearance {
	private TerrainClearance() {}
	public static final int GRID = 8;
	public static final int FLOOR_RAYS = 5;
	public static final int RAYS_PER_CELL = 29;
	public static final double HEAD = 1.875;
	public static final double FLOOR_RANGE = 0.3;
	public static final double BODY_HEIGHT = 1.801;
	public static final double DETAIL_DROP = 6;
	public static final double DETAIL_RISE = .625;

	/** Floors may descend through a doorway; the five points must agree locally. */
	public static boolean supportedCell(double min, double max, double feet) {
		return Double.isFinite(min) && Double.isFinite(max) && min <= max
			&& min >= feet - DETAIL_DROP && max <= feet + DETAIL_RISE
			&& max - min <= DETAIL_RISE;
	}

	/** Quantize toward solid surfaces, keeping the real floor and ceiling. */
	public static int carveBetween(int mask, int blockY, double floor, double ceiling) {
		if (!Double.isFinite(floor) || !Double.isFinite(ceiling) || ceiling <= floor) return mask;
		int lo = Math.max(0, Math.min(16, (int)Math.ceil((floor - blockY) * 16 - 1e-4)));
		int hi = Math.max(0, Math.min(16, (int)Math.floor((ceiling - blockY) * 16 + 1e-4)));
		if (hi <= lo) return mask;
		return mask & ~(((1 << hi) - 1) & ~((1 << lo) - 1)) & 0xFFFF;
	}
	public record Segment(double x0, double z0, double x1, double z1) {}
	/** Diagonals catch walls which clip a column's corner but miss both centre lines. */
	public static final java.util.List<Segment> WALL_SEGMENTS = java.util.List.of(
		new Segment(0, .5, 1, .5), new Segment(.5, 0, .5, 1),
		new Segment(0, 0, 1, 1), new Segment(0, 1, 1, 0));
	public static final int COARSE_RAYS = 2 + 3 * WALL_SEGMENTS.size() * 2;

	/** A wall still blocks movement when downward rays start inside it and find no floor. */
	public static boolean wallNeedsColumn(boolean wallHit, boolean floorHit, double groundY, double probeY) {
		return wallHit && (!floorHit || groundY < probeY - 0.25);
	}

	/** A high floor can belong to a bridge or ceiling over empty air, rather than a solid cliff. */
	public static boolean hillNeedsColumn(boolean lowFloorHit, boolean highFloorHit, boolean bodyHit) {
		return !lowFloorHit && highFloorHit && bodyHit;
	}

	/** All twelve visibility probes must miss, and there must be nearby ground. */
	public static boolean clear(int[] hits, int offset, double minFloor, double maxFloor, double feetY) {
		if (!Double.isFinite(minFloor) || !Double.isFinite(maxFloor)
			|| minFloor < feetY - FLOOR_RANGE || maxFloor > feetY + FLOOR_RANGE) return false;
		for (int i = 0; i < FLOOR_RAYS; i++) if (hits[offset + i] == 0) return false;
		for (int i = FLOOR_RAYS; i < RAYS_PER_CELL; i++) if (hits[offset + i] != 0) return false;
		return true;
	}

	/** Remove only verified body space; retain the floor and everything above the head. */
	public static int carve(int mask, int blockY, double floorY, double feetY) {
		int lo = Math.max(0, Math.min(16, (int) Math.ceil((floorY - blockY) * 16 - 1e-4)));
		int hi = Math.max(0, Math.min(16, (int) Math.floor((feetY + HEAD - blockY) * 16 + 1e-4)));
		if (hi <= lo) return mask;
		int free = ((1 << hi) - 1) & ~((1 << lo) - 1);
		return mask & ~free & 0xFFFF;
	}

	/** Revoke an earlier opening in body space before applying fresh native clearance. */
	public static int refreshBody(int previous, int coarse, int blockY, double feetY) {
		int body = ~carve(0xFFFF, blockY, feetY, feetY) & 0xFFFF;
		return (previous & ~body) | (coarse & body);
	}

	/** Add support only to a cell whose floor was actually hit. */
	public static int floorMask(int blockY, double floorY) {
		int height = Math.max(0, Math.min(16, (int) Math.ceil((floorY - blockY) * 16 - 1e-4)));
		return (1 << height) - 1;
	}
}
