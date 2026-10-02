from pathlib import Path
import mmap, struct, json, time
ROOT = Path(__file__).resolve().parents[1]
FILE = ROOT/'runtime/bridge.shm'
if not FILE.exists():
    print('Bridge file has not been created yet.')
    raise SystemExit(1)
with FILE.open('r+b') as f:
    with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_WRITE) as m:
        header = struct.unpack_from('<4I2Q2I2Q4Ii',m,0)
        for _ in range(20):
            a = struct.unpack_from('<I',m,0x100)[0]
            blob = m[0x100:0x300]
            b = struct.unpack_from('<I',m,0x100)[0]
            if a == b and not a & 1: break
            time.sleep(.001)
        seq, flags, frame = struct.unpack_from('<IIQ',blob)
        stage = struct.unpack_from('<I',blob,0x7c)[0]
        hb = struct.unpack_from('<Q',m,0x10)[0]
        time.sleep(.25)
        result = dict(host_pid=struct.unpack_from('<I',m,0x20)[0],minecraft_pid=struct.unpack_from('<I',m,0x24)[0],core_status=struct.unpack_from('<i',m,0x44)[0],heartbeat=hb,live=struct.unpack_from('<Q',m,0x10)[0]>hb,flags=flags,frame=frame,stage=stage,player_valid=bool(flags&2),camera_override=bool(flags&8),compositing=bool(flags&64))
        print(json.dumps(result,indent=2))
