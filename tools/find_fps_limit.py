"""Inspect the supported game's FPS-limit instruction without changing it."""
import mmap, re, struct, json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
game=Path(json.loads((root/'installation.json').read_text(encoding='utf-8-sig'))['game_dir'])/'eldenring.exe'
with game.open('rb') as f, mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ) as m:
    pe=struct.unpack_from('<I',m,0x3c)[0]
    sections=struct.unpack_from('<H',m,pe+6)[0]
    table=pe+24+struct.unpack_from('<H',m,pe+20)[0]
    for i in range(sections):
        at=table+i*40
        if m[at:at+8].rstrip(b'\0') != b'.text': continue
        size,rva,rawsize,offset=struct.unpack_from('<4I',m,at+8)
        for match in re.finditer(rb'\xc7..[\x89\x88\x90]\x88\x88\x3c\xeb',m):
            pos=match.start()
            if offset<=pos<offset+rawsize:
                print(hex(rva+pos-offset),m[pos-3:pos+12].hex(' '))
