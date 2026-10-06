"""Verify bounded ten-chunk staged surface prefetch and non-final native misses."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-distant-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'DistantTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainDistantPrefetch;
public class DistantTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        var cache = new TerrainDistantPrefetch();
        int farthest = 0;
        for (int i = 0; i < 21 * 21 * 256; i++) {
            var p = cache.next(-10, 3);
            int radius = Math.max(Math.abs((p.x() >> 4) + 10), Math.abs((p.z() >> 4) - 3));
            check(radius <= 10, "prefetch stays inside ten chunks, including negative coordinates");
            farthest = Math.max(farthest, radius);
        }
        check(farthest == 10, "the cursor must actually reach ten chunks");
        var ahead = cache.ahead(0, 0, 1, 0);
        check(ahead.x() == 128, "eight-to-ten-chunk lookahead starts immediately");
        long key = TerrainDistantPrefetch.key(-40, 64);
        check(cache.due(key, 0), "new area is eligible");
        cache.attempted(key, 100);
        check(!cache.due(key, 9999) && cache.due(key, 10100), "unstreamed native geometry retries rather than becoming permanent air");
        cache.store(key, Double.NaN, 100);
        check(cache.take(key, 200) == null, "invalid samples cannot create terrain");
        cache.store(key, 17.25, 100);
        check(cache.take(key, 200).y() == 17.25 && cache.take(key, 200) == null, "ground cache is consumed once when approaching");
        double[] walls = {1, 17.8, 2, 1.5, 18.2, 2};
        cache.store(key, 17.25, 100, walls);
        walls[0] = 900;
        var staged = cache.take(key, 200);
        check(staged.obstacles()[0] == 1 && staged.obstacles().length == 6,
            "first-pass wall contacts are retained independently of reused ray buffers");
        var copy = staged.obstacles(); copy[0] = 800;
        check(staged.obstacles()[0] == 1, "cached wall contacts cannot be mutated by readers");
        cache.store(key, 18, 100); cache.invalidate(key);
        check(cache.take(key, 200) == null && cache.due(key, 200), "door/prop invalidation discards distant evidence too");
        for (int i = 0; i < 40000; i++) cache.store(i, i, 100);
        check(cache.cachedCount() == TerrainDistantPrefetch.MAX_CACHED, "distant cache has a hard memory bound");
        cache.clear(); check(cache.cachedCount() == 0, "world reset clears distant geometry");
        System.out.println("PASS: 10-chunk coverage, immediate lookahead, staged floor/wall contacts, retries and bounded memory");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainDistantPrefetch.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'DistantTest'], check=True)
