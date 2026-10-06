package dev.ermc.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Continuous wall faces reconstructed from neighbouring native hit points.
 * Never infer a plane from a single strip, join corners or extend a doorway. */
public final class TerrainWallGeometry {
    private TerrainWallGeometry() {}
    private record Point(double cross, double along, double low, double high) {}
    public record Plane(int axis, double slope, double intercept, int side,
            double start, double end, double low, double high) {
        double nx() { return side * (axis == 0 ? 1 : -slope); }
        double nz() { return side * (axis == 0 ? -slope : 1); }
        public boolean covers(AABB body) {
            double min = axis == 0 ? body.minZ : body.minX, max = axis == 0 ? body.maxZ : body.maxX;
            return min >= start - 1e-5 && max <= end + 1e-5 && body.minY < high && body.maxY > low;
        }
        public boolean supportsMovement(AABB body, AABB area) {
            if (!covers(body) || !covers(area)) return false;
            double nx = nx(), nz = nz();
            double centre = nx * ((body.minX + body.maxX) * .5) + nz * ((body.minZ + body.maxZ) * .5) - side * intercept;
            double radius = Math.abs(nx) * body.getXsize() * .5 + Math.abs(nz) * body.getZsize() * .5;
            // A plane remembers the side from which it was measured. Once the
            // whole body is on its other side, that old half-space must not
            // clamp free movement or suppress vanilla's actual wall collider.
            return centre + radius > 1e-7;
        }
        public boolean contains(AABB strip) {
            double min = axis == 0 ? strip.minZ : strip.minX, max = axis == 0 ? strip.maxZ : strip.maxX;
            double along = axis == 0 ? (side < 0 ? strip.minX : strip.maxX) : (side < 0 ? strip.minZ : strip.maxZ);
            return min >= start - .001 && max <= end + .001 && strip.minY >= low - .001
                && strip.maxY <= high + .001 && Math.abs(along - slope * (min + max) * .5 - intercept) < .015;
        }
        /** Remove only the voxel approximation immediately surrounding this face.
         * Native floor contacts remain separate, below the player's feet. */
        public AABB replacementBand() {
            double first = slope * start + intercept, last = slope * end + intercept;
            double min = Math.min(first, last) - .125, max = Math.max(first, last) + .125;
            return axis == 0 ? new AABB(min, low, start, max, high, end)
                : new AABB(start, low, min, end, high, max);
        }
    }

    public static List<Plane> fit(List<AABB> strips, Vec3 origin, double unit) {
        List<Plane> rows = new ArrayList<>();
        for (int axis : new int[]{0, 2}) for (int side : new int[]{-1, 1}) {
            List<Point> points = new ArrayList<>();
            for (AABB b : strips) {
                double depth = axis == 0 ? b.getXsize() : b.getZsize();
                if (depth > .07 / unit) continue;
                double along = axis == 0 ? (side < 0 ? b.minX : b.maxX) : (side < 0 ? b.minZ : b.maxZ);
                double from = axis == 0 ? origin.x : origin.z;
                if ((along - from) * side >= 0) continue;
                double cross = axis == 0 ? (b.minZ + b.maxZ) * .5 : (b.minX + b.maxX) * .5;
                points.add(new Point(cross, along, b.minY, b.maxY));
            }
            points.sort(Comparator.comparingDouble(Point::low).thenComparingDouble(Point::high).thenComparingDouble(Point::cross));
            for (int i = 0; i < points.size();) {
                Point first = points.get(i); int rowEnd = i + 1;
                while (rowEnd < points.size() && points.get(rowEnd).low == first.low && points.get(rowEnd).high == first.high) rowEnd++;
                for (int begin = i; begin + 1 < rowEnd;) {
                    Point a = points.get(begin), b = points.get(begin + 1);
                    double span = b.cross - a.cross;
                    if (span < .01 / unit || span > .126 / unit) { begin++; continue; }
                    double slope = (b.along - a.along) / span, intercept = a.along - slope * a.cross;
                    int end = begin + 2;
                    while (end < rowEnd && points.get(end).cross - points.get(end - 1).cross <= .126 / unit
                            && Math.abs(points.get(end).along - slope * points.get(end).cross - intercept) < .003 / unit) end++;
                    if (points.get(end - 1).cross - a.cross >= .625 / unit)
                        rows.add(new Plane(axis, slope, intercept, side, a.cross - .0625 / unit,
                            points.get(end - 1).cross + .0625 / unit, first.low, first.high));
                    begin = end - 1;
                }
                i = rowEnd;
            }
        }
        List<Plane> combined = new ArrayList<>();
        for (Plane p : rows) {
            int found = -1;
            for (int i = 0; i < combined.size(); i++) {
                Plane q = combined.get(i);
                if (p.axis == q.axis && p.side == q.side && Math.abs(p.slope - q.slope) < .005
                        && Math.abs(p.intercept - q.intercept) < .005 / unit
                        && Math.min(p.end, q.end) - Math.max(p.start, q.start) >= .625 / unit
                        && p.low <= q.high + .001 / unit && p.high >= q.low - .001 / unit) { found = i; break; }
            }
            if (found < 0) combined.add(p);
            else {
                Plane q = combined.get(found);
                combined.set(found, new Plane(q.axis, q.slope, q.intercept, q.side,
                    Math.min(p.start, q.start), Math.max(p.end, q.end), Math.min(p.low, q.low), Math.max(p.high, q.high)));
            }
        }
        // A legal step or a floating prop is not a full-height wall. Require
        // continuous measurements through the body before replacing voxel faces.
        return combined.stream().filter(p -> p.high - p.low >= 1.75 / unit).toList();
    }

    public static Vec3 slide(Vec3 movement, AABB body, List<Plane> planes) {
        Vec3 result = movement;
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (Plane p : planes) {
                if (!p.supportsMovement(body, body.expandTowards(result)) || !p.covers(body.move(result))) continue;
                double nx = p.nx(), nz = p.nz(), length = nx * nx + nz * nz;
                double distance = nx * ((body.minX + body.maxX) * .5) + nz * ((body.minZ + body.maxZ) * .5)
                    - p.side * p.intercept - Math.abs(nx) * body.getXsize() * .5 - Math.abs(nz) * body.getZsize() * .5;
                double inward = nx * result.x + nz * result.z;
                if (inward >= 0 || distance + inward >= -1e-7) continue;
                double blocked = inward + Math.max(0, distance);
                result = new Vec3(result.x - nx * blocked / length, result.y, result.z - nz * blocked / length);
                changed = true;
            }
            if (!changed) break;
        }
        return result;
    }
}
