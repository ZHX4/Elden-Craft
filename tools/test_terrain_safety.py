"""Check fast landing recovery and the one-shot three-second precise refresh."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-safety-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainSafetyTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainLanding;
import dev.ermc.bridge.TerrainIdleRefinement;
public class TerrainSafetyTest {
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        check(TerrainLanding.crossed(18, 8, 10, false), "fast elytra descent crossing native ground must recover");
        check(TerrainLanding.crossed(10.1, 9.9, 10, false), "a delayed collision packet must not lose landing");
        check(!TerrainLanding.crossed(8, 8.2, 10, false), "ascending or already underground is not a measured landing");
        check(!TerrainLanding.crossed(10, 10, 10, false), "static ground must never carry or snap a standing player");
        check(!TerrainLanding.crossed(12, 11, 10, false), "airborne descent above ground remains ordinary flight");
        check(!TerrainLanding.crossed(18, 8, 10, true), "creative flight must not acquire floor contact");
        check(!TerrainLanding.crossed(40, 8, 10, false), "teleport or stale discontinuity must not recover");
        check(!TerrainLanding.crossed(18, 8, Double.NaN, false), "missing native evidence cannot create a floor");
        var idle = new TerrainIdleRefinement();
        check(idle.update(1, 100, 1, 1000), "precise refinement starts immediately");
        check(!idle.update(1.04, 100, 1, 1050), "jitter does not invalidate the same cell");
        check(idle.update(2.04, 100, 1, 1300), "walking immediately refines newly approached cells");
        check(idle.precise(), "ordinary walking selects exact geometry");
        check(!idle.update(12, 100, 1, 1350), "fast flight does not restart precise batches");
        check(!idle.precise(), "fast travel uses bounded prefetch");
        check(idle.update(12, 100, 1, 1400), "slowing down immediately restores precision");
        for (int tick = 0; tick < 10000; tick++)
            check(!idle.update(12, 100, 1, 1450 + tick * 50), "stationary verified shapes do not churn");
        System.out.println("PASS: safe landings, immediate walking refinement, fast-travel fallback and cache reuse");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production/'TerrainLanding.java'),
                str(production/'TerrainIdleRefinement.java'), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainSafetyTest'], check=True)
