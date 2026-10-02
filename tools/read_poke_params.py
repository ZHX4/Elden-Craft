from pathlib import Path
import os, sys, struct
root = Path(__file__).resolve().parents[1]
os.environ['ERMC_DIR'] = str(root / 'runtime')
sys.path.insert(0, str(root / 'bridge-base/elden-ring/scripts'))
from erctl import Bridge
b = Bridge()
base = next(addr for name, addr, size in b.modules() if name.lower() == 'eldenring.exe')
repository = b.u64(base + 0x3D85F58)
for index, name, size in ((8, 'attack', 0x1C8), (10, 'bullet', 0x138)):
    res = b.u64(repository + 0x88 + index * 0x48)
    file = b.u64(b.u64(res + 0x80) + 0x80)
    count = struct.unpack_from('<H', b.read(file, 0x40), 0xA)[0]
    rows = b.read(file + 0x40, count * 24)
    for i in range(count):
        row_id, offset = struct.unpack_from('<i4xQ', rows, i * 24)
        if row_id == 10176000:
            address = file + offset
            row = b.read(address, size)
            print(name, hex(address), 'atkObj='+str(struct.unpack_from('<H', row, 0x62)[0]) if index == 8
                  else 'life='+str(struct.unpack_from('<f', row, 0x10)[0])+' speed='+str(struct.unpack_from('<f', row, 0x28)[0]))
            (root / f'runtime/poke-{name}.bin').write_bytes(row)
            break
