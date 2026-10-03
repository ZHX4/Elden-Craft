"""Check the native stand-in's quaternion against mapped player and door directions."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CXX = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
OUT = ROOT / 'build/player-facing-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'test.cpp'
source.write_text(r'''
#include "player_facing.h"
#include <cstdio>
#include <cstdlib>

// Rotate the character's local forward (0, 0, -1) with a Y-axis quaternion.
void check(float yaw, float expectedX, float expectedZ) {
    float qy = sinf(yaw * .5f), qw = cosf(yaw * .5f);
    float x = -2 * qw * qy, z = 2 * qy * qy - 1;
    if (fabsf(x - expectedX) > 1e-5f || fabsf(z - expectedZ) > 1e-5f) {
        std::fprintf(stderr, "Facing mismatch: (%f,%f), expected (%f,%f)\n",
                     x, z, expectedX, expectedZ);
        std::exit(1);
    }
}

int main() {
    const float headings[][3] = {
        {0, 0, -1}, {90, -1, 0}, {180, 0, 1}, {270, 1, 0},
        {-90, 1, 0}, {360, 0, -1}, {720, 0, -1},
        {30, -.5f, -.8660254f}, {-30, .5f, -.8660254f},
        {499.52182f, -.6491584f, .7606533f}
    };
    for (const auto& h : headings)
        check(mb::player_yaw_from_minecraft(h[0]), h[1], h[2]);
    // Face the same slanted doorway from both sides of its plane.
    check(mb::player_yaw_from_forward(-.8660254f, -.5f), -.8660254f, -.5f);
    check(mb::player_yaw_from_forward(.8660254f, .5f), .8660254f, .5f);
    std::puts("PASS: player headings and both sides of a slanted doorway");
}
''', encoding='utf-8')
exe = OUT / 'test.exe'
subprocess.run([str(CXX), '-std=c++17', '-static', '-I' + str(ROOT / 'bridge-base/elden-ring/er-bridge/include'),
                str(source), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
