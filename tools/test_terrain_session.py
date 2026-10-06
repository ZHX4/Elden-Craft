"""Check that returning from flight or to a corridor does not regenerate its collision."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-session-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'TerrainSessionTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainSessionCache;
public class TerrainSessionTest {
    static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        var cache = new TerrainSessionCache();
        require(cache.needsSample(17, 409.2), "first visit generates the terrain");
        cache.sampled(17, 409.2);
        for (int tick = 0; tick < 10000; tick++)
            require(!cache.needsSample(17, 409 + (tick % 16) / 16.0), "walking and elapsed time reuse the same band");
        require(cache.needsSample(17, 460), "an unseen flight height gets its own geometry");
        cache.sampled(17, 460);
        require(!cache.needsSample(17, 409.7), "landing back on existing ground does not regenerate it");
        require(!cache.needsSample(17, 460.5), "returning to a flight height reuses its geometry");
        cache.sampled(18, 409);
        cache.invalidate(17);
        require(cache.needsSample(17, 409) && cache.needsSample(17, 460), "door or prop change invalidates affected column");
        require(!cache.needsSample(18, 409), "unrelated walls stay cached");
        cache.sampled(19, -0.1);
        require(!cache.needsSample(19, -0.9) && cache.needsSample(19, 0), "negative heights use floor, not truncation");
        cache.sampled(20, 409.9999);
        require(!cache.needsSample(20, 409.9999) && cache.needsSample(20, 410), "fractional flight near a boundary cannot loop on the wrong band");
        cache.clear();
        require(cache.needsSample(18, 409), "a new world session regenerates terrain once");
        cache.sampled(30, 409, false, 1000);
        require(!cache.needsSample(30,409,1100),"streaming misses wait for the bounded retry delay");
        require(cache.needsSample(30,409,1250),"an initially unloaded native floor must be sampled again");
        cache.sampled(30,409,true,1250);
        require(!cache.needsSample(30,409,1000000),"successful retry restores normal session reuse");
        cache.sampled(31,409,false,2000);cache.invalidate(31);
        require(cache.needsSample(31,409,2001),"door invalidation bypasses a pending miss retry");
        System.out.println("PASS: once per session and height band, idle reuse, flight return, selective invalidation and negative heights");
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainSessionCache.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainSessionTest'], check=True)
