"""Run the clearance safety rules without launching either game."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-clearance-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainClearanceTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainClearance;
import java.util.Arrays;

public class TerrainClearanceTest {
    static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    static boolean crossesCorner(TerrainClearance.Segment ray, boolean reverse) {
        double start = ray.x0() + ray.z0() - .2;
        double end = ray.x1() + ray.z1() - .2;
        // A one-sided wall only reports entry; reversing the segment must still find it.
        return reverse ? end > 0 && start < 0 : start > 0 && end < 0;
    }
    public static void main(String[] args) {
        require(!crossesCorner(TerrainClearance.WALL_SEGMENTS.get(0), false)
            && !crossesCorner(TerrainClearance.WALL_SEGMENTS.get(1), false),
            "reproduce the observed wall corner missed by both centre lines");
        require(TerrainClearance.WALL_SEGMENTS.stream().anyMatch(ray -> crossesCorner(ray, false) || crossesCorner(ray, true)),
            "diagonals must detect a thin wall across a column corner, including a one-sided face");
        require(TerrainClearance.refreshBody(0, 0xFFFF, 100, 100) == 0xFFFF,
            "new native obstruction must close an earlier opening");
        require(TerrainClearance.refreshBody(0xFFFF, 0, 99, 100) == 0xFFFF,
            "rechecking body clearance retains existing floor support");
        require(TerrainClearance.refreshBody(0xC000, 0xFFFF, 101, 100) == 0xFFFF,
            "rechecking restores the body while retaining the ceiling");
        require(TerrainClearance.carve(TerrainClearance.refreshBody(0, 0xFFFF, 100, 100), 100, 100, 100) == 0,
            "fresh confirmed clearance keeps the arch open after revalidation");
        // Observed in the chapel: both downward rays miss inside the wall,
        // but a horizontal ray hits it. It must remain a solid barrier.
        require(TerrainClearance.wallNeedsColumn(true, false, Double.NaN, 101), "wall without sampled floor must block");
        require(TerrainClearance.wallNeedsColumn(true, true, 100, 101), "wall over a floor must block");
        require(!TerrainClearance.wallNeedsColumn(false, false, Double.NaN, 101), "no hit must not invent a wall");
        require(!TerrainClearance.wallNeedsColumn(true, true, 101, 101), "level ground must not become a wall");
        // Recorded at the wheel lift: no low floor, a high floor 31.15 m
        // overhead, and all 24 body probes clear. It is not a solid cliff.
        require(!TerrainClearance.hillNeedsColumn(false, true, false), "overhead floor over empty space must not extrude an invisible pillar");
        require(TerrainClearance.hillNeedsColumn(false, true, true), "a cliff confirmed by body collision must still block");
        require(!TerrainClearance.hillNeedsColumn(true, true, true), "an overhead bridge must not replace a low floor");
        require(!TerrainClearance.hillNeedsColumn(false, false, true), "a wall without a high floor uses wall collision, not fabricated ground");
        int[] hits = new int[TerrainClearance.RAYS_PER_CELL];
        Arrays.fill(hits, 0, TerrainClearance.FLOOR_RAYS, 1);
        require(TerrainClearance.clear(hits, 0, 100, 100, 100), "supported opening must clear");
        for (int i = 0; i < hits.length; i++) {
            int saved = hits[i];
            hits[i] = i < TerrainClearance.FLOOR_RAYS ? 0 : 1;
            require(!TerrainClearance.clear(hits, 0, 100, 100, 100), "missing floor or any obstruction must block: " + i);
            hits[i] = saved;
        }
        require(!TerrainClearance.clear(hits, 0, 98, 100, 100), "floor corner over a drop must block");
        require(!TerrainClearance.clear(hits, 0, 100, 101, 100), "solid rise must block");
        require(!TerrainClearance.clear(hits, 0, Double.NaN, 100, 100), "invalid sample must block");
        require(TerrainClearance.carve(0xFFFF, 99, 100, 100) == 0xFFFF, "retain the floor");
        require(TerrainClearance.carve(0xFFFF, 100, 100, 100) == 0, "clear feet-to-head body space");
        require(TerrainClearance.carve(0xFFFF, 101, 100, 100) == 0xC000, "retain ceiling above verified headroom");
        require(TerrainClearance.carve(0xFFFF, 102, 100, 100) == 0xFFFF, "retain higher walls");
        require(TerrainClearance.carve(0xFFFF, 100, 100.1, 100) == 3, "round the actual floor upward");
        require(TerrainClearance.floorMask(99, 100) == 0xFFFF, "full floor support");
        require(TerrainClearance.floorMask(100, 100.1) == 3, "fractional floor support");
        require(TerrainClearance.floorMask(100, 100) == 0, "do not fill air above the floor");
        // Two coarse wall columns abut a 1 m arch. Subcells wholly inside it give
        // a .75 m conservative opening even when its edges fall inside the columns.
        int free = 0;
        for (int x = 0; x < 16; x++) {
            double left = x / 8.0, right = (x + 1) / 8.0;
            if (left > .45 && right < 1.45) free++;
        }
        require(free / 8.0 >= .6, "a supported 1 m arch must fit Minecraft's .6 m body");
        System.out.println("PASS: corner and one-sided walls, stale openings, narrow arch, body probes and floor safety");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainClearance.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainClearanceTest'], check=True)
