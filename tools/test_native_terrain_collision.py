"""Cast production native contacts, then exercise Minecraft's actual movement solver."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/native-terrain-collision-test'
OUT.mkdir(parents=True, exist_ok=True)
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
cxx = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
cpp = r'''
#include "terrain_contacts.h"
#include <vector>
#include <cstdio>
#include <cassert>
#include <algorithm>
struct Box { float lo[3], hi[3]; };
std::vector<Box> scene;
bool diagonal = false, ramp = false;
// Entry faces only, as for one-sided native triangle meshes. Unlike an AABB
// overlap test this deliberately DOES NOT report a hit from inside a solid.
bool cast(const float* s, const float* e, float* point) {
    float nearest = 2;
    for (auto& box : scene) for (int axis = 0; axis < 3; axis++) {
        float d = e[axis] - s[axis]; if (std::fabs(d) < 1e-8f) continue;
        float plane = d > 0 ? box.lo[axis] : box.hi[axis];
        float t = (plane - s[axis]) / d;
        if (t < .00001f || t > 1 || t >= nearest) continue;
        bool inside = true;
        for (int cross = 0; cross < 3; cross++) if (cross != axis) {
            float p = s[cross] + (e[cross] - s[cross]) * t;
            inside &= p > box.lo[cross] + 1e-6f && p < box.hi[cross] - 1e-6f;
        }
        if (inside) nearest = t;
    }
    if (diagonal) {
        float start = s[0] - s[2], delta = (e[0]-s[0]) - (e[2]-s[2]);
        if (delta > 1e-8f && start < 1) {
            float t = (1-start)/delta;
            if (t > .00001f && t <= 1 && t < nearest && s[1]+(e[1]-s[1])*t > 0) nearest=t;
        }
    }
    if (ramp) {
        float start = s[1] - .2f*s[0] - .1f*s[2];
        float delta = e[1]-s[1] - .2f*(e[0]-s[0]) - .1f*(e[2]-s[2]);
        if (start >= 0 && delta < -1e-8f) {float t=-start/delta;if(t>.00001f && t<=1 && t<nearest)nearest=t;}
    }
    if (nearest > 1) return false;
    for (int a = 0; a < 3; a++) point[a] = s[a] + (e[a] - s[a]) * nearest;
    return true;
}
void box(float x0,float y0,float z0,float x1,float y1,float z1) {
    scene.push_back({{x0,y0,z0},{x1,y1,z1}});
}
void print(const char* name, const TerrainContactSampler& sampler) {
    printf("%s %u\n",name,sampler.table.count);
    for(unsigned i=0;i<sampler.table.count;i++) {
        auto& c=sampler.table.contacts[i];
        for(int a=0;a<3;a++)assert(c.min[a]<c.max[a]);
        printf("%.9g %.9g %.9g %.9g %.9g %.9g %u\n",c.min[0],c.min[1],c.min[2],c.max[0],c.max[1],c.max[2],c.kind);
    }
}
void emit(const char* name, float x,float y,float z,float previousY,float vx=0,float vz=0,float vy=0) {
    TerrainContactSampler sampler; float feet[3]={x,y,z}; sampler.begin(feet,previousY,std::hypot(vx,vz)*.05f,14,vx,vz,NAN,NAN,vy);
    assert(sampler.table.previousFeetY==previousY);
    assert(!sampler.done() && sampler.table.count==0);
    // Urgent contacts must precede the large refinement batch.
    while(sampler.cursor<TerrainContactSampler::GUARD_RAYS)sampler.step(cast);
    char urgent[64];sprintf(urgent,"guard_%s",name);print(urgent,sampler);
    while (!sampler.done()) { for(int i=0;i<16 && !sampler.done();i++)sampler.step(cast); }
    assert(sampler.table.count <= ERMC_MAX_CONTACTS);
    print(name,sampler);
}
int main() {
    static_assert(TerrainContactSampler::RAYS <= ERMC_MAX_CONTACTS);
    scene.clear(); box(-10,-100,-10,10,0,10); emit("floor",0,0,0,0);
    box(1,-100,-10,1.2f,8,10); emit("wall",0,0,0,0);
    scene.clear(); box(-10,-100,-10,10,0,10);
    box(-10,0,1,10,8,1.02f); emit("thin",0,0,0,0);
    scene.clear(); box(-10,-100,-10,10,0,10);
    box(-10,2.05f,-10,10,2.2f,10); emit("ceiling",0,0,0,0);
    scene.clear(); box(-10,-100,-10,10,0,10);
    box(-3,0,1,-.5f,8,1.2f); box(.5f,0,1,3,8,1.2f); box(-3,2.2f,1,3,8,1.2f);
    emit("open",0,0,0,0); box(-.5f,0,1,.5f,2.2f,1.02f); emit("closed",0,0,0,0);
    scene.pop_back(); emit("reopened",0,0,0,0);
    scene.clear(); box(-10,-100,-10,10,0,10);
    for(int i=0;i<8;i++)box(.5f+i*.5f,-100,-3,1.f+i*.5f,(i+1)*.25f,3);
    emit("stairs",0,0,0,0);
    for(int i=0;i<7;i++) {
        char name[32];sprintf(name,"stair%d",i);
        emit(name,.65f+i*.5f,(i+1)*.25f,0,(i+1)*.25f);
    }
    scene.clear();box(-10,-100,-10,10,0,10);box(.5f,-100,-3,3,.6f,3);emit("maxstep",0,0,0,0);
    scene.clear(); box(-10,-100,-10,0,0,10); emit("ledge",0,0,0,0);
    scene.clear(); box(-10,0.9f,-10,10,1.f,10); emit("bridge",0,1,0,1);
    scene.clear(); box(-10,-100,-10,10,10,10); emit("landing",0,8,0,18);
    scene.clear(); emit("void",0,0,0,0);
    box(-10,-100,-10,10,0,10); diagonal=true;
    for(int i=0;i<50;i++) {
        char name[32];sprintf(name,"diagonal%d",i);
        emit(name,.4f+i*.04f,0,i*.04f,0);
    }
    diagonal=false;scene.clear();ramp=true;emit("ramp",0,0,0,0);ramp=false;
    scene.clear();box(-200,-100,-200,200,0,200);emit("fastfloor",0,3,0,3,120,0);
    box(1,0,-200,1.1f,20,200);emit("flyup",0,0,0,0,40,0,80);emit("flydown",0,8,0,8,40,0,-80);
    scene.clear();box(-200,4.05f,-200,200,4.2f,200);emit("fastceiling",0,0,0,0,0,0,80);
    scene.clear();box(-10,-100,-10,10,0,10);box(1,1.86f,-10,1.1f,1.96f,10);emit("overhang",0,.2f,0,.2f);
}
'''
(OUT / 'native.cpp').write_text(cpp, encoding='utf-8')
exe = OUT / 'native.exe'
subprocess.run([str(cxx), '-std=c++17', '-O2', '-static', '-I', str(ER/'include'),
                str(OUT/'native.cpp'), '-o', str(exe)], check=True)
with (OUT / 'contacts.txt').open('w', encoding='utf-8') as stream:
    subprocess.run([str(exe)], stdout=stream, check=True)

source = r'''
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import dev.ermc.bridge.TerrainContactGeometry;
import dev.ermc.bridge.TerrainAirShape;
import dev.ermc.bridge.TerrainWallGeometry;

public class NativeCollisionTest {
    static final Map<String,List<VoxelShape>> scenes=new HashMap<>();
    static final Map<String,List<AABB>> clearance=new HashMap<>(), walls=new HashMap<>();
    static final Map<List<VoxelShape>,List<TerrainWallGeometry.Plane>> planes=new IdentityHashMap<>();
    static Method collide, steps;
    static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static Vec3 move(Vec3 delta,AABB body,List<VoxelShape> shapes)throws Exception {
        var measured=planes.getOrDefault(shapes,List.of());
        Vec3 clipped=TerrainWallGeometry.slide(delta,body,measured);
        AABB area=body.expandTowards(clipped);
        List<VoxelShape> remaining=new ArrayList<>();
        for(VoxelShape shape:shapes)if(measured.stream().noneMatch(p->p.supportsMovement(body,area)&&p.contains(shape.bounds())))remaining.add(shape);
        return (Vec3)collide.invoke(null,clipped,body,remaining);
    }
    static AABB body(double x,double y,double z){return new AABB(x-.3,y,z-.3,x+.3,y+1.8,z+.3);}
    static Vec3 walk(Vec3 delta,AABB body,List<VoxelShape> shapes)throws Exception {
        Vec3 result=move(delta,body,shapes);
        if(result.x==delta.x && result.z==delta.z)return result;
        float[] heights=(float[])steps.invoke(null,body,shapes,.6f,(float)result.y);
        for(float height:heights){
            Vec3 step=move(new Vec3(delta.x,height,delta.z),body,shapes);
            if(step.horizontalDistanceSqr()>result.horizontalDistanceSqr())return step;
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try(Scanner in=new Scanner(Path.of(args[0]))){in.useLocale(Locale.ROOT);
            while(in.hasNext()){
                String name=in.next();int count=in.nextInt();List<VoxelShape> shapes=new ArrayList<>();List<AABB> air=new ArrayList<>(), wallBoxes=new ArrayList<>();
                for(int i=0;i<count;i++){
                    AABB box=new AABB(in.nextDouble(),in.nextDouble(),in.nextDouble(),in.nextDouble(),in.nextDouble(),in.nextDouble());
                    int kind=in.nextInt();if(kind==4)air.add(box);else shapes.add(Shapes.create(box));if(kind==2)wallBoxes.add(box);
                }scenes.put(name,shapes);clearance.put(name,TerrainContactGeometry.merge(air));walls.put(name,TerrainContactGeometry.merge(wallBoxes));
                double x=0,z=0;
                if(name.startsWith("diagonal")){z=Integer.parseInt(name.substring(8))*.04;x=.4+z;}
                planes.put(shapes,TerrainWallGeometry.fit(wallBoxes,new Vec3(x,0,z),1));
            }
        }
        collide=Entity.class.getDeclaredMethod("collideWithShapes",Vec3.class,AABB.class,List.class);collide.setAccessible(true);
        steps=Entity.class.getDeclaredMethod("collectCandidateStepUpHeights",AABB.class,List.class,float.class,float.class);steps.setAccessible(true);
        for(double x:new double[]{-.55,-.01,0,.01,.55})for(double z:new double[]{-.55,0,.55}) {
            Vec3 fall=move(new Vec3(0,-20,0),body(x,3,z),scenes.get("floor"));
            require(Math.abs(fall.y+3)<1e-5,"no floor holes at footprints or sample seams");
        }
        require(move(new Vec3(3,-.1,.2),body(0,0,0),scenes.get("guard_wall")).x<=.70001,
            "urgent wall protection must precede dense floor/ceiling refinement");
        require(move(new Vec3(0,-15,0),body(0,18,0),scenes.get("guard_landing")).y>=-8.0001,
            "urgent swept floor must protect a landing before the dense batch completes");
        for(double x:new double[]{6,12})
            require(move(new Vec3(0,-5,0),body(x,3,0),scenes.get("guard_fastfloor")).y==-3,
                "a fast flight landing has support at the next two predicted simulation positions");
        require(move(new Vec3(2,4,.2),body(0,0,0),scenes.get("guard_flyup")).x<=.70001,
            "rising flight cannot escape wall coverage above the old contact band");
        require(move(new Vec3(2,-4,.2),body(0,8,0),scenes.get("guard_flydown")).x<=.70001,
            "descending flight cannot escape wall coverage below the old contact band");
        require(move(new Vec3(0,6,0),body(0,0,0),scenes.get("guard_fastceiling")).y<=2.25001,
            "upward flight must collide with a ceiling beyond the standing probe reach");
        Vec3 wall=move(new Vec3(3,-.1,.2),body(0,0,0),scenes.get("wall"));
        require(Math.abs(wall.x-.7)<1e-5 && Math.abs(wall.z-.2)<1e-5,"wall stops crossing but permits sliding");
        AABB overlapping=body(.8,0,0);
        Vec3 along=move(new Vec3(.4,-.08,.3),overlapping,scenes.get("wall"));
        require(along.x==0 && along.z==.3 && along.y==0,"partially overlapping a thin wall must block entry and permit uninterrupted sliding: "+along);
        require(move(new Vec3(-.4,0,.3),overlapping,scenes.get("wall")).x==-.4,"continuous collision must allow moving away from a wall");
        Vec3 behind=move(new Vec3(.2,0,.1),body(1.6,0,0),scenes.get("wall"));
        require(behind.x==.2 && behind.z==.1,"an old wall behind the body cannot restrict free movement: "+behind);
        Vec3 underBeam=move(new Vec3(2,0,0),body(0,0,0),scenes.get("guard_overhang"));
        require(underBeam.x==2,"guard samples cannot extend a raised surface down into a clear walking path: "+underBeam);
        for(int axis:new int[]{0,2})for(int side:new int[]{-1,1}) {
            var plane=new TerrainWallGeometry.Plane(axis,0,1,side,-2,2,0,3);
            double centre=1-side*1.5;
            AABB clearBody=axis==0 ? body(centre,0,0) : body(0,0,centre);
            for(double vx:new double[]{-.2,0,.2})for(double vz:new double[]{-.2,0,.2}) {
                Vec3 wanted=new Vec3(vx,0,vz),actual=TerrainWallGeometry.slide(wanted,clearBody,List.of(plane));
                require(actual.equals(wanted),"old contact on the other side must preserve every free direction: "+axis+" "+side+" "+actual);
            }
        }
        for(double x:new double[]{-.2,-.1,0,.1,.2})for(double z:new double[]{-.2,0,.2})
            require(move(new Vec3(0,-3,0),body(x,0,z),scenes.get("guard_floor")).y==0,"urgent floor samples must contain no footprint gaps");
        Vec3 thin=move(new Vec3(0,0,3),body(0,0,0),scenes.get("thin"));
        require(Math.abs(thin.z-.7)<1e-5,"thin one-sided wall stops fast movement");
        Vec3 ceiling=move(new Vec3(0,2,0),body(0,0,0),scenes.get("ceiling"));
        require(Math.abs(ceiling.y-.25)<1e-5,"ceiling collision is independent of the floor");
        for(String name:new String[]{"open","reopened"}) {
            Vec3 passage=move(new Vec3(0,0,2),body(0,0,0),scenes.get(name));
            require(Math.abs(passage.z-2)<1e-5,"open doorway must not retain a wall or connect floor to lintel");
        }
        Vec3 door=move(new Vec3(0,0,2),body(0,0,0),scenes.get("closed"));
        require(Math.abs(door.z-.7)<1e-5,"closed door remains solid");
        Vec3 climbed=walk(new Vec3(.4,-.08,0),body(0,0,0),scenes.get("stairs"));
        require(climbed.x>.39 && climbed.y>.24 && climbed.y<.26,"first tread must step up instead of falling through or becoming a wall: "+climbed);
        for(int i=0;i<7;i++) {
            double x=.65+i*.5,y=(i+1)*.25;
            List<VoxelShape> shapes=scenes.get("stair"+i);
            Vec3 stand=move(new Vec3(0,-3,0),body(x,y,0),shapes);
            require(Math.abs(stand.y)<1e-5,"every stair tread has support");
            Vec3 up=walk(new Vec3(.5,-.08,0),body(x,y,0),shapes);
            require(up.x>.499 && up.y>=.249 && up.y<=.501,"consecutive treads must climb: "+i+" "+up);
        }
        Vec3 maximum=walk(new Vec3(.4,-.08,0),body(0,0,0),scenes.get("maxstep"));
        require(maximum.x>.399 && maximum.y>=.599 && maximum.y<.601,
            "wall strips must not round a climbable .6m tread above Minecraft's step limit: "+maximum);
        Vec3 edge=move(new Vec3(0,-2,0),body(.05,0,0),scenes.get("ledge"));
        require(Math.abs(edge.y)<1e-5,"partial footprint at a ledge remains supported");
        Vec3 outside=move(new Vec3(0,-2,0),body(.6,0,0),scenes.get("ledge"));
        require(outside.y==-2,"no artificial floor beyond the actual ledge");
        Vec3 below=move(new Vec3(.2,0,0),body(0,-1,0),scenes.get("bridge"));
        require(below.x==.2,"bridge contacts must not fill the space underneath");
        Vec3 land=move(new Vec3(0,-15,0),body(0,18,0),scenes.get("landing"));
        require(Math.abs(land.y+8)<1e-4,"swept native sampling retains a floor crossed before its publication");
        for(int i=0;i<50;i++) {
            double z=i*.04, x=.4+z;
            Vec3 sliding=move(new Vec3(.21,-.08,.2),body(x,0,z),scenes.get("diagonal"+i));
            require(x+sliding.x-z-sliding.z <= .4001,
                "oblique wall must prevent penetration while sliding: x="+x+" motion="+sliding);
            require(sliding.x > .18 && sliding.z > .18,
                "oblique wall must retain tangential walking speed at every grid phase: "+i+" "+sliding);
            Vec3 swimming=move(new Vec3(.21,0,.2),new AABB(x-.3,0,z-.3,x+.3,.6,z+.3),scenes.get("diagonal"+i));
            require(swimming.x>.18 && swimming.z>.18 && x+swimming.x-z-swimming.z<=.4001,
                "swimming/crawling pose keeps wall protection and tangential speed: "+i+" "+swimming);
        }
        require(scenes.get("void").isEmpty(),"missing native surfaces do not fabricate collision");
        require(clearance.get("floor").size()<24,"flat terrain clearance must be merged before movement queries");
        for(String name:new String[]{"floor","open","reopened"}) {
            VoxelShape original=Shapes.block();
            VoxelShape filtered=TerrainContactGeometry.removeAir(original,clearance.get(name),0,0,0);
            List<VoxelShape> hybrid=new ArrayList<>(scenes.get(name));hybrid.add(filtered);
            Vec3 passage=move(new Vec3(.2,-.08,.2),body(0,0,0),hybrid);
            require(passage.x==.2 && passage.z==.2 && passage.y==0,"verified air opens obsolete cubes and retains floor: "+name+" "+passage);
        }
        VoxelShape remote=TerrainContactGeometry.removeAir(Shapes.block(),clearance.get("floor"),2,-1,0).move(2,-1,0);
        Vec3 remoteFall=move(new Vec3(0,-4,0),body(2.5,1,0),List.of(remote));
        require(remoteFall.y==-1,"unsampled ground outside the ray footprints must remain solid");
        VoxelShape cachedWall=TerrainContactGeometry.removeAir(Shapes.block(),clearance.get("wall"),1,0,0).move(1,0,0);
        require(move(new Vec3(3,0,0),body(0,0,0),List.of(cachedWall)).x<=.70001,"measured wall face cannot be carved by free space");
        List<AABB> separated=TerrainContactGeometry.merge(List.of(new AABB(0,0,0,.25,1,1),new AABB(.5,0,0,.75,1,1)));
        require(separated.size()==2,"compression must never bridge unmeasured gaps");
        Random random=new Random(84);
        for(int example=0;example<40;example++) {
            List<AABB> boxes=new ArrayList<>();List<VoxelShape> old=new ArrayList<>();
            for(int i=0;i<12;i++) {
                double x=random.nextInt(8)/8.,y=random.nextInt(8)/8.,z=random.nextInt(8)/8.;
                AABB box=new AABB(x,y,z,Math.min(1,x+.25),Math.min(1,y+.25),Math.min(1,z+.25));
                boxes.add(box);old.add(Shapes.create(box));
            }
            VoxelShape reference=Shapes.or(Shapes.empty(),old.toArray(VoxelShape[]::new));
            require(!Shapes.joinIsNotEmpty(reference,TerrainAirShape.build(boxes),BooleanOp.NOT_SAME),
                "fast air union must preserve every occupied/empty cell, including overlap and enclosed gaps");
        }
        TerrainAirShape.build(List.of(new AABB(-0.,0,0,.25,.25,.25),new AABB(.1,.1,.1,.5,.5,.5)));
        for(int warm=0;warm<100;warm++)TerrainContactGeometry.removeAir(Shapes.block(),clearance.get("wall"),0,0,0);
        long started=System.nanoTime();
        for(int frame=0;frame<200;frame++)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=3;y++)
            TerrainContactGeometry.removeAir(Shapes.block(),clearance.get("wall"),x,y,z);
        System.out.println("Clearance preparation for 45 full blocks: "+((System.nanoTime()-started)/200/1e6)+" ms; merged air boxes: "+clearance.get("wall").size());
        AABB localBounds=new AABB(-.8125,-1,-.8125,.8125,2.75,.8125);
        List<AABB> localRamp=new ArrayList<>();
        for(AABB box:clearance.get("ramp"))if(box.intersects(localBounds))localRamp.add(box.intersect(localBounds));
        localRamp=TerrainContactGeometry.merge(localRamp);
        started=System.nanoTime();
        for(int frame=0;frame<30;frame++)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=3;y++)
            TerrainContactGeometry.removeAir(Shapes.block(),localRamp,x,y,z);
        System.out.println("Slope clearance preparation for 45 full blocks: "+((System.nanoTime()-started)/30/1e6)+" ms; merged local air boxes: "+localRamp.size());
        VoxelShape unchanged=TerrainContactGeometry.removeAir(Shapes.block(),List.of(),0,0,0);
        require(unchanged==Shapes.block(),"missing/partial snapshots never disable saved collision");
        System.out.println("PASS: native rays + Minecraft movement/step solver: floor seams, oblique wall sliding, thin doors, ceilings, stairs, flight landings, and non-destructive partial clearance");
    }
}
'''
(OUT / 'NativeCollisionTest.java').write_text(source, encoding='utf-8')
JDK = next((ROOT / '.tools/java').iterdir())
cache = ROOT / '.tools/gradle-home/caches'
mc = next((cache/'fabric-loom/minecraftMaven/net/minecraft/minecraft-common').rglob('*.jar'))
classpath = os.pathsep.join(map(str, [mc, *sorted((cache/'modules-2/files-2.1').rglob('*.jar'))]))
def java_args(name, arguments):
    file = OUT / (name + '.args')
    file.write_text('\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in arguments), encoding='utf-8')
    subprocess.run([str(JDK/'bin'/name), '@' + str(file)], check=True)
java_args('javac.exe', ['-cp', classpath, '-d', OUT, OUT/'NativeCollisionTest.java',
    ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainContactGeometry.java',
    ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainWallGeometry.java',
    ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainAirShape.java'])
java_args('java.exe', ['-cp', str(OUT) + os.pathsep + classpath, 'NativeCollisionTest', OUT/'contacts.txt'])
