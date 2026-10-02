"""Compare transfer resolutions in the current game scene without moving the player."""
from pathlib import Path
import json
import mmap
import statistics
import struct
import time

root = Path(__file__).resolve().parents[1]
results = []
with (root / 'runtime/bridge.shm').open('r+b') as stream:
    with mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as shared:
        for scale in (0.5, 1.0, 0.5):
            (root / 'runtime/mc_cmd.txt').write_text(f'pt scale {scale}\n', encoding='ascii')
            time.sleep(3)
            samples = []
            stages = []
            valid = []
            previous = struct.unpack_from('<Q', shared, 0x108)[0]
            stamp = time.perf_counter()
            for _ in range(15):
                time.sleep(1)
                now = time.perf_counter()
                frame = struct.unpack_from('<Q', shared, 0x108)[0]
                samples.append((frame - previous) / (now - stamp))
                stages.append(struct.unpack_from('<I', shared, 0x17c)[0])
                valid.append(bool(struct.unpack_from('<I', shared, 0x104)[0] & 64))
                previous, stamp = frame, now
            row = dict(scale=scale, median_fps=round(statistics.median(samples), 2),
                       mean_fps=round(statistics.mean(samples), 2), samples=samples,
                       stages=stages, compositing=valid)
            results.append(row)
            print(json.dumps(row), flush=True)
(root / 'runtime/performance-comparison.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
