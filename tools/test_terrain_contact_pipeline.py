"""Exercise production shared-memory parsing and per-level collision policy.

World/player fixtures replace the running game; Minecraft's shape implementation
and the native ray output are real. Run test_native_terrain_collision.py first.
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/terrain-contact-pipeline-test'
OUT.mkdir(parents=True, exist_ok=True)
JAVA = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java'
stubs = {
    'net/minecraft/world/level/ClipContext.java': '''package net.minecraft.world.level;
import net.minecraft.world.phys.Vec3;import net.minecraft.world.entity.Entity;
public record ClipContext(Vec3 getFrom,Vec3 getTo,Block block,Fluid fluid,Entity entity) {
 public enum Block {COLLIDER} public enum Fluid {NONE}
}''',
    'net/minecraft/world/entity/Entity.java': '''package net.minecraft.world.entity;
import net.minecraft.world.phys.*;
public class Entity {
 public Vec3 feet=new Vec3(.5,100,.5),velocity=Vec3.ZERO;
 public Vec3 position(){return feet;}
 public double getX(){return feet.x;} public double getY(){return feet.y;} public double getZ(){return feet.z;}
 public Vec3 getDeltaMovement(){return velocity;} public void setDeltaMovement(Vec3 v){velocity=v;}
 public void setPos(double x,double y,double z){feet=new Vec3(x,y,z);}
 public AABB getBoundingBox(){return new AABB(feet.x-.3,feet.y,feet.z-.3,feet.x+.3,feet.y+1.8,feet.z+.3);}
}''',
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
import net.minecraft.world.entity.Entity;import net.minecraft.world.level.Level;import java.util.UUID;
public class Player extends Entity {
 public final Abilities abilities=new Abilities();public boolean spectator,gliding,grounded;public float fallDistance;
 public final Level world;public final UUID id=UUID.randomUUID();public Player(Level w){world=w;}
 public boolean isSpectator(){return spectator;}public boolean isFallFlying(){return gliding;}
 public Abilities getAbilities(){return abilities;}public Level level(){return world;}public UUID getUUID(){return id;}
 public void setOnGround(boolean b){grounded=b;}public void stopFallFlying(){gliding=false;}
}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
import net.minecraft.world.phys.shapes.*;import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;
public class BlockState {public VoxelShape shape=Shapes.block();public boolean terrain=true;
 public VoxelShape getShape(Level w,BlockPos p){return shape;}}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
import java.util.concurrent.*;
public class Level {
 public final BlockState state=new BlockState();public CountDownLatch entered,release;public boolean loaded=true;
 public boolean hasChunk(int x,int z){return loaded;}
 public BlockState getBlockState(BlockPos p){
  if(entered!=null){entered.countDown();try{release.await(300,TimeUnit.MILLISECONDS);}catch(Exception e){throw new RuntimeException(e);}}
  return state;
 }
}''',
    'dev/ermc/bridge/ErBridgeMod.java': '''package dev.ermc.bridge;
import net.minecraft.world.level.block.state.BlockState;
public class ErBridgeMod {public static boolean isTerrain(BlockState s){return s.terrain;}}''',
    'dev/ermc/bridge/TerrainManager.java': '''package dev.ermc.bridge;
public class TerrainManager {public static boolean bridge=true,settled=true;
 public static boolean isBridgeWorld(){return bridge;}public static boolean recallSettled(long delay){return settled;}}''',
    'dev/ermc/bridge/link/ErLink.java': '''package dev.ermc.bridge.link;
public class ErLink {public static final ErLink INSTANCE=new ErLink();public BridgeShm shm;public boolean live=true;
 public static ErLink get(){return INSTANCE;}public boolean alive(){return live;}
 public int readContacts(float[] d,float[] o,int[] m,int sequence){return shm.readContacts(d,o,m,sequence);}}'''
}
for name, content in stubs.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')

source = r'''
import dev.ermc.bridge.*;import dev.ermc.bridge.link.*;
import java.nio.*;import java.nio.channels.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;import net.minecraft.world.phys.*;import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.level.ClipContext;import net.minecraft.core.Direction;
public class ContactPipelineTest {
 static MappedByteBuffer memory;static float[][] rays;static int sequence;
 static void require(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void publish(int count,int zone){
  int base=Protocol.OFF_CONTACTS;memory.putInt(base,++sequence);
  memory.putInt(base+4,count);memory.putInt(base+8,zone);memory.putInt(base+12,15);
  for(int a=0;a<6;a++)memory.putFloat(base+16+a*4,0);
  for(int i=0;i<count;i++){int at=base+40+i*Protocol.CONTACT_SIZE;
   for(int a=0;a<6;a++)memory.putFloat(at+a*4,rays[i][a]);memory.putInt(at+24,(int)rays[i][6]);}
  memory.putInt(base,++sequence);
 }
 public static void main(String[] args)throws Exception {
  try(Scanner in=new Scanner(Path.of(args[0]))){in.useLocale(Locale.ROOT);
   while(in.hasNext()){String name=in.next();int count=in.nextInt();float[][] data=new float[count][7];
    for(int i=0;i<count;i++)for(int a=0;a<7;a++)data[i][a]=in.nextFloat();
    if(name.equals("floor")){rays=data;break;}}
  }
  require(rays!=null,"native floor fixture exists");
  try(FileChannel file=FileChannel.open(Path.of(args[1]),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)){
   file.write(ByteBuffer.wrap(new byte[1]),Protocol.SHM_SIZE-1);
   memory=file.map(FileChannel.MapMode.READ_WRITE,0,Protocol.SHM_SIZE);memory.order(ByteOrder.LITTLE_ENDIAN);
  }
  var ctor=BridgeShm.class.getDeclaredConstructor(MappedByteBuffer.class);ctor.setAccessible(true);
  BridgeShm shm=ctor.newInstance(memory);ErLink.get().shm=shm;
  memory.putInt(Protocol.OFF_COLLISION_CONTROL,0);
  float[] feet={2,3,4},velocity={60,-12,-5};shm.writeCollision(1,14,feet,4,velocity);
  int control=Protocol.OFF_COLLISION_CONTROL;
  require(memory.getInt(control)==2 && memory.getInt(control+4)==3 && memory.getInt(control+8)==14,"simulation pose uses its own seqlock");
  require(memory.getFloat(control+16)==2 && memory.getFloat(control+24)==4 && memory.getFloat(control+28)==4
   && memory.getFloat(control+32)==60 && memory.getFloat(control+12)==2 && memory.getFloat(control+44)==4,
   "actual feet, previous XYZ and velocity survive the mailbox");
  shm.writeCollision(0,0,feet,0,velocity);require(memory.getInt(control)==4 && memory.getInt(control+4)==0,"host handoff releases collision requests");
  var map=new CoordMap.Mapping(0,0,0,1,true,false,14,0);CoordMap.set(map);
  Level client=new Level();Player player=new Player(client);publish(rays.length,14);
  TerrainManager.settled=false;NativeTerrainCollision.refresh(client,map);
  require(!NativeTerrainCollision.append(player,client,player.getBoundingBox().inflate(.3),List.of()).isEmpty(),"first joining movement tick has native support before recall settles");
  TerrainManager.settled=true;
  VoxelShape base=client.state.shape;
  VoxelShape cleared=NativeTerrainCollision.collision(player,client,new BlockPos(0,100,0),base);
  require(cleared.isEmpty(),"ray-verified room air removes the obsolete local cube");
  require(NativeTerrainCollision.collision(player,client,new BlockPos(4,99,0),base)==base,"unmeasured ground is never disabled");
  float[][] completeRays=rays;
  rays=new float[][]{{-.2f,-.125f,-.2f,.2f,0,.2f,1}};
  publish(1,14);memory.putInt(Protocol.OFF_CONTACTS+12,3);NativeTerrainCollision.refresh(client,map);
  require(NativeTerrainCollision.collision(player,client,new BlockPos(0,101,0),base).isEmpty(),
   "a partial floor refresh must not put invisible coarse cubes back at body height");
  rays=completeRays;publish(rays.length,14);NativeTerrainCollision.refresh(client,map);
  player.abilities.flying=true;
  require(!NativeTerrainCollision.append(player,client,player.getBoundingBox().inflate(.3),List.of()).isEmpty(),"creative flight keeps world collision");
  player.gliding=true;
  require(NativeTerrainCollision.collision(player,client,new BlockPos(0,100,0),base).isEmpty(),"elytra uses the same measured air without enabling coarse invisible walls");
  player.spectator=true;List<VoxelShape> existing=List.of(base);
  require(NativeTerrainCollision.append(player,client,player.getBoundingBox(),existing)==existing,"spectator retains ordinary noclip");player.spectator=false;
  memory.putInt(Protocol.OFF_CONTACTS,sequence+1);NativeTerrainCollision.refresh(client,map);
  require(NativeTerrainCollision.collision(player,client,new BlockPos(0,100,0),base)==cleared,"an interrupted seqlock read keeps the previous usable geometry");
  memory.putInt(Protocol.OFF_CONTACTS,sequence);
  Level server=new Level();server.entered=new CountDownLatch(1);server.release=new CountDownLatch(1);
  Thread worker=new Thread(()->NativeTerrainCollision.refresh(server,map));worker.start();
  require(server.entered.await(2,TimeUnit.SECONDS),"server entered shape preparation");
  long start=System.nanoTime();NativeTerrainCollision.append(player,client,player.getBoundingBox().inflate(.3),List.of());
  long elapsed=System.nanoTime()-start;server.release.countDown();worker.join();
  require(elapsed<50_000_000,"server shape work must not hold a client movement monitor: "+elapsed);
  publish(0,14);NativeTerrainCollision.refresh(client,map);
  require(NativeTerrainCollision.collision(player,client,new BlockPos(0,100,0),base).isEmpty(),"empty partial batches retain the last measured air and support");
  publish(rays.length,99);NativeTerrainCollision.reset();NativeTerrainCollision.refresh(client,map);
  require(NativeTerrainCollision.append(player,client,player.getBoundingBox(),existing)==existing,"foreign-zone samples cannot affect movement");
  publish(rays.length,14);NativeTerrainCollision.refresh(client,map);ErLink.get().live=false;
  require(NativeTerrainCollision.collision(player,client,new BlockPos(0,100,0),base)==base,"host disconnect keeps cached world hitboxes");
  ErLink.get().live=true;
  require(!NativeTerrainCollision.append(null,client,new AABB(-.2,99.95,-.2,.2,100.05,.2),List.of()).isEmpty(),
   "destination checks without a current player include native floors");
  Vec3 from=new Vec3(0,103,0),to=new Vec3(0,98,0);
  var miss=BlockHitResult.miss(to,Direction.DOWN,BlockPos.containing(to));
  var ray=new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player);
  require(NativeTerrainCollision.clip(client,ray,miss).getLocation().y==100,
   "projectile clipping sees native floor before generated packets arrive");
  require(NativeTerrainCollision.clip(client,ray,miss).getBlockPos().getY()==99,
   "projectile contact refers to the solid side below the floor, not the air block above it");
  player.abilities.flying=false;player.setPos(.2,103,.2);NativeTerrainCollision.recoverLanding(player);
  player.setPos(.2,99.9,.2);NativeTerrainCollision.recoverLanding(player);
  require(Math.abs(player.getY()-100)<.0001,"actual downward floor crossing recovers without losing horizontal position");
  player.setPos(3.5,100.2,.2);NativeTerrainCollision.recoverLanding(player);
  player.setPos(.2,99,.2);NativeTerrainCollision.recoverLanding(player);
  require(player.getY()==99,"a floor outside the real crossing point must not grab movement around corners");
  player.setPos(.2,103,.2);NativeTerrainCollision.recoverLanding(player);
  player.setPos(.2,99,.2);NativeTerrainCollision.teleported(player);NativeTerrainCollision.recoverLanding(player);
  require(player.getY()==99,"a pearl or chorus arrival does not replay the previous fall trajectory");
  rays=new float[][]{{3,0,-1,3.0625f,2.625f,1,2}};
  publish(1,14);NativeTerrainCollision.refresh(client,map);player.setPos(2.6,100,0);
  require(!NativeTerrainCollision.append(player,client,player.getBoundingBox().expandTowards(new Vec3(2,0,0)),List.of()).isEmpty(),
   "approaching a known wall beyond the old source radius cannot disable flight collision");
  rays=new float[][]{{-.2f,-.125f,-.2f,.2f,0,.2f,1}};
  publish(1,14);memory.putInt(Protocol.OFF_CONTACTS+12,3);NativeTerrainCollision.refresh(client,map);
  require(!NativeTerrainCollision.append(player,client,player.getBoundingBox().expandTowards(new Vec3(2,0,0)),List.of()).isEmpty(),
   "a new floor-only partial batch must retain the previous wall until urgent wall sampling completes");
  rays=new float[13*21][7];int next=0;
  for(int y=0;y<21;y++)for(int z=0;z<13;z++) {
   float cross=(z-6)*.125f;
   rays[next++]=new float[]{1,y*.125f,cross-.0625f,1.0625f,(y+1)*.125f,cross+.0625f,2};
  }
  publish(rays.length,14);NativeTerrainCollision.refresh(client,map);
  player.setPos(2.1,100,.5);Vec3 free=new Vec3(.2,-.08,.1);
  require(NativeTerrainCollision.slide(player,free).equals(free),"production movement preserves free motion behind a previous wall");
  NativeTerrainCollision.finishMove(player);
  Vec3 returning=new Vec3(-1,0,0);NativeTerrainCollision.slide(player,returning);
  require(!NativeTerrainCollision.append(player,client,player.getBoundingBox().expandTowards(returning),List.of()).isEmpty(),
   "rejecting an old plane must keep the actual wall collider when returning to it");
  NativeTerrainCollision.finishMove(player);
  player.setPos(.5,100,.5);
  require(Math.abs(NativeTerrainCollision.slide(player,new Vec3(2,0,0)).x-.7)<1e-5,
   "a current wall still stops penetration from the measured side");
  NativeTerrainCollision.finishMove(player);
  System.out.println("PASS: contact protocol, startup support, projectile rays, destination checks, flight lookahead, real floor crossings, teleport arrivals, partial updates and independent client/server movement");
 }
}
'''
(OUT / 'ContactPipelineTest.java').write_text(source, encoding='utf-8')
JDK = next((ROOT / '.tools/java').iterdir())
cache = ROOT / '.tools/gradle-home/caches'
mc = next((cache/'fabric-loom/minecraftMaven/net/minecraft/minecraft-common').rglob('*.jar'))
compiled = ROOT / 'bridge-base/elden-ring/mc-bridge/build/classes/java/main'
classpath = os.pathsep.join(map(str, [mc, compiled, *sorted((cache/'modules-2/files-2.1').rglob('*.jar'))]))
production = [JAVA/'dev/ermc/bridge'/name for name in (
    'NativeTerrainCollision.java', 'TerrainContactGeometry.java', 'TerrainWallGeometry.java', 'TerrainAirShape.java', 'TerrainLanding.java', 'CoordMap.java',
    'link/BridgeShm.java', 'link/Protocol.java')]
def run(name, arguments):
    file = OUT / (name + '.args')
    file.write_text('\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in arguments), encoding='utf-8')
    subprocess.run([str(JDK/'bin'/name), '@' + str(file)], check=True)
run('javac.exe', ['-cp', classpath, '-d', OUT, *[OUT/name for name in stubs], *production, OUT/'ContactPipelineTest.java'])
run('java.exe', ['-cp', str(OUT) + os.pathsep + classpath, 'ContactPipelineTest',
    ROOT/'build/native-terrain-collision-test/contacts.txt', OUT/'bridge.shm'])
