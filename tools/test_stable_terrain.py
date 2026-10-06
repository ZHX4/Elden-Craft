"""Verify saved collision compatibility and the active stable movement path."""
from pathlib import Path
import json
import subprocess

root = Path(__file__).resolve().parents[1]
main = root / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
out = root / 'build/stable-terrain-test'
out.mkdir(parents=True, exist_ok=True)
jdk = next((root / '.tools/java').iterdir())
source = out / 'StableTerrainTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainShapeCells;
import dev.ermc.bridge.TerrainGeometry;
import java.util.Arrays;
public class StableTerrainTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        // Every saved subcell and every vertical layer must keep its support.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            int[] fine = new int[256]; fine[z * 16 + x] = 1 << y;
            int[] coarse = TerrainShapeCells.coarse(fine);
            check(coarse[(z / 2) * 8 + x / 2] == (1 << y));
            check(Arrays.stream(coarse).filter(v -> v != 0).count() == 1);
        }
        int[] previous = new int[64]; Arrays.fill(previous, 0x1234);
        check(Arrays.equals(previous, TerrainShapeCells.coarse(TerrainGeometry.upgrade(previous))));
        int[] copy = TerrainShapeCells.coarse(previous); copy[0] = 0;
        check(previous[0] == 0x1234);
        int[] full = new int[256]; Arrays.fill(full, 0xFFFF);
        check(Arrays.stream(TerrainShapeCells.coarse(full)).allMatch(v -> v == 0xFFFF));
        check(Arrays.stream(TerrainShapeCells.coarse(new int[256])).allMatch(v -> v == 0));
        System.out.println("PASS: saved 8x8/16x16 shapes retain every floor and wall bit");
    }
}
''', encoding='utf-8')
subprocess.run([str(jdk / 'bin/javac.exe'), '-d', str(out), str(main / 'TerrainShapeCells.java'),
                str(main / 'TerrainGeometry.java'), str(source)], check=True)
subprocess.run([str(jdk / 'bin/java.exe'), '-cp', str(out), 'StableTerrainTest'], check=True)
mc = main.parents[5]
config = json.loads((mc / 'src/main/resources/erbridge.mixins.json').read_text())
assert not set(config['mixins']) & {'CollisionGetterMixin', 'EntityCollisionMixin', 'BlockGetterMixin', 'ServerPlayerTeleportMixin'}
manager = (main / 'TerrainManager.java').read_text()
detail = (main / 'TerrainDetailManager.java').read_text()
assert 'NativeTerrainCollision.' not in manager
assert 'TerrainDistantPrefetch' not in manager and 'TerrainGeometry.COUNT' not in detail
entity = (main / 'TerrainShapeBlockEntity.java').read_text()
assert 'Arrays.fill(cells, 0xFFFF)' in entity and 'shape = Shapes.block()' in entity
native = (root / 'bridge-base/elden-ring/er-bridge/src/game.cpp').read_text()
assert 'update_terrain_contacts' not in native
print('PASS: block collision drives movement; extra native sampling and landing corrections disabled')
