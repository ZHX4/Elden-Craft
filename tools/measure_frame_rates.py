"""Measure host rendering and actual Minecraft frame publication independently."""
from pathlib import Path
import argparse
import json
import mmap
import struct
import time

parser = argparse.ArgumentParser()
parser.add_argument('--seconds', type=int, default=20)
parser.add_argument('--output', default='runtime/frame-rate-check.json')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
samples = []
with (root/'runtime/frames.shm').open('r+b') as ff, (root/'runtime/bridge.shm').open('r+b') as bf:
    with mmap.mmap(ff.fileno(), 0) as frames, mmap.mmap(bf.fileno(), 0) as bridge:
        before_mc = struct.unpack_from('<Q', frames, 0x10)[0]
        before_host = struct.unpack_from('<Q', bridge, 0x108)[0]
        started = time.perf_counter()
        for _ in range(args.seconds):
            time.sleep(1)
            samples.append({
                'mc': struct.unpack_from('<Q', frames, 0x10)[0],
                'host': struct.unpack_from('<Q', bridge, 0x108)[0],
                'flags': struct.unpack_from('<I', bridge, 0x104)[0],
            })
        seconds = time.perf_counter()-started
        result = {
            'seconds': round(seconds, 2),
            'minecraft_updates_fps': round((samples[-1]['mc']-before_mc)/seconds, 1),
            'elden_ring_fps': round((samples[-1]['host']-before_host)/seconds, 1),
            'stalled_intervals': sum(a['mc']==b['mc'] for a,b in zip(samples,samples[1:])),
            'resolution': list(struct.unpack_from('<2I', frames, 0x44)),
            'gpu_buffers': struct.unpack_from('<I', frames, 0x54)[0],
            'bridge_flags': samples[-1]['flags'],
        }
        print(json.dumps(result), flush=True)
        (root/args.output).write_text(json.dumps({'result':result,'samples':samples},indent=2))
