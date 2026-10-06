"""Exercise native support sampling and Minecraft lift travel without a running game."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/moving-platform-test'
OUT.mkdir(parents=True, exist_ok=True)
java = r'''
import dev.ermc.bridge.MovingPlatformMotion;
public class PlatformTest {
    static void check(boolean value, String message) {if(!value) throw new AssertionError(message);}
    public static void main(String[] args) {
        var motion=new MovingPlatformMotion();
        double feet=10;
        check(!motion.update(true,1,0,10,feet,true,false,1000).moving(),"Standing on static ground does not claim moving terrain");
        for(int i=1;i<=30;i++) {
            double floor=10+i*.1;
            var step=motion.update(true,1,i*.1,floor,feet,true,false,1000+i*50);
            check(step.moving(),"A rising lift must stay attached");
            feet+=step.dy();
            check(Math.abs(feet-MovingPlatformMotion.collisionHeight(floor))<1e-8,"Feet follow the rising floor");
        }
        for(int i=1;i<=30;i++) {
            double floor=13-i*.1;
            var step=motion.update(true,1,3-i*.1,floor,feet,true,false,2500+i*50);
            feet+=step.dy();
            check(Math.abs(feet-MovingPlatformMotion.collisionHeight(floor))<1e-8,"Feet follow the descending floor");
        }
        var stopped=motion.update(true,1,0,10,feet,true,false,5000);
        check(stopped.dy()==0 && !stopped.moving(),"Stop terrain ownership after the lift settles");
        var jumped=motion.update(true,1,.1,10.1,feet+.4,false,false,5100);
        check(jumped.dy()==0 && motion.epoch()==1,"Track the moving floor without carrying the airborne passenger");
        var landed=motion.update(true,1,.2,10.2,10.15,false,false,-.1,5150);
        check(landed.landed() && landed.moving(),"Land on the updated platform position");
        feet=MovingPlatformMotion.collisionHeight(landed.floor());
        var ridingAgain=motion.update(true,1,.3,10.3,feet,true,false,5190);
        check(ridingAgain.dy()>0 && ridingAgain.moving(),"Resume the ride after landing");
        var descendingJump=new MovingPlatformMotion();
        descendingJump.update(true,8,0,10,10,true,false,1000);
        double jumpFeet=10,velocity=.42;boolean didLand=false;
        for(int i=1;i<=25;i++) {
            jumpFeet+=velocity;velocity=(velocity-.08)*.98;
            var jumpStep=descendingJump.update(true,8,-i*.2,10-i*.2,jumpFeet,false,false,velocity,1000+i*50);
            check(descendingJump.epoch()==8,"Keep tracking a descending lift throughout the jump");
            if(jumpStep.landed()) {didLand=true;break;}
            check(jumpStep.dy()==0,"An airborne player follows jump physics, not forced platform travel");
        }
        check(didLand,"Land again on a lift descending at four metres per second");
        // Whole rides at higher speed, including a delayed client tick. The
        // previous fixed half-block gap detached even a stationary passenger.
        var fast=new MovingPlatformMotion();
        double fastFloor=10,fastTravel=0,fastFeet=10;long fastTime=1000;
        fast.update(true,9,0,fastFloor,fastFeet,true,false,fastTime);
        for(int i=1;i<=240;i++) {
            long dt=i==60 ? 200:50;
            double speed=Math.min(i*.25,20);
            double change=(i<=120 ? 1:-1)*speed*dt/1000;
            fastFloor+=change;fastTravel+=change;fastTime+=dt;
            var step=fast.update(true,9,fastTravel,fastFloor,fastFeet,true,false,fastTime);
            check(fast.epoch()==9 && step.moving(),"Keep support at high speed and through a delayed tick");
            fastFeet+=step.dy();
            check(Math.abs(fastFeet-MovingPlatformMotion.collisionHeight(fastFloor))<1e-8,"Finish the entire fast ride without falling");
        }
        var overtaken=new MovingPlatformMotion();
        overtaken.update(true,10,0,10,10,true,false,1000);
        check(overtaken.update(true,10,.6,10.6,10.42,false,false,.333,1050).landed(),
            "A rising platform can catch the passenger while the jump still moves upwards");
        motion.update(true,2,0,10,10,true,false,5200);
        check(motion.update(true,2,.1,10.1,10,true,true,5250).dy()==0,"Flying must not be pulled to the floor");
        motion.update(true,3,0,10,10,true,false,5300);
        check(motion.update(true,4,100,10,10,true,false,5350).dy()==0,"A new support epoch must not replay old travel");
        check(motion.update(true,4,103,13,10,true,false,5400).dy()==0,"Discard discontinuities rather than teleporting");
        motion.update(true,5,0,10,10,true,false,5500);
        check(motion.update(true,5,0,11,11,true,false,5550).dy()==0,"Walking up a static step is not lift motion");
        check(motion.update(false,5,1,12,11,true,false,5600).dy()==0,"No movement without verified native support");
        var boarding=new MovingPlatformMotion();
        var firstLanding=boarding.update(true,12,.1,10.3,10,false,false,-.3,1000);
        check(firstLanding.landed() && firstLanding.moving() && firstLanding.dy()>.29,"Catch a rising platform on the first airborne contact sample");
        check(boarding.update(true,12,.27,10.47,MovingPlatformMotion.collisionHeight(10.3),true,false,1050).moving(),"Continue riding after the initial catch");
        var jumpingAway=new MovingPlatformMotion();
        check(!jumpingAway.update(true,13,0,10,10.1,false,false,.3,1000).landed(),"Do not catch a new upward jump above a floor");
        var walking=new MovingPlatformMotion();
        for(int i=0;i<120;i++) {
            double floor=10+i*.012,feetOnSlope=floor-.09;
            var step=walking.update(true,100+i,0,floor,feetOnSlope,true,false,1000+i*50);
            check(step.dy()==0 && !step.landed() && !step.moving(),"Static slope corners must never grab walking or sprinting players");
        }
        var tinyNoise=new MovingPlatformMotion();
        tinyNoise.update(true,200,0,10.1,10,true,false,1000);
        check(tinyNoise.update(true,200,.00003,10.15,10,true,false,1050).dy()==0,
            "Floating-point support noise must not snap the player's feet on static ground");
        check(!new MovingPlatformMotion().update(true,201,0,10.3,10,false,false,-.3,1000).landed(),
            "An airborne static-floor contact must use Minecraft collision instead of platform snapping");
        System.out.println("PASS: full fast rides, delayed ticks, ascent, descent, settling, jumps, flight and static stairs");
    }
}
'''
(OUT / 'PlatformTest.java').write_text(java, encoding='utf-8')
jdk = next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent
motion = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/MovingPlatformMotion.java'
subprocess.run([str(jdk / 'javac.exe'), '-d', str(OUT), str(motion), str(OUT / 'PlatformTest.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'PlatformTest'], check=True)

source = (ROOT / 'bridge-base/elden-ring/er-bridge/src/game.cpp').read_text(encoding='utf-8')
start = source.index('static void update_support(const ErmcControl& c, bool active, void* ignore) {')
opening = source.index('{', start)
depth, end = 1, opening + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
native = r'''
#include <cassert>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include "support_surface.h"
#include <cstring>
#include <initializer_list>
using std::fabsf;
constexpr uint32_t ERMC_CTRL_GROUNDED=256,ERMC_CTRL_FLYING=512,kTerrainRayFilter=0x5d;
struct ErmcControl {uint32_t flags=256;float hunterPos[3]={0,10,0};uint32_t supportEpoch=0;float supportTravelY=0;};
struct ErmcRayHit {uint32_t hit=0;float pos[3]={};};
bool g_supportValid=false;uint32_t g_supportEpoch=0;float g_supportTravelY=0,g_supportPos[3]={},g_supportRayFloor=0;uint64_t g_supportAtMs=0;
uint64_t clockMs=1000;float floorY=10,slope=0,ceilingY=0;bool haveFloor=true,stairs=false,edge=false,railing=false;
uint64_t now_ms(){return clockMs;}uint32_t GetTickCount(){return (uint32_t)clockMs;}
void log(const char*,...) {}
bool raycast(const float* s,const float* e,uint32_t,void*,ErmcRayHit* out) {
    float y=floorY+(stairs && s[0]>1 ? 1:0)+slope*s[0]+(railing && s[0]>.1f ? .4f:0);
    if(ceilingY!=0 && ceilingY>y && s[1]>=ceilingY && e[1]<=ceilingY)y=ceilingY;
    out->hit=haveFloor && !(edge && s[0]>.1f) && s[1]>=y && e[1]<=y;
    out->pos[0]=s[0];out->pos[1]=y;out->pos[2]=s[2];return out->hit;
}
'''
native += source[start:end] + '\n'
native += r'''
int main() {
    ErmcControl c;update_support(c,true,nullptr);
    assert(g_supportValid && g_supportTravelY==0);
    // Replay the static floor/ceiling alias recorded in the user's run.
    floorY=424.046f;ceilingY=427.495f;c.hunterPos[1]=floorY;
    g_supportValid=false;clockMs+=50;update_support(c,true,nullptr);
    for(uint64_t dt : {168u,220u,248u,296u,648u}) {
        c.supportEpoch=g_supportEpoch;c.supportTravelY=g_supportTravelY;
        clockMs+=dt;update_support(c,true,nullptr);
        assert(!g_supportValid || fabsf(g_supportTravelY)<.001f);
        if(!g_supportValid){clockMs+=50;update_support(c,true,nullptr);}
    }
    assert(g_supportValid && fabsf(g_supportRayFloor-floorY)<.001f);
    ceilingY=0;floorY=10;c.hunterPos[1]=10;g_supportValid=false;
    clockMs+=50;update_support(c,true,nullptr);
    c.supportEpoch=g_supportEpoch;
    for(int i=1;i<=30;i++) {
        clockMs+=50;floorY=10+i*.1f;update_support(c,true,nullptr);
        assert(g_supportValid && fabsf(g_supportTravelY-i*.1f)<1e-4);
        c.hunterPos[1]=floorY;c.supportTravelY=g_supportTravelY;
    }
    for(int i=1;i<=30;i++) {
        clockMs+=50;floorY=13-i*.1f;update_support(c,true,nullptr);
        assert(g_supportValid && fabsf(g_supportTravelY-(3-i*.1f))<1e-4);
        c.hunterPos[1]=floorY;c.supportTravelY=g_supportTravelY;
    }
    // A transient airborne flag can accompany a frame whose feet and support
    // acknowledgement are both older than the moving floor. Validate their
    // relative height, rather than comparing old feet with the newest floor.
    c.flags=0;clockMs+=150;floorY+=.6f;update_support(c,true,nullptr);
    assert(g_supportValid);
    c.flags=256;c.hunterPos[1]=floorY;c.supportTravelY=g_supportTravelY;
    for(int i=1;i<=240;i++) {
        uint64_t dt=i==60 ? 200:50;
        float speed=i*.25f<20 ? i*.25f:20;
        float dy=(i<=120 ? 1:-1)*speed*dt/1000;
        clockMs+=dt;floorY+=dy;update_support(c,true,nullptr);
        if(!g_supportValid)fprintf(stderr,"Lost at %d: floor=%f tracked=%f feet=%f dy=%f\n",i,floorY,g_supportRayFloor,c.hunterPos[1],dy);
        assert(g_supportValid);
        // START_CLIENT_TICK has already moved BOTH render endpoints; the
        // acknowledgement must contain all that applied travel, even at pt=0.
        c.hunterPos[1]=floorY;c.supportTravelY=g_supportTravelY;
    }
    floorY=10;c.hunterPos[1]=10;c.supportEpoch=0;g_supportValid=false;
    clockMs+=50;update_support(c,true,nullptr);assert(g_supportValid);
    // Walking to a higher static tread must not become cumulative platform travel.
    stairs=true;c.hunterPos[0]=3;c.hunterPos[1]=11;clockMs+=50;update_support(c,true,nullptr);
    assert(!g_supportValid);
    clockMs+=50;update_support(c,true,nullptr);assert(g_supportValid && g_supportTravelY==0);
    uint32_t previous=g_supportEpoch;
    c.flags=0;c.hunterPos[1]=12;clockMs+=50;floorY=10.1f;update_support(c,true,nullptr);
    assert(g_supportValid && g_supportEpoch==previous && fabsf(g_supportTravelY-.1f)<1e-4);
    c.flags=ERMC_CTRL_FLYING;clockMs+=50;update_support(c,true,nullptr);
    assert(!g_supportValid && g_supportEpoch!=previous);
    c.flags=256;c.hunterPos[1]=11.1f;c.supportEpoch=0;clockMs+=50;update_support(c,true,nullptr);
    assert(g_supportValid && g_supportTravelY==0);
    haveFloor=false;clockMs+=50;update_support(c,true,nullptr);assert(!g_supportValid);
    // Replay the values recorded at the user's actual mid-ride failure.
    haveFloor=true;stairs=false;g_supportValid=true;g_supportEpoch=77;
    g_supportTravelY=8.236f;g_supportRayFloor=g_supportPos[1]=329.838f;g_supportAtMs=clockMs;
    c.flags=0;c.supportEpoch=77;c.supportTravelY=7.834f;c.hunterPos[1]=329.452f;
    floorY=329.973f;clockMs+=29;update_support(c,true,nullptr);
    assert(g_supportValid && g_supportEpoch==77);
    // An actual fall below that acknowledged floor still loses contact.
    c.supportTravelY=g_supportTravelY;c.hunterPos[1]=328.9f;clockMs+=20;update_support(c,true,nullptr);
    assert(!g_supportValid);
    // The wheel lift is sloped: its corners differ by about .1 m. Track
    // temporal motion at the centre, not that permanent corner-height offset.
    floorY=10;slope=.3f;c.hunterPos[0]=0;c.hunterPos[1]=10.125f;
    c.flags=256;c.supportEpoch=0;c.supportTravelY=0;g_supportValid=false;clockMs+=50;
    update_support(c,true,nullptr);assert(g_supportValid);
    c.supportEpoch=g_supportEpoch;
    for(int i=1;i<=80;i++) {
        clockMs+=50;floorY+=.165f;update_support(c,true,nullptr);
        assert(g_supportValid && fabsf(g_supportTravelY-i*.165f)<.001f);
        c.hunterPos[1]=g_supportPos[1];c.supportTravelY=g_supportTravelY;
    }
    float travel=g_supportTravelY;
    for(int i=0;i<20;i++){clockMs+=50;update_support(c,true,nullptr);assert(g_supportValid && fabsf(g_supportTravelY-travel)<.001f);}
    slope=1.0f;clockMs+=50;update_support(c,true,nullptr);assert(!g_supportValid);
    slope=0;edge=true;c.hunterPos[1]=floorY;c.supportEpoch=0;clockMs+=50;
    update_support(c,true,nullptr);assert(g_supportValid);
    edge=false;railing=true;clockMs+=50;update_support(c,true,nullptr);assert(g_supportValid);
    // A falling player can make first contact; requiring onGround would defer
    // the real floor until after the platform had already risen through them.
    railing=false;g_supportValid=false;c.flags=0;c.hunterPos[1]=floorY+.1f;
    clockMs+=50;update_support(c,true,nullptr);assert(g_supportValid);
    puts("PASS: native floor travel, delayed airborne pose, recorded mid-ride failure, jumps, flight and lost support");
}
'''
(OUT / 'native.cpp').write_text(native, encoding='utf-8')
cxx = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
exe = OUT / 'native.exe'
subprocess.run([str(cxx), '-std=c++17', '-static', '-I', str(ROOT/'bridge-base/elden-ring/er-bridge/include'), str(OUT / 'native.cpp'), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
