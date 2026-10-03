"""Exercise terrain prediction and refresh without launching the games."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-prefetch-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainPrefetchTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainPrefetch;

public class TerrainPrefetchTest {
    static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    static void close(double a, double b, String description) {
        require(Math.abs(a - b) < 1e-8, description + ": " + a + " vs " + b);
    }
    public static void main(String[] args) {
        long now = 10000;
        var distant = new TerrainPrefetch.Sample(100, 0, 0, now);
        require(TerrainPrefetch.needsSample(distant, 20, 0, 18, 100, 0, now, true),
            "a wall sampled before host streaming must be rechecked ahead of arrival");
        var nearby = new TerrainPrefetch.Sample(100, 18, 0, now);
        require(!TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100, 0, now, true), "fresh evidence is reused");
        require(TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100, 0, now + 2000, true),
            "nearby streamed geometry is refreshed without a height change");
        require(TerrainPrefetch.needsSample(nearby, 20, 0, 18, 100.5, 0, now, true),
            "stairs recheck wall probes before the previous two-metre height threshold");
        double[] idle = TerrainPrefetch.ahead(0, 0, 0, 0, 1, 0);
        close(idle[0], 12, "standing player prefetches the viewed corridor");
        double[] sprint = TerrainPrefetch.ahead(0, 0, 0, 5.6, -1, 0);
        close(sprint[0], 0, "movement takes priority over a sideways camera");
        close(sprint[1], 16.8, "sprint prefetches three seconds ahead");
        require(TerrainPrefetch.ahead(0, 0, 100, 0, 1, 0)[0] <= 24, "teleport does not request an unbounded area");

        System.out.println("PASS: predictive travel, streamed/stale geometry and stairs");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainPrefetch.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainPrefetchTest'], check=True)
