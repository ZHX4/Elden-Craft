package dev.ermc.bridge;

/** Reconstruct a whole 16x16x16 collision block using shared corner rays. */
public final class TerrainGeometry {
    private TerrainGeometry() {}
    public static final int GRID = 16, LAYERS = 16, ROWS = GRID, SLICES = 1;
    public static final int CELLS = GRID * GRID, PREFIX = 2, EDGE = GRID + 1;
    public static final int POINTS = EDGE * EDGE;
    public static final int VERTICAL_RAYS = PREFIX + POINTS * (LAYERS + 1);
    public static final int DIAGONAL_RAYS = VERTICAL_RAYS + POINTS * 2;
    public static final int X_RAYS = DIAGONAL_RAYS + LAYERS * 4;
    public static final int Z_RAYS = X_RAYS + POINTS * 2;
    public static final int CENTRE_RAYS = Z_RAYS + POINTS * 2;
    public static final int COUNT = CENTRE_RAYS + CELLS * 2;
    // Fractional feet heights put a standing player's head in the third block.
    public static final int[] HEIGHTS = {0, 1, 2, -1, 3, -2};
    public static final int PARTS = HEIGHTS.length;

    public static void rays(double[] out, int bx, int by, int bz, int unused,
            double eyeX, double eyeY, double eyeZ, double feetY) {
        put(out, 0, eyeX, eyeY - .1, eyeZ, eyeX, eyeY, eyeZ);
        put(out, 1, eyeX, eyeY, eyeZ, eyeX, feetY - 512, eyeZ);
        for (int y = 0; y <= LAYERS; y++) for (int z = 0; z <= GRID; z++) for (int x = 0; x <= GRID; x++)
            put(out, vertex(x, y, z), eyeX, eyeY, eyeZ, bx + x / 16.0, by + y / 16.0, bz + z / 16.0);
        for (int z = 0; z <= GRID; z++) for (int x = 0; x <= GRID; x++) {
            double cx = bx + x / 16.0, cz = bz + z / 16.0;
            int ray = VERTICAL_RAYS + (z * EDGE + x) * 2;
            put(out, ray, cx, by - .125, cz, cx, by + 1.125, cz);
            put(out, ray + 1, cx, by + 1.125, cz, cx, by - .125, cz);
        }
        // Sample centres as well as edges: Havok can miss triangles on their
        // shared edges and rays that begin exactly on/inside a floor surface.
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            int ray = CENTRE_RAYS + (z * GRID + x) * 2;
            double cx = bx + (x + .5) / GRID, cz = bz + (z + .5) / GRID;
            put(out, ray, cx, by + 1.125, cz, cx, by - .125, cz);
            put(out, ray + 1, cx, by - .125, cz, cx, by + 1.125, cz);
        }
        for (int y = 0; y < LAYERS; y++) {
            double mid = by + (y + .5) / 16.0;
            int ray = DIAGONAL_RAYS + y * 4;
            put(out, ray, bx, mid, bz, bx + 1, mid, bz + 1);
            put(out, ray + 1, bx + 1, mid, bz + 1, bx, mid, bz);
            put(out, ray + 2, bx, mid, bz + 1, bx + 1, mid, bz);
            put(out, ray + 3, bx + 1, mid, bz, bx, mid, bz + 1);
        }
        for (int y = 0; y <= LAYERS; y++) for (int cross = 0; cross <= GRID; cross++) {
            double h = by + y / 16.0, side = cross / 16.0;
            int offset = (y * EDGE + cross) * 2;
            put(out, X_RAYS + offset, bx, h, bz + side, bx + 1, h, bz + side);
            put(out, X_RAYS + offset + 1, bx + 1, h, bz + side, bx, h, bz + side);
            put(out, Z_RAYS + offset, bx + side, h, bz, bx + side, h, bz + 1);
            put(out, Z_RAYS + offset + 1, bx + side, h, bz + 1, bx + side, h, bz);
        }
    }

    private static int vertex(int x, int y, int z) { return PREFIX + (y * EDGE + z) * EDGE + x; }
    private static void put(double[] out, int ray, double x, double y, double z, double ex, double ey, double ez) {
        int i = ray * 6;
        out[i] = x; out[i + 1] = y; out[i + 2] = z;
        out[i + 3] = ex; out[i + 4] = ey; out[i + 5] = ez;
    }

    /** Visibility opens air; actual surface points add collision, including one-sided geometry. */
    public static int refine(int coarse, int cell, int[] flags, double[] hits, int bx, int by, int bz, int unused) {
        int mask = coarse & 0xFFFF, x = cell % GRID, z = cell / GRID;
        double x0 = bx + x / 16.0, z0 = bz + z / 16.0;
        for (int y = 0; y < LAYERS; y++) {
            double bottom = by + y / 16.0, top = by + (y + 1) / 16.0;
            boolean clear = true, surface = false;
            for (int corner = 0; corner < 8; corner++) {
                int ray = vertex(x + (corner & 1), y + ((corner >> 2) & 1), z + ((corner >> 1) & 1));
                if (flags[ray] != 0) {
                    clear = false;
                    surface |= surface(ray, hits, x0, bottom, z0, top);
                }
            }
            for (int corner = 0; corner < 4; corner++) {
                int ray = VERTICAL_RAYS + ((z + (corner >> 1)) * EDGE + x + (corner & 1)) * 2;
                for (int direction = 0; direction < 2; direction++)
                    if (flags[ray + direction] != 0) surface |= surface(ray + direction, hits, x0, bottom, z0, top);
                int xr = X_RAYS + ((y + (corner & 1)) * EDGE + z + (corner >> 1)) * 2;
                int zr = Z_RAYS + ((y + (corner >> 1)) * EDGE + x + (corner & 1)) * 2;
                for (int direction = 0; direction < 2; direction++) {
                    if (flags[xr + direction] != 0) surface |= surface(xr + direction, hits, x0, bottom, z0, top);
                    if (flags[zr + direction] != 0) surface |= surface(zr + direction, hits, x0, bottom, z0, top);
                }
            }
            for (int direction = 0; direction < 4; direction++) {
                int ray = DIAGONAL_RAYS + y * 4 + direction;
                if (flags[ray] != 0) surface |= surface(ray, hits, x0, bottom, z0, top);
            }
            if (surface) mask |= 1 << y;
            // A reverse ray can miss from INSIDE a one-sided solid. Only the
            // connected eye-origin visibility rays prove all eight corners air.
            else if (clear) mask &= ~(1 << y);
        }
        int centre = CENTRE_RAYS + cell * 2;
        if (flags[centre] != 0) {
            double height = hits[centre * 3 + 1] - by;
            if (height > 1e-4 && height <= 1.0001) mask |= supportMask(height);
        }
        if (flags[centre + 1] != 0) {
            double height = hits[(centre + 1) * 3 + 1] - by;
            if (height >= -1e-4 && height < .9999) {
                int bottom = Math.max(0, (int)Math.floor(height * 16 + 1e-4));
                mask |= (3 << bottom) & 0xFFFF;
            }
        }
        return mask;
    }

    /** Two connected layers below a measured tread, never a filled shaft. */
    public static int supportMask(double height) {
        int top = Math.max(0, Math.min(16, (int)Math.ceil(height * 16 - 1e-4)));
        return top == 0 ? 0 : ((1 << top) - 1) & ~((1 << Math.max(0, top - 2)) - 1);
    }

    private static boolean surface(int ray, double[] hits, double x0, double bottom, double z0, double top) {
        int h = ray * 3;
        return inside(hits[h], x0, x0 + 1.0 / GRID) && inside(hits[h + 1], bottom, top)
            && inside(hits[h + 2], z0, z0 + 1.0 / GRID);
    }
    private static boolean inside(double value, double min, double max) {
        return Double.isFinite(value) && value >= min - 1e-4 && value <= max + 1e-4;
    }

    /** Match placement to the sampled shape, rather than the inherited HEIGHT=16. */
    public static boolean replaceable(int[] cells, boolean adjacent, boolean topFace) {
        boolean full = true;
        int occupied = 0;
        for (int cell : cells) { full &= (cell & 0xFFFF) == 0xFFFF; occupied |= cell & 0xFFFF; }
        if (occupied == 0) return true;
        if (full) return false;
        // A partial cell next to the clicked surface can accept an ordinary block.
        // A low floor can accept it in this cell, avoiding a floating block above it.
        return adjacent || topFace && (occupied & 0x8000) == 0;
    }

	/** Upgrade saved 8x8 shapes without opening any previously solid space. */
	public static int[] upgrade(int[] cells) {
		if (cells.length == GRID * GRID) return cells.clone();
		if (cells.length != 64) return null;
		int[] expanded = new int[GRID * GRID];
		for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++)
			expanded[z * GRID + x] = cells[(z / 2) * 8 + x / 2] & 0xFFFF;
		return expanded;
	}
}
