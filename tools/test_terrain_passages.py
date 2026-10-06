"""Run the real two-stage passage refiner against floors, ceilings and walls."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / 'build/terrain-passages-test'
out.mkdir(parents=True, exist_ok=True)
sources = {
'it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap.java': '''package it.unimi.dsi.fastutil.longs;
public class Long2ObjectOpenHashMap<V> extends java.util.HashMap<Long,V> {}''',
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
public static BlockPos containing(double x,double y,double z){return new BlockPos((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
public static Iterable<BlockPos> betweenClosed(BlockPos a,BlockPos b){var list=new java.util.ArrayList<BlockPos>();
for(int x=a.x;x<=b.x;x++)for(int y=a.y;y<=b.y;y++)for(int z=a.z;z<=b.z;z++)list.add(new BlockPos(x,y,z));return list;}
}''',
'net/minecraft/util/Mth.java': '''package net.minecraft.util;
public class Mth {public static int floor(double v){return (int)Math.floor(v);}}''',
'net/minecraft/world/level/ChunkPos.java': '''package net.minecraft.world.level;
public class ChunkPos {public static long asLong(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}}''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public class Vec3 {public static final Vec3 ZERO=new Vec3(0,0,0);public final double x,y,z;public Vec3(double a,double b,double c){x=a;y=b;z=c;}
public double distanceToSqr(Vec3 b){return Math.pow(x-b.x,2)+Math.pow(y-b.y,2)+Math.pow(z-b.z,2);}
public Vec3 multiply(double a,double b,double c){return new Vec3(x*a,y*b,z*c);}
public Vec3 subtract(Vec3 b){return new Vec3(x-b.x,y-b.y,z-b.z);}
public double dot(Vec3 b){return x*b.x+y*b.y+z*b.z;}
public double horizontalDistanceSqr(){return x*x+z*z;}public double lengthSqr(){return x*x+y*y+z*z;}
public Vec3 scale(double a){return multiply(a,a,a);}public Vec3 normalize(){double n=Math.sqrt(x*x+y*y+z*z);return n==0?this:scale(1/n);}}''',
'net/minecraft/world/phys/AABB.java': '''package net.minecraft.world.phys;
import net.minecraft.core.BlockPos;
public class AABB {public final double minX,minY,minZ,maxX,maxY,maxZ;
public AABB(double x,double y,double z,double a,double b,double c){minX=x;minY=y;minZ=z;maxX=a;maxY=b;maxZ=c;}
public AABB expandTowards(Vec3 v){return new AABB(minX+Math.min(0,v.x),minY+Math.min(0,v.y),minZ+Math.min(0,v.z),maxX+Math.max(0,v.x),maxY+Math.max(0,v.y),maxZ+Math.max(0,v.z));}
public AABB move(BlockPos p){return new AABB(minX+p.x(),minY+p.y(),minZ+p.z(),maxX+p.x(),maxY+p.y(),maxZ+p.z());}
public boolean intersects(AABB b){return maxX>b.minX&&minX<b.maxX&&maxY>b.minY&&minY<b.maxY&&maxZ>b.minZ&&minZ<b.maxZ;}}''',
'net/minecraft/world/phys/shapes/CollisionContext.java': '''package net.minecraft.world.phys.shapes;
public class CollisionContext {public static CollisionContext of(Object p){return new CollisionContext();}}''',
'net/minecraft/world/phys/shapes/VoxelShape.java': '''package net.minecraft.world.phys.shapes;
public class VoxelShape {public boolean isEmpty(){return false;}
public net.minecraft.world.phys.AABB bounds(){return new net.minecraft.world.phys.AABB(0,0,0,1,1,1);}}''',
'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public record BlockState(String kind) {public boolean isAir(){return kind.equals("air");}
public boolean is(net.minecraft.world.level.block.Block b){return kind.equals(b.defaultBlockState().kind());}
public int getValue(Object ignored){return 16;}
public net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(Object l,Object p,Object c){return new net.minecraft.world.phys.shapes.VoxelShape();}}''',
'net/minecraft/world/level/block/Block.java': '''package net.minecraft.world.level.block;
import net.minecraft.world.level.block.state.BlockState;
public class Block {public static final int UPDATE_CLIENTS=2,UPDATE_KNOWN_SHAPE=16;
final String kind;public Block(String k){kind=k;}public BlockState defaultBlockState(){return new BlockState(kind);}}''',
'net/minecraft/world/level/block/Blocks.java': '''package net.minecraft.world.level.block;
public class Blocks {public static final Block AIR=new Block("air");}''',
'dev/ermc/bridge/TerrainBlock.java': '''package dev.ermc.bridge;
public class TerrainBlock {public static final Object HEIGHT=new Object();}''',
'dev/ermc/bridge/TerrainShapeBlockEntity.java': '''package dev.ermc.bridge;
public class TerrainShapeBlockEntity {int[] data=new int[256];
public int[] cells(){return data.clone();}public int[] fineCells(){return data.clone();}
public void setCells(int[] v){data=v.clone();}}''',
'dev/ermc/bridge/ErBridgeMod.java': '''package dev.ermc.bridge;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
public class ErBridgeMod {public static final Block TERRAIN=new Block("terrain"),TERRAIN_DETAIL=new Block("detail");
public static boolean isTerrain(BlockState s){return s.kind().equals("terrain")||s.kind().equals("detail");}}''',
'dev/ermc/bridge/CoordMap.java': '''package dev.ermc.bridge;
import net.minecraft.world.phys.Vec3;
public class CoordMap {public record Mapping(int zone){
public double[] toHost(double x,double y,double z){return new double[]{x,y,z};}
public Vec3 toMc(double x,double y,double z){return new Vec3(x,y,z);}}
public static Mapping get(){return new Mapping(1);}}''',
'dev/ermc/bridge/TerrainManager.java': '''package dev.ermc.bridge;
public class TerrainManager {public static boolean movingColumn(int x,int z){return false;}
public static boolean detailPending(int x,int z){return true;}public static void detailReady(int x,int z){}}''',
'net/minecraft/server/level/ServerPlayer.java': '''package net.minecraft.server.level;
import net.minecraft.world.phys.Vec3;
public class ServerPlayer {public static double feet=100,x=.5,z=.5;public double getY(){return feet;}
public float maxUpStep(){return .6f;}
public Vec3 position(){return new Vec3(x,feet,z);}
public Vec3 getViewVector(int tick){return new Vec3(1,0,0);}
public net.minecraft.world.phys.AABB getBoundingBox(){return new net.minecraft.world.phys.AABB(.2,feet,.2,.8,feet+1.8,.8);}}''',
'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
import dev.ermc.bridge.TerrainShapeBlockEntity;
public class ServerLevel {
public boolean hasChunk(int x,int z){return true;}
public java.util.Map<BlockPos,BlockState> blocks=new java.util.HashMap<>();
public java.util.Map<BlockPos,TerrainShapeBlockEntity> details=new java.util.HashMap<>();
public BlockState getBlockState(BlockPos p){return blocks.getOrDefault(p,new BlockState("air"));}
public Object getBlockEntity(BlockPos p){return details.get(p);}
public void setBlock(BlockPos p,BlockState s,int flags){blocks.put(p,s);
if(s.kind().equals("detail"))details.computeIfAbsent(p,k->new TerrainShapeBlockEntity());else details.remove(p);}
public int mask(int y,int x,int z){BlockPos p=new BlockPos(1,y,0);var s=getBlockState(p);
return s.isAir()?0:s.kind().equals("detail")?details.get(p).fineCells()[z*16+x]:0xFFFF;}
}''',
'org/slf4j/LoggerFactory.java': '''package org.slf4j;
public class LoggerFactory {public static LoggerFactory getLogger(String s){return new LoggerFactory();}
public void info(String s,Object... args){}}''',
'dev/ermc/bridge/link/ErLink.java': '''package dev.ermc.bridge.link;
public class ErLink {
static final ErLink INSTANCE=new ErLink();public static ErLink get(){return INSTANCE;}
public float[] rays;public int count,seq,submitted;public boolean missing,wall,occluded,low,narrow,flatRoom,raisedRoom;
public int submitRays(float[] r,int n,int flags,Object filter){
if(n>2048)throw new AssertionError("ray budget exceeded");rays=r.clone();count=n;submitted++;return ++seq;}
public boolean raysDone(int seq){return true;}
public void readHits(int n,float[] hits,int[] flags,int[] attrs){
if(n!=count)throw new AssertionError("wrong batch length");java.util.Arrays.fill(flags,0);
for(int i=0;i<n;i++){
 int a=i*6;double x=rays[a],y=rays[a+1],z=rays[a+2],ex=rays[a+3],ey=rays[a+4],ez=rays[a+5];
 boolean vertical=Math.abs(x-ex)<1e-6&&Math.abs(z-ez)<1e-6;
 double floor=x<1?100:99.5,ceiling=x<1?101.84:(low?101.2:101.34),hit=0;
 if(flatRoom){floor=100;ceiling=103.5;}
 if(raisedRoom){floor=100.75;ceiling=104;}
 if(vertical&&y>ey&&floor<=y&&floor>=ey&&(!missing||x<1)){flags[i]=1;hit=floor;hits[a+4]=1;}
 if(vertical&&y<ey&&ceiling>=y&&ceiling<=ey){flags[i]=1;hit=ceiling;hits[a+4]=-1;}
 // A low visibility ray aimed downhill intersects the upper stair tread.
 if(!vertical&&x<1&&ex>=1&&y>100&&ey<100){
  double t=(y-100)/(y-ey);if(x+t*(ex-x)<1){flags[i]=1;hit=100;hits[a+4]=1;}
 }
 if(!vertical&&wall&&Math.min(x,ex)<=1.92&&Math.max(x,ex)>=1.92){flags[i]=1;hit=y;hits[a+4]=0;}
 if(!vertical&&narrow)for(double edge:new double[]{1.16,1.84}){
  if(Math.min(x,ex)<=edge&&Math.max(x,ex)>=edge){flags[i]=1;hit=y;hits[a+4]=0;}
 }
 if(!vertical&&occluded&&x<1&&ex>=1){flags[i]=1;hit=y;hits[a+4]=0;}
 if(flags[i]!=0){hits[a]=(float)x;hits[a+1]=(float)hit;hits[a+2]=(float)z;}
}
}
}''',
'PassageTest.java': r'''
import dev.ermc.bridge.*;import dev.ermc.bridge.link.ErLink;
import net.minecraft.server.level.*;import net.minecraft.core.BlockPos;
import java.util.List;
public class PassageTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static ServerLevel run(boolean missing,boolean wall,boolean low,boolean occluded,boolean previousOpen,boolean invalidate){
  TerrainDetailManager.reset();var l=new ServerLevel();var link=ErLink.get();
  link.missing=missing;link.wall=wall;link.low=low;link.occluded=occluded;link.submitted=0;
  for(int y=98;y<=103;y++)l.setBlock(new BlockPos(1,y,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  if(previousOpen)l.setBlock(new BlockPos(1,100,0),new net.minecraft.world.level.block.state.BlockState("air"),0);
  TerrainDetailManager.noteColumn(1,0,100,true,103,98,99,8,true);
  var map=CoordMap.get();check(TerrainDetailManager.submit(map,List.of(new ServerPlayer())),"submit floors");
  check(link.count==1280,"bounded floor batch");TerrainDetailManager.collectResults(l,map);
  TerrainDetailManager.submit(map,List.of(new ServerPlayer()));
  check(link.count<=1280,"bounded body batch");
  if(invalidate)TerrainDetailManager.invalidate(1,0);
  int batches=0;while(TerrainDetailManager.busy()){
   TerrainDetailManager.collectResults(l,map);check(++batches<=4,"bounded body parts");
   if(!invalidate)TerrainDetailManager.submit(map,List.of(new ServerPlayer()));
  }
  check(!TerrainDetailManager.busy(),"release mailbox");
  return l;
 }
 public static void main(String[] args){
  var l=run(false,false,false,false,false,false);
  check(l.mask(99,4,4)==0xFF,"keep descending stair floor");
  check(l.mask(100,4,4)==0,"remove false block between lower floor and ceiling");
  check(l.mask(101,4,4)==0xFFE0,"keep actual low vault");
  // Coarse sampling has no block entity to inspect after a block became air.
  // It must preserve that measured air even if obstacle classification flips.
  l.setBlock(new BlockPos(1,100,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  check(TerrainDetailManager.preserveVerified(l,new BlockPos(1,100,0)),"remember cleared air without a block entity");
  check(l.mask(100,4,4)==0,"coarse refresh cannot recreate a full cube in verified air");
  TerrainDetailManager.noteColumn(1,0,100,false,103,98,99,8,false);
  check(TerrainDetailManager.preserveVerified(l,new BlockPos(1,100,0)),"classification change retains measured air");
  TerrainDetailManager.invalidate(1,0);
  check(!TerrainDetailManager.preserveVerified(l,new BlockPos(1,100,0)),"explicit door invalidation revokes cached air");
  l=run(false,true,false,false,false,false);
  check(l.mask(100,14,4)==0xFFFF,"keep real side wall");
  check(l.mask(100,4,4)==0,"clear walkable space beside the wall");
  l=run(true,false,false,false,false,false);check(l.mask(100,4,4)==0xFFFF,"missing floors cannot remove support");
  l=run(false,false,true,false,false,false);
  check(l.mask(100,4,4)==0,"low roof must not turn the whole column solid");
  check(l.mask(101,4,4)==0xFFF8,"retain real low roof");
  l=run(false,false,false,true,true,false);check(l.mask(100,4,4)==0,"occlusion cannot recreate cubes in verified air");
  l.setBlock(new BlockPos(1,100,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  check(TerrainDetailManager.preserveVerified(l,new BlockPos(1,100,0)),
      "already-empty loaded air must be remembered even when refinement changes no blocks");
  check(l.mask(100,4,4)==0,"coarse generation cannot fill unchanged verified air");
  l=run(false,false,false,false,false,true);check(l.mask(100,4,4)==0xFFFF,"door invalidation rejects old body batch");
  ServerPlayer.feet=100.4;l=run(false,false,false,false,false,false);
  check(l.mask(100,4,4)==0,"a rounded floor under the player must not veto other cells");
  ServerPlayer.feet=100;ErLink.get().narrow=true;l=run(false,false,false,false,false,false);
  check(l.mask(100,2,8)==0xFFFF&&l.mask(100,13,8)==0xFFFF,"retain both narrow doorway edges");
  int free=0;for(int x=3;x<=12;x++)if(l.mask(100,x,8)==0)free++;
  check(free/16.0>=.6,"preserve a standing body's width in a sub-block doorway");
  ErLink.get().narrow=false;
  TerrainDetailManager.reset();var discovered=new ServerLevel();
  discovered.setBlock(new BlockPos(1,100,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  TerrainDetailManager.discover(discovered,List.of(new ServerPlayer()));
  check(TerrainDetailManager.submit(CoordMap.get(),List.of(new ServerPlayer())),"discover blocking ordinary ground without coarse obstacle flags");
  TerrainDetailManager.reset();discovered=new ServerLevel();
  discovered.setBlock(new BlockPos(1,100,0),new net.minecraft.world.level.block.state.BlockState("player-build"),0);
  TerrainDetailManager.discover(discovered,List.of(new ServerPlayer()));
  check(!TerrainDetailManager.submit(CoordMap.get(),List.of(new ServerPlayer())),"leave player builds out of discovery");
  check(!TerrainClearance.supportedCell(99,100,100),"drop inside one cell is not flat support");
  // Cold coarse cubes must be removed ahead of arrival after two batches,
  // without waiting for all 256 cells of any detailed column.
  TerrainTravelClearance.reset();TerrainDetailManager.reset();ErLink.get().flatRoom=true;
  var travel=new ServerLevel();
  for(int bx=-2;bx<=16;bx++)for(int bz=-2;bz<=2;bz++)for(int y=99;y<=104;y++)
   travel.setBlock(new BlockPos(bx,y,bz),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  var p=new ServerPlayer();
  check(TerrainTravelClearance.submit(CoordMap.get(),List.of(p)),"submit travel floor batch");
  check(ErLink.get().count==160,"160 floor rays independent of detailed columns");
  TerrainTravelClearance.collect(travel,CoordMap.get());
  check(ErLink.get().count==1696,"bounded body guard batch");
  TerrainTravelClearance.collect(travel,CoordMap.get());
  check(travel.getBlockState(new BlockPos(4,100,0)).kind().equals("detail"),"clear cold terrain four metres ahead");
  check(travel.details.get(new BlockPos(4,100,0)).fineCells()[8*16+8]==0,"clear body before the player arrives");
  check(travel.getBlockState(new BlockPos(4,99,0)).kind().equals("terrain"),"retain ground support");
  // Advance continuously into new coarse columns. Each next path is cleared
  // before position advances; the slow column refiner never runs in this test.
  for(int tick=0;tick<12;tick++){
   try{Thread.sleep(110);}catch(InterruptedException e){throw new RuntimeException(e);}
   ServerPlayer.x+=.5;
   check(TerrainTravelClearance.submit(CoordMap.get(),List.of(p)),"refresh moving corridor");
   TerrainTravelClearance.collect(travel,CoordMap.get());TerrainTravelClearance.collect(travel,CoordMap.get());
   var pos=new BlockPos((int)Math.floor(ServerPlayer.x+2),100,0);
   check(travel.getBlockState(pos).isAir() || travel.details.get(pos).fineCells()[8*16+8]==0,"future walking column ready");
  }
  TerrainTravelClearance.reset();ErLink.get().wall=true;ServerPlayer.x=.5;
  var blocked=new ServerLevel();blocked.setBlock(new BlockPos(1,100,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  TerrainTravelClearance.submit(CoordMap.get(),List.of(p));TerrainTravelClearance.collect(blocked,CoordMap.get());TerrainTravelClearance.collect(blocked,CoordMap.get());
  check(blocked.getBlockState(new BlockPos(1,100,0)).isAir()==false,"real gate cannot be erased");
  check(blocked.details.get(new BlockPos(1,100,0)).fineCells()[8*16+15]==0xFFFF,"retain the measured wall edge");
  TerrainTravelClearance.reset();ErLink.get().wall=false;
  var stale=new ServerLevel();stale.setBlock(new BlockPos(1,100,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  TerrainTravelClearance.submit(CoordMap.get(),List.of(p));TerrainTravelClearance.collect(stale,CoordMap.get());
  TerrainTravelClearance.invalidate();TerrainTravelClearance.collect(stale,CoordMap.get());
  check(stale.getBlockState(new BlockPos(1,100,0)).kind().equals("terrain"),"reject travel clearance after a door change");
  TerrainDetailManager.reset();ErLink.get().flatRoom=false;ErLink.get().raisedRoom=true;
  ServerPlayer.x=.5;var uphill=new ServerLevel();
  for(int y=99;y<=104;y++)uphill.setBlock(new BlockPos(1,y,0),ErBridgeMod.TERRAIN.defaultBlockState(),0);
  TerrainDetailManager.noteColumn(1,0,100,true,104,99,100,12,true);
  check(TerrainDetailManager.submit(CoordMap.get(),List.of(p)),"prefetch higher native ground before climbing");
  for(int part=0;part<5;part++){TerrainDetailManager.collectResults(uphill,CoordMap.get());TerrainDetailManager.submit(CoordMap.get(),List.of(p));}
  check(uphill.mask(101,8,8)==0,"higher surface body space is ready before player height changes");
  check(uphill.mask(100,8,8)==0xFFF,"retain actual higher surface");
  System.out.println("PASS: cold continuous travel prefetch, real walls, door invalidation, bounded rays and higher-ground lookahead");
  System.out.println("PASS: real two-stage refinement, descending doorway, low vault, walls, occlusion, missing support and door invalidation");
 }
}'''
}
for name, content in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
main = root / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'
jdk = next((root / '.tools/java').iterdir())
subprocess.run([str(jdk / 'bin/javac.exe'), '-d', str(out),
                *[str(out / name) for name in sources],
                *[str(main / name) for name in ['TerrainDetailManager.java', 'TerrainTravelClearance.java', 'TerrainClearance.java', 'TerrainPrefetch.java']]], check=True)
subprocess.run([str(jdk / 'bin/java.exe'), '-cp', str(out), 'PassageTest'], check=True)
