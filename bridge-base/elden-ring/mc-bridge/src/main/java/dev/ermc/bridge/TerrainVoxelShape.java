package dev.ermc.bridge;

import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Arrays;

/** Builds the exact collision grid in linear time, without repeated shape unions. */
public final class TerrainVoxelShape extends ArrayVoxelShape {
    private TerrainVoxelShape(BitSetDiscreteVoxelShape grid, int[] xs, int[] ys, int[] zs) {
        super(grid, coordinates(xs), coordinates(ys), coordinates(zs));
    }

    private static double[] coordinates(int[] cuts) {
        double[] coords = new double[cuts.length];
        for (int i = 0; i < coords.length; i++) coords[i] = cuts[i] / 16.0;
        return coords;
    }

    public static VoxelShape build(int[] cells) {
        boolean full = true, empty = true;
        for (int cell : cells) { full &= (cell & 0xFFFF) == 0xFFFF; empty &= (cell & 0xFFFF) == 0; }
        if (full) return Shapes.block();
        if (empty) return Shapes.empty();
        // Remove planes whose adjacent occupancy is identical. This retains the
        // collision behavior of optimized shapes without rebuilding box unions.
        int[] xs = cuts(cells, 0), ys = cuts(cells, 1), zs = cuts(cells, 2);
        BitSetDiscreteVoxelShape grid = new BitSetDiscreteVoxelShape(xs.length - 1, ys.length - 1, zs.length - 1);
        for (int z = 0; z < zs.length - 1; z++) for (int x = 0; x < xs.length - 1; x++) {
            int mask = cells[zs[z] * 16 + xs[x]];
            for (int y = 0; y < ys.length - 1; y++) if ((mask & (1 << ys[y])) != 0) grid.fill(x, y, z);
        }
        return new TerrainVoxelShape(grid, xs, ys, zs);
    }

    private static int[] cuts(int[] cells, int axis) {
        int[] cuts = new int[17];
        int count = 1;
        for (int plane = 1; plane < 16; plane++) {
            boolean differs = false;
            if (axis == 1) {
                for (int cell : cells) if (((cell >>> plane) & 1) != ((cell >>> (plane - 1)) & 1)) {
                    differs = true; break;
                }
            } else for (int cross = 0; cross < 16; cross++) {
                int index = axis == 0 ? cross * 16 + plane : plane * 16 + cross;
                int previous = index - (axis == 0 ? 1 : 16);
                if ((cells[index] & 0xFFFF) != (cells[previous] & 0xFFFF)) { differs = true; break; }
            }
            if (differs) cuts[count++] = plane;
        }
        cuts[count++] = 16;
        return Arrays.copyOf(cuts, count);
    }
}
