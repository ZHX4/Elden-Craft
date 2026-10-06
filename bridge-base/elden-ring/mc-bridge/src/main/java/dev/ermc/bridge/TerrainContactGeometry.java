package dev.ermc.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Compress measured boxes without filling gaps, then remove only verified air. */
public final class TerrainContactGeometry {
    private TerrainContactGeometry() {}

    public static List<AABB> merge(List<AABB> boxes) {
        List<AABB> result = new ArrayList<>(boxes);
        for (int axis = 0; axis < 3; axis++) {
            final int along = axis, first = (axis + 1) % 3, second = (axis + 2) % 3;
            result.sort(Comparator.comparingDouble((AABB b) -> min(b, first))
                .thenComparingDouble(b -> max(b, first)).thenComparingDouble(b -> min(b, second))
                .thenComparingDouble(b -> max(b, second)).thenComparingDouble(b -> min(b, along)));
            List<AABB> merged = new ArrayList<>();
            AABB pending = null;
            for (AABB box : result) {
                if (pending != null && min(pending, first) == min(box, first) && max(pending, first) == max(box, first)
                        && min(pending, second) == min(box, second) && max(pending, second) == max(box, second)
                        && min(box, along) <= max(pending, along)) pending = pending.minmax(box);
                else {
                    if (pending != null) merged.add(pending);
                    pending = box;
                }
            }
            if (pending != null) merged.add(pending);
            result = merged;
        }
        // Urgent probes overlap the later dense grid. Drop contained boxes so
        // they do not add redundant coordinates and shape work every tick.
        result.sort(Comparator.comparingDouble((AABB b) -> b.getXsize() * b.getYsize() * b.getZsize()).reversed());
        List<AABB> unique = new ArrayList<>();
        for (AABB box : result) {
            boolean contained = false;
            for (AABB kept : unique) if (kept.minX <= box.minX && kept.maxX >= box.maxX
                    && kept.minY <= box.minY && kept.maxY >= box.maxY && kept.minZ <= box.minZ && kept.maxZ >= box.maxZ) {
                contained = true; break;
            }
            if (!contained) unique.add(box);
        }
        return List.copyOf(unique);
    }

    public static VoxelShape removeAir(VoxelShape original, List<AABB> air, int x, int y, int z) {
        if (original.isEmpty()) return original;
        AABB block = original.bounds().move(x, y, z);
        List<AABB> clipped = new ArrayList<>();
        for (AABB box : air) if (box.intersects(block)) clipped.add(box.intersect(block).move(-x, -y, -z));
        if (clipped.isEmpty()) return original;
        return Shapes.joinUnoptimized(original, TerrainAirShape.build(clipped), BooleanOp.ONLY_FIRST);
    }

    private static double min(AABB b, int axis) { return axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ; }
    private static double max(AABB b, int axis) { return axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ; }
}
