"""Cast the production detail rays against synthetic arches, slopes and thin walls."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-geometry-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainGeometryTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainGeometry;
import java.util.*;

public class TerrainGeometryTest {
    record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}
    static List<Box> scene = new ArrayList<>();
    static double angle;
    static double eyeX = .5, eyeZ = -1;
    static boolean entryOnly, missGridEdges;
    static final double[] rays = new double[TerrainGeometry.COUNT * 6];
    static final double[] hits = new double[TerrainGeometry.COUNT * 3];
    static final int[] flags = new int[TerrainGeometry.COUNT];
    static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static double intersection(Box box, int ray) {
        double[] low = {box.x0, box.y0, box.z0}, high = {box.x1, box.y1, box.z1};
        double c = Math.cos(angle), s = Math.sin(angle);
        double sx = rays[ray * 6] - .5, sz = rays[ray * 6 + 2];
        double ex = rays[ray * 6 + 3] - .5, ez = rays[ray * 6 + 5];
        double[] startPoint = {.5 + sx * c - sz * s, rays[ray * 6 + 1], sx * s + sz * c};
        double[] endPoint = {.5 + ex * c - ez * s, rays[ray * 6 + 4], ex * s + ez * c};
        if (entryOnly && startPoint[0] > low[0] && startPoint[0] < high[0]
                && startPoint[1] > low[1] && startPoint[1] < high[1]
                && startPoint[2] > low[2] && startPoint[2] < high[2]) return Double.POSITIVE_INFINITY;
        double enter = 0, leave = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start = startPoint[axis], delta = endPoint[axis] - start;
            if (Math.abs(delta) < 1e-12) {
                if (start < low[axis] || start > high[axis]) return Double.POSITIVE_INFINITY;
            } else {
                double a = (low[axis] - start) / delta, b = (high[axis] - start) / delta;
                enter = Math.max(enter, Math.min(a, b)); leave = Math.min(leave, Math.max(a, b));
                if (leave < enter) return Double.POSITIVE_INFINITY;
            }
        }
        if (missGridEdges) {
            double px = startPoint[0] + enter * (endPoint[0] - startPoint[0]);
            double pz = startPoint[2] + enter * (endPoint[2] - startPoint[2]);
            if (Math.abs(px * 16 - Math.rint(px * 16)) < 1e-8
                    || Math.abs(pz * 16 - Math.rint(pz * 16)) < 1e-8) return Double.POSITIVE_INFINITY;
        }
        return enter;
    }
    static int[] reconstruct(int bx, int by, int bz, int initial) {
        int[] cells = new int[256];
        for (int slice = 0; slice < TerrainGeometry.SLICES; slice++) {
            Arrays.fill(rays, Double.NaN);
            TerrainGeometry.rays(rays, bx, by, bz, slice, eyeX, 1.62, eyeZ, 0);
            for (int ray = 0; ray < TerrainGeometry.COUNT; ray++) {
                for (int axis = 0; axis < 6; axis++) require(Double.isFinite(rays[ray * 6 + axis]), "every ray must be written");
                double closest = Double.POSITIVE_INFINITY;
                for (Box box : scene) closest = Math.min(closest, intersection(box, ray));
                flags[ray] = Double.isFinite(closest) ? 1 : 0;
                for (int axis = 0; axis < 3; axis++) hits[ray * 3 + axis] = flags[ray] == 0 ? Double.NaN
                    : rays[ray * 6 + axis] + closest * (rays[ray * 6 + axis + 3] - rays[ray * 6 + axis]);
            }
            require(flags[0] == 0, "reference eye must remain in native free space");
            for (int cell = 0; cell < TerrainGeometry.CELLS; cell++)
                cells[slice * TerrainGeometry.CELLS + cell] = TerrainGeometry.refine(initial, cell, flags, hits, bx, by, bz, slice);
        }
        return cells;
    }
    static boolean solid(int[] cells, int x, int y, int z) { return (cells[z * 16 + x] & (1 << y)) != 0; }
    public static void main(String[] args) {
        require(TerrainGeometry.COUNT <= 8192, "bounded detail mailbox");
        require(TerrainGeometry.CELLS == 256 && TerrainGeometry.PARTS == 6,
            "one response must refine the complete block, rather than leaving seven rows coarse");
        scene.clear();
        int[] empty = reconstruct(0, 0, 0, 0xFFFF);
        for (int cell : empty) require(cell == 0, "clear air without a floor must not retain square collision");
        scene.add(new Box(0, 0, 0, 1, 3, 1));
        int[] solidWall = reconstruct(0, 0, 0, 0xFFFF);
        for (int cell : solidWall) require(cell == 0xFFFF, "native solid wall stays blocking after refinement");
        int[] freshWall = reconstruct(0, 0, 0, 0);
        require(solid(freshWall, 7, 8, 0), "a fresh wall retains native boundary contact without a guessed column");
        scene.clear(); scene.add(new Box(.35, 0, .35, .65, 3, .65));
        int[] trunk = reconstruct(0, 0, 0, 0xFFFF);
        require(solid(trunk, 8, 8, 8), "tree trunk remains blocking");
        require(!solid(trunk, 1, 8, 1), "air beside a trunk is not replaced by a square wall");
        // A circular arch with a two-metre opening: native render/collision is the reference,
        // not an artificial rectangular passage cut into full cubes.
        scene.add(new Box(-2, -1, 0, -.5, 4, .5));
        scene.add(new Box(1.5, -1, 0, 3, 4, .5));
        for (int i = 0; i < 64; i++) {
            double x0 = -.5 + i / 32.0, x1 = x0 + 1 / 32.0;
            double centre = (x0 + x1) / 2 - .5;
            double roof = 1.2 + Math.sqrt(Math.max(0, 1 - centre * centre));
            scene.add(new Box(x0, roof, 0, x1, 4, .5));
        }
        int[] feet = reconstruct(0, 0, 0, 0xFFFF), head = reconstruct(0, 1, 0, 0xFFFF);
        for (int x = 3; x < 13; x++) for (int y = 0; y < 16; y++) {
            require(!solid(feet, x, y, 2), "full cubes must clear from the arch opening at feet height");
            require(!solid(head, x, y, 2), "full cubes must clear from the arch opening at head height");
        }
        int[] roof = reconstruct(0, 2, 0, 0xFFFF);
        require(!solid(roof, 7, 0, 2), "arch air above the player's head must also clear");
        require(solid(roof, 7, 5, 2), "native arch roof must retain collision");
        int[] side = reconstruct(1, 1, 0, 0xFFFF);
        require(!solid(side, 0, 0, 2) && solid(side, 10, 0, 2), "arch opening and side pillar must have distinct shapes");
        // The Minecraft player's square footprint must fit an angled one-metre doorway.
        scene.clear(); angle = Math.PI / 4;
        eyeX = .5 - Math.sin(angle); eyeZ = -Math.cos(angle);
        scene.add(new Box(-2, -1, -.2, 0, 4, .2));
        scene.add(new Box(1, -1, -.2, 3, 4, .2));
        for (int i = 0; i < 32; i++) {
            double x0 = i / 32.0, x1 = x0 + 1 / 32.0, centre = (x0 + x1) / 2 - .5;
            scene.add(new Box(x0, 1.85 + Math.sqrt(.25 - centre * centre), -.2, x1, 4, .2));
        }
        for (int bz = -1; bz <= 0; bz++) for (int by = 0; by <= 1; by++) {
            int[] angled = reconstruct(0, by, bz, 0xFFFF);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
                double x0 = x / 16.0, z0 = bz + z / 16.0, y0 = by + y / 16.0;
                if (x0 < .8 && x0 + .0625 > .2 && z0 < .3 && z0 + .0625 > -.3 && y0 < 1.8)
                    require(!solid(angled, x, y, z), "a .6m Minecraft body must fit a rotated 1m native arch: " + x + "," + (by * 16 + y) + "," + (bz * 16 + z));
            }
        }
        angle = 0;
        eyeX = .5; eyeZ = -1;
        // A hanging lintel retains its thin roof without a pillar underneath.
        scene.clear(); scene.add(new Box(0, 1.7, 0, 1, 1.85, .5));
        int[] lintel = reconstruct(0, 1, 0, 0xFFFF);
        require(!solid(lintel, 7, 3, 2), "space below a hanging lintel must clear");
        require(solid(lintel, 7, 12, 2), "thin ceiling must remain solid");
        // Native surface hits add detail even if coarse body rays missed the surface.
        scene.clear(); scene.add(new Box(0, .23, 0, 1, .3, .5));
        int[] ledge = reconstruct(0, 0, 0, 0);
        require(solid(ledge, 7, 4, 2) && !solid(ledge, 7, 9, 2), "low protrusion must not become a tall wall");
        // Stairs/slopes use a different height for each horizontal cell.
        scene.clear();
        for (int x = 0; x < 16; x++) scene.add(new Box(x / 16.0, -1, 0, (x + 1) / 16.0, .15 + x * .02, .5));
        int[] slope = reconstruct(0, 0, 0, 0xFFFF);
        require(solid(slope, 12, 5, 2) && !solid(slope, 2, 5, 2), "floor slope must follow the native height field");
        // A closed door must be restored, and fresh clear rays must remove it after opening.
        scene.clear(); scene.add(new Box(0, 0, .2, 1, 2, .22));
        int[] door = reconstruct(0, 0, 0, 0);
        require(solid(door, 7, 8, 3), "thin closed door must block");
        scene.clear();
        int[] open = reconstruct(0, 0, 0, door[3 * 16 + 7]);
        require(!solid(open, 7, 8, 3), "opened door must not leave stale solid cells");
        // A one-sided local face must block even when forward visibility misses it.
        Arrays.fill(flags, 0); Arrays.fill(hits, Double.NaN);
        int local = TerrainGeometry.DIAGONAL_RAYS + 8 * 4;
        flags[local + 1] = 1;
        hits[(local + 1) * 3] = .02; hits[(local + 1) * 3 + 1] = .53125; hits[(local + 1) * 3 + 2] = .02;
        require((TerrainGeometry.refine(0, 0, flags, hits, 0, 0, 0, 0) & (1 << 8)) != 0,
            "reverse diagonal must preserve a one-sided surface");
        // Hits in an occluding wall cannot fabricate collision far behind it.
        Arrays.fill(flags, 1); Arrays.fill(hits, -100);
        Arrays.fill(flags, TerrainGeometry.VERTICAL_RAYS, TerrainGeometry.COUNT, 0);
        require(TerrainGeometry.refine(0, 0, flags, hits, 0, 0, 0, 0) == 0, "occluded unknown air must not invent a wall");
        require(TerrainGeometry.refine(0xFFFF, 0, flags, hits, 0, 0, 0, 0) == 0xFFFF,
            "inside-start misses behind an occluder cannot erase previously measured collision");
        Arrays.fill(flags, 1); Arrays.fill(hits, .03125);
        require((TerrainGeometry.refine(0xFFFF, 0, flags, hits, 0, 0, 0, 0) & 1) != 0,
            "a locally confirmed solid surface must remain blocking");
        int[] old = new int[64]; old[3 * 8 + 2] = 0xA55A;
        int[] upgraded = TerrainGeometry.upgrade(old);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            require(upgraded[z * 16 + x] == old[(z / 2) * 8 + x / 2], "saved coarse cells upgrade without collision loss");
        require(TerrainGeometry.upgrade(new int[63]) == null, "malformed saved shapes are rejected");
        int[] floorPlacement = new int[256]; Arrays.fill(floorPlacement, 1 << 6);
        require(TerrainGeometry.replaceable(floorPlacement, false, true), "placing onto sampled ground uses its actual height");
        require(!TerrainGeometry.replaceable(floorPlacement, false, false), "side click keeps the clicked floor as support");
        int[] wallPlacement = new int[256]; for (int z = 0; z < 16; z++) wallPlacement[z * 16] = 0xFFFF;
        require(TerrainGeometry.replaceable(wallPlacement, true, false), "adjacent partial wall cell accepts a Minecraft block");
        require(!TerrainGeometry.replaceable(wallPlacement, false, false), "wall remains the clicked placement surface");
        Arrays.fill(wallPlacement, 0xFFFF);
        require(!TerrainGeometry.replaceable(wallPlacement, true, true), "a fully occupied native wall cannot be replaced");
        // Reproduce native triangle misses: rays from inside solids and exactly
        // on shared mesh/grid edges provide no hit, unlike solid-AABB mocks.
        entryOnly = missGridEdges = true;
        scene.clear(); scene.add(new Box(-10, -2, -10, 10, .333, 10));
        int[] nativeFloor = reconstruct(0, 0, 0, 0);
        for (int cell : nativeFloor) require((cell & 0x30) == 0x30,
            "native triangle-edge misses must not open support holes or remove tread depth");
        scene.clear();
        for (int x = 0; x < 16; x++) scene.add(new Box(x / 16., -2, -10, (x + 1) / 16., .15 + x * .02, 10));
        int[] nativeSteps = reconstruct(0, 0, 0, 0);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int expected = TerrainGeometry.supportMask(.15 + x * .02);
            require((nativeSteps[z * 16 + x] & expected) == expected,
                "native centre rays must support every tread after edge and inside-start misses");
        }
        System.out.println("PASS: 16x16x16 circular and angled arches, upper vaults, lintels, ledges, slopes, thin doors, one-sided surfaces, occlusion and save upgrade");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainGeometry.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainGeometryTest'], check=True)
