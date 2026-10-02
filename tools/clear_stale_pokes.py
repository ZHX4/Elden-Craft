"""Release only old player-owned bridge emitters through the game's normal update."""
exec(open('tools/read_poke_params.py').read().split('for index,')[0])
manager = b.u64(base + 0x3D667A8)
world = b.u64(base + 0x3D69FF8)
# Matches kMainPlayer from this supported game build.
import re
source = (root / 'bridge-base/elden-ring/er-bridge/src/game.cpp').read_text()
offset = int(re.search(r'kMainPlayer\s*=\s*(0x[0-9A-Fa-f]+)', source)[1], 16)
owner = b.u64(b.u64(world + offset) + 8)
node = b.u64(manager + 0x28)
released = 0
seen = set()
while node and node not in seen and len(seen) < 256:
    seen.add(node)
    obj = b.read(node, 0x9d0)
    next_node = struct.unpack_from('<Q', obj, 0x998)[0]
    if (struct.unpack_from('<i', obj, 0x334)[0] == 10176000
        and struct.unpack_from('<Q', obj, 0x340)[0] == owner and obj[0x4c4] == 0):
        b.write(node + 0x4c4, b'\x01')
        released += 1
    node = next_node
print('Released stale bridge emitters:', released)
