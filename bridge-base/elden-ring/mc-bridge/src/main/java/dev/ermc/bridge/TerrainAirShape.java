package dev.ermc.bridge;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Exact union of measured air boxes, built once instead of repeated unions. */
public final class TerrainAirShape extends ArrayVoxelShape {
    private TerrainAirShape(Grid grid, double[] xs, double[] ys, double[] zs) { super(grid, xs, ys, zs); }

    public static VoxelShape build(List<AABB> boxes) {
        if (boxes.isEmpty()) return Shapes.empty();
        if (boxes.size() == 1) return Shapes.create(boxes.getFirst());
        double[] xs = cuts(boxes, 0), ys = cuts(boxes, 1), zs = cuts(boxes, 2);
        Grid grid = new Grid(xs.length - 1, ys.length - 1, zs.length - 1);
        for (AABB box : boxes) grid.fillRange(index(xs, box.minX), index(ys, box.minY), index(zs, box.minZ),
            index(xs, box.maxX), index(ys, box.maxY), index(zs, box.maxZ));
        return new TerrainAirShape(grid, xs, ys, zs);
    }

    private static double[] cuts(List<AABB> boxes, int axis) {
        double[] values = new double[boxes.size() * 2];
        for (int i = 0; i < boxes.size(); i++) {
            AABB b = boxes.get(i);
            values[i * 2] = axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ;
            values[i * 2 + 1] = axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ;
        }
        for (int i = 0; i < values.length; i++) if (values[i] == 0) values[i] = 0;
        Arrays.sort(values);
        int unique = 0;
        for (double value : values) if (unique == 0 || value != values[unique - 1]) values[unique++] = value;
        return Arrays.copyOf(values, unique);
    }

    private static int index(double[] values, double value) { return Arrays.binarySearch(values, value == 0 ? 0 : value); }

    private static final class Grid extends DiscreteVoxelShape {
        private final BitSet cells;
        private final int[] first, last = new int[3];
        Grid(int x, int y, int z) {
            super(x, y, z);
            cells = new BitSet(Math.multiplyExact(Math.multiplyExact(x, y), z));
            first = new int[] {x, y, z};
        }
        private int index(int x, int y, int z) { return (x * zSize + z) * ySize + y; }
        @Override public boolean isFull(int x, int y, int z) { return cells.get(index(x, y, z)); }
        @Override public boolean isEmpty() { return cells.isEmpty(); }
        @Override public int firstFull(Direction.Axis axis) { return first[axis.ordinal()]; }
        @Override public int lastFull(Direction.Axis axis) { return last[axis.ordinal()]; }
        @Override public void fill(int x, int y, int z) { fillRange(x, y, z, x + 1, y + 1, z + 1); }
        void fillRange(int x0, int y0, int z0, int x1, int y1, int z1) {
            first[0] = Math.min(first[0], x0); first[1] = Math.min(first[1], y0); first[2] = Math.min(first[2], z0);
            last[0] = Math.max(last[0], x1); last[1] = Math.max(last[1], y1); last[2] = Math.max(last[2], z1);
            for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) cells.set(index(x, y0, z), index(x, y1, z));
        }
    }
}
