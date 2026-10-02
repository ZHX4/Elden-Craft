"""Measure current full-resolution bridge, temporarily bypass transfer, then restore it."""
from pathlib import Path
import mmap, struct, time, statistics, json
root = Path(__file__).resolve().parents[1]
rows = []
with (root/'runtime/bridge.shm').open('r+b') as f, mmap.mmap(f.fileno(),0) as m:
    try:
        for mode in ('on','off','on'):
            (root/'runtime/mc_cmd.txt').write_text('pt '+mode, encoding='ascii')
            time.sleep(2)
            values=[]
            before=struct.unpack_from('<Q',m,0x108)[0]; at=time.perf_counter()
            for i in range(8):
                time.sleep(1)
                now=time.perf_counter(); frame=struct.unpack_from('<Q',m,0x108)[0]
                values.append((frame-before)/(now-at)); before=frame; at=now
            row={'transfer':mode,'median_fps':round(statistics.median(values),2),'samples':values}
            rows.append(row); print(json.dumps(row),flush=True)
    finally:
        (root/'runtime/mc_cmd.txt').write_text('pt on',encoding='ascii')
(root/'runtime/pipeline-baseline.json').write_text(json.dumps(rows,indent=2))
