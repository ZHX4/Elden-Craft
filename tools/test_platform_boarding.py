"""Exercise the actual native nearby-platform publisher before passenger contact."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / 'build/platform-boarding-test'
out.mkdir(parents=True, exist_ok=True)
source = (root / 'bridge-base/elden-ring/er-bridge/src/game.cpp').read_text(encoding='utf-8')
start = source.index('struct PlatformHistory {')
end = source.index('static volatile LONG g_dbgRayState', start)
cpp = r'''
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <cstring>
#include "bridge_protocol.h"
constexpr uint32_t kTerrainRayFilter=0x5d;
struct ErmcControlMock {float hunterPos[3]={0,10,0};uint32_t flags=ERMC_CTRL_FLYING;};
struct ErmcRayHitMock {int hit;float pos[3];float normal[3]={0,1,0};};
#define ErmcControl ErmcControlMock
#define ErmcRayHit ErmcRayHitMock
uint64_t clockMs=1000;
float floorY=10,slope=0,ceilingY=0;
bool present=true,wall=false,edge=false;
ErmcPlatformTable table={};
uint64_t now_ms(){return clockMs;}
ErmcPlatformTable* shm_platforms(){return &table;}
bool raycast(const float* a,const float* b,uint32_t,void*,ErmcRayHit* hit){
    bool solid=false;
    float floor=floorY+slope*a[0];
    if(wall && a[1]>floorY+.3f && a[1]<floorY+3) solid=true;
    if(present && !(edge && a[0]>.1f) && a[0]==b[0] && a[2]==b[2])
        solid |= (a[1]>=floor && b[1]<=floor)||(b[1]>=floor && a[1]<=floor);
    if(ceilingY && a[0]==b[0] && a[2]==b[2]
        && ((a[1]>=ceilingY && b[1]<=ceilingY)||(b[1]>=ceilingY && a[1]<=ceilingY))) {
        if(!solid || a[1]>=ceilingY) floor=ceilingY;
        solid=true;
    }
    hit->hit=solid;hit->pos[0]=a[0];hit->pos[1]=floor;hit->pos[2]=a[2];return solid;
}
'''
cpp += source[start:end]
cpp += r'''
int main(){
    ErmcControl c;
    update_platform_cells(c,true,14,nullptr);assert(table.count==0);
    for(int i=0;i<10;i++){clockMs+=50;update_platform_cells(c,true,14,nullptr);assert(table.count==0);}
    // A sloping wheel rising at 3.3 m/s gets a floor before support is acquired.
    slope=.3f;floorY+=.165f;clockMs+=50;update_platform_cells(c,true,14,nullptr);
    floorY+=.165f;clockMs+=50;update_platform_cells(c,true,14,nullptr);
    assert(table.count>0);
    for(unsigned i=0;i<table.count;i++)assert(table.cells[i].flags&1);
    wall=true;floorY+=.165f;clockMs+=50;update_platform_cells(c,true,14,nullptr);
    assert(table.count==0); // A wall blocks cleanup even in a cell that moved earlier.
    wall=false;present=false;clockMs+=50;update_platform_cells(c,true,14,nullptr);
    assert(table.count>0);
    for(unsigned i=0;i<table.count;i++)assert(!(table.cells[i].flags&1));
    clockMs+=50;update_platform_cells(c,true,14,nullptr);assert(table.count>0);
    // A reader may have missed one publication: removal persists, not a one-frame event.
    clockMs+=50;update_platform_cells(c,true,15,nullptr);assert(table.count==0);
    present=true;slope=0;floorY=10;edge=true;
    clockMs+=50;update_platform_cells(c,true,15,nullptr);
    floorY+=.165f;clockMs+=50;update_platform_cells(c,true,15,nullptr);
    for(unsigned i=0;i<table.count;i++)if(table.cells[i].flags&1)assert(table.cells[i].x+.3125f<=.10001f);
    clockMs+=50;update_platform_cells(c,false,15,nullptr);assert(table.count==0);
    // Static collision belongs to the independent contact sampler, not the lift
    // mailbox; it must never publish flat clearance patches across stair treads.
    c.flags=0;edge=false;present=true;floorY=10;
    clockMs+=50;update_platform_cells(c,true,16,nullptr);assert(table.count==0);
    ceilingY=13;clockMs+=50;update_platform_cells(c,true,17,nullptr);assert(table.count==0);
    ceilingY=0;
    // A closed door cannot be erased; opening it clears the next publication.
    wall=true;clockMs+=50;update_platform_cells(c,true,17,nullptr);assert(table.count==0);
    wall=false;clockMs+=50;update_platform_cells(c,true,17,nullptr);assert(table.count==0);
    // The floor remains in the swept query even after a fast descent crosses it.
    c.hunterPos[1]=18;clockMs+=50;update_platform_cells(c,true,17,nullptr);
    c.hunterPos[1]=8;clockMs+=50;update_platform_cells(c,true,17,nullptr);assert(table.count==0);
    present=false;clockMs+=50;update_platform_cells(c,true,17,nullptr);assert(table.count==0);
    // A shifted vertical query can reveal a static overhead floor. Repeating
    // the old ray window must still find the old floor, rejecting false travel.
    present=true;ceilingY=11;c.hunterPos[1]=6.6f;
    clockMs+=50;update_platform_cells(c,true,18,nullptr);assert(table.count==0);
    c.hunterPos[1]=7.2f;clockMs+=50;update_platform_cells(c,true,18,nullptr);assert(table.count==0);
    puts("PASS: moving platforms, no static-floor carving, closed/open doors, missing ground and zone resets");
}
'''
(out / 'native.cpp').write_text(cpp, encoding='utf-8')
cxx = next((root / '.tools/compiler').glob('*/bin/clang++.exe'))
exe = out / 'native.exe'
subprocess.run([str(cxx), '-std=c++17', '-static', '-I', str(root / 'bridge-base/elden-ring/er-bridge/include'), str(out / 'native.cpp'), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
