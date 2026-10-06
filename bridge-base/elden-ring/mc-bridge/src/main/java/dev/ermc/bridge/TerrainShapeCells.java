package dev.ermc.bridge;

/** Conservatively read saved 1/16 shapes using the stable 1/8 terrain sampler. */
public final class TerrainShapeCells {
    private TerrainShapeCells() {}

    public static int[] coarse(int[] fine) {
        if (fine.length == 64) return fine.clone();
        if (fine.length != 256) throw new IllegalArgumentException("Invalid terrain shape size");
        int[] coarse = new int[64];
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            int i = z * 32 + x * 2;
            // Keep every measured floor/wall bit until fresh rays verify clearance.
            coarse[z * 8 + x] = (fine[i] | fine[i + 1] | fine[i + 16] | fine[i + 17]) & 0xFFFF;
        }
        return coarse;
    }
}
