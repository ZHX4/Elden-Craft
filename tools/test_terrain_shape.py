"""Verify and benchmark exact terrain collision using Minecraft's real shape classes."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-shape-test'
OUT.mkdir(parents=True, exist_ok=True)
cache = ROOT / '.tools/gradle-home/caches'
minecraft = next((cache / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-common').rglob('*.jar'))
jars = [minecraft]
for name in ('fastutil', 'guava', 'failureaccess', 'datafixerupper', 'slf4j-api', 'joml', 'brigadier',
             'logging', 'gson', 'authlib', 'commons-lang3', 'log4j-api', 'log4j-core', 'log4j-slf4j2-impl'):
    jars.extend((cache / 'modules-2/files-2.1').glob(f'*/{name}/*/*/*.jar'))
classpath = os.pathsep.join(map(str, jars))
source = OUT / 'TerrainShapeTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainVoxelShape;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import dev.ermc.bridge.TerrainClearance;
import dev.ermc.bridge.TerrainShapeCells;
import dev.ermc.bridge.TerrainGeometry;

public class TerrainShapeTest {
    static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static VoxelShape legacy(int[] cells) {
        List<VoxelShape> boxes = new ArrayList<>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16;) {
            int mask = cells[z * 16 + x] & 0xFFFF, end = x + 1;
            while (end < 16 && (cells[z * 16 + end] & 0xFFFF) == mask) end++;
            for (int y = 0; y < 16;) {
                if ((mask & (1 << y)) == 0) { y++; continue; }
                int top = y + 1;
                while (top < 16 && (mask & (1 << top)) != 0) top++;
                boxes.add(Shapes.box(x / 16., y / 16., z / 16., end / 16., top / 16., (z + 1) / 16.));
                y = top;
            }
            x = end;
        }
        return Shapes.or(Shapes.empty(), boxes.toArray(VoxelShape[]::new)).optimize();
    }
    static void check(int[] cells) { check(cells, true); }
    static void check(int[] cells, boolean compareLegacy) {
        VoxelShape actual = TerrainVoxelShape.build(cells);
        int[] reconstructed = new int[256];
        actual.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            for (int z = (int)Math.round(z0 * 16); z < (int)Math.round(z1 * 16); z++)
                for (int x = (int)Math.round(x0 * 16); x < (int)Math.round(x1 * 16); x++)
                    for (int y = (int)Math.round(y0 * 16); y < (int)Math.round(y1 * 16); y++)
                        reconstructed[z * 16 + x] |= 1 << y;
        });
        require(Arrays.equals(cells, reconstructed), "every occupied and clear voxel must survive plane compression");
        if (!compareLegacy) return;
        VoxelShape previous = legacy(cells);
        require(!Shapes.joinIsNotEmpty(actual, previous, BooleanOp.NOT_SAME), "exact occupancy must match old shape");
        Random random = new Random(19);
        for (int n = 0; n < 20; n++) {
            double x = random.nextDouble() * 2 - .5, y = random.nextDouble() * 2 - .5, z = random.nextDouble() * 2 - .5;
            AABB body = new AABB(x, y, z, x + .6, y + 1.8, z + .6);
            for (Direction.Axis axis : Direction.Axis.values()) for (double delta : new double[] {-.7, .7})
                require(Math.abs(actual.collide(axis, body, delta) - previous.collide(axis, body, delta)) < 1e-9,
                    "collision mismatch " + axis + " " + delta + " " + body + " direct=" + actual.collide(axis, body, delta)
                        + " old=" + previous.collide(axis, body, delta));
        }
    }
    public static void main(String[] args) {
        int[] cells = new int[256];
        check(cells);
        Arrays.fill(cells, 0xFFFF); check(cells);
        Arrays.fill(cells, 1 << 15); check(cells);
        VoxelShape bridge = TerrainVoxelShape.build(cells);
        require(bridge.collide(Direction.Axis.Y, new AABB(.1, -.4, .1, .9, .8, .9), .1) == .1,
            "a sampled bridge surface cannot fill the free space underneath");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cells[z * 16 + x] = x < 2 ? 0xFFFF : 0;
        check(cells);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cells[z * 16 + x] = x < 3 || x > 12 ? 0xFFFF : 0xF000;
        check(cells);
        // A 0.625 m free gap fits Minecraft's 0.6 m body. Downsampling it
        // to 1/8 restores wall strips and makes the same doorway impassable.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cells[z * 16 + x] = x < 3 || x > 12 ? 0xFFFF : 0;
        AABB doorwayBody = new AABB(.2, 0, .2, .8, 1.8, .8);
        VoxelShape narrow = TerrainVoxelShape.build(cells);
        require(!Shapes.joinIsNotEmpty(Shapes.create(doorwayBody), narrow, BooleanOp.AND),
            "fine doorway clearance must fit the actual Minecraft body");
        VoxelShape coarse = TerrainVoxelShape.build(TerrainGeometry.upgrade(TerrainShapeCells.coarse(cells)));
        require(Shapes.joinIsNotEmpty(Shapes.create(doorwayBody), coarse, BooleanOp.AND),
            "reproduce a full-size body blocked by 1/8 doorway rounding");
        // The standing position can be above the native floor due to rounding.
        // A small step is blocked by the old artificial head-height ceiling.
        Arrays.fill(cells, TerrainClearance.carveBetween(0xFFFF, 2, .3125, .3125 + TerrainClearance.HEAD));
        VoxelShape oldRoof = TerrainVoxelShape.build(cells).move(0, 2, 0);
        AABB steppedBody = new AABB(.2, .4375, .2, .8, .4375 + 1.8, .8);
        require(Shapes.joinIsNotEmpty(Shapes.create(steppedBody), oldRoof, BooleanOp.AND),
            "reproduce a 1/16 step blocked by a false overhead slab");
        Arrays.fill(cells, TerrainClearance.carveBetween(0xFFFF, 2, .3125, 3.0));
        VoxelShape newRoof = TerrainVoxelShape.build(cells).move(0, 2, 0);
        require(!Shapes.joinIsNotEmpty(Shapes.create(steppedBody), newRoof, BooleanOp.AND),
            "measured ceiling clearance must leave room for stepping");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cells[z * 16 + x] = (1 << (x + 1)) - 1;
        check(cells);
        Random random = new Random(713);
        for (int scene = 0; scene < 30; scene++) {
            for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(65536);
            check(cells, false);
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cells[z * 16 + x] = (1 << (1 + (x + z) % 16)) - 1;
        check(cells);
        for (int i = 0; i < 100; i++) TerrainVoxelShape.build(cells);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) TerrainVoxelShape.build(cells);
        double direct = (System.nanoTime() - start) / 1e6 / 1000;
        start = System.nanoTime();
        legacy(cells);
        double old = (System.nanoTime() - start) / 1e6;
        System.out.printf(Locale.ROOT, "PASS: exact empty/full/slope/random shapes and three-axis collisions; build %.3f ms vs legacy %.3f ms%n", direct, old);
    }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
subprocess.run([str(JDK/'bin/javac.exe'), '-cp', classpath, '-d', str(OUT),
                str(production/'TerrainGeometry.java'), str(production/'TerrainVoxelShape.java'),
                str(production/'TerrainClearance.java'), str(production/'TerrainShapeCells.java'), str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT) + os.pathsep + classpath, 'TerrainShapeTest'], check=True)
