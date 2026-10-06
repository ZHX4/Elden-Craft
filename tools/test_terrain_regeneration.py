"""Run the production chunk cleanup against persisted terrain and player builds."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JDK = next((ROOT / '.tools/java').iterdir())
OUT = ROOT / 'build/terrain-regeneration-test'
OUT.mkdir(parents=True, exist_ok=True)
stubs = {
    'it/unimi/dsi/fastutil/longs/LongOpenHashSet.java': '''package it.unimi.dsi.fastutil.longs;
public class LongOpenHashSet extends java.util.HashSet<Long> {}''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public class BlockPos {
 public int x,y,z;
 public static class MutableBlockPos extends BlockPos {
  public void set(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 }
}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
public class Level {public static final Object OVERWORLD=new Object();}''',
    'net/minecraft/world/level/ChunkPos.java': '''package net.minecraft.world.level;
public class ChunkPos {public int x,z; public ChunkPos(int x,int z){this.x=x;this.z=z;}
 public long toLong(){return (long)x & 0xffffffffL | ((long)z << 32);}}''',
    'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public record BlockState(String kind) {}''',
    'net/minecraft/world/level/block/Blocks.java': '''package net.minecraft.world.level.block;
import net.minecraft.world.level.block.state.BlockState;
public class Blocks {public static final Blocks AIR=new Blocks();
 public BlockState defaultBlockState(){return new BlockState("air");}}''',
    'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
import net.minecraft.world.level.Level;
public class ServerLevel {
 public String name; public Object dim=Level.OVERWORLD;
 public ServerLevel(String name){this.name=name;} public Object dimension(){return dim;}
 public ServerLevel getServer(){return this;} public ServerLevel getWorldData(){return this;}
 public String getLevelName(){return name;}
}''',
    'net/minecraft/world/level/chunk/LevelChunkSection.java': '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Predicate;
public class LevelChunkSection {
 public BlockState[] blocks=new BlockState[4096];
 public LevelChunkSection(){java.util.Arrays.fill(blocks,new BlockState("air"));}
 public boolean maybeHas(Predicate<BlockState> p){for(var b:blocks)if(p.test(b))return true;return false;}
 public BlockState getBlockState(int x,int y,int z){return blocks[(y*16+z)*16+x];}
 public int changes;
 public BlockState setBlockState(int x,int y,int z,BlockState state){
  var old=getBlockState(x,y,z);blocks[(y*16+z)*16+x]=state;changes++;return old;}
 public void set(int x,int y,int z,String kind){blocks[(y*16+z)*16+x]=new BlockState(kind);}
}''',
    'net/minecraft/world/level/chunk/LevelChunk.java': '''package net.minecraft.world.level.chunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
public class LevelChunk {
 public LevelChunkSection[] sections={new LevelChunkSection(),new LevelChunkSection()};
 public final ChunkPos pos; public boolean unsaved; public int changes,removedDetail;
 public LevelChunk(int x,int z){pos=new ChunkPos(x,z);}
 public ChunkPos getPos(){return pos;} public LevelChunkSection[] getSections(){return sections;}
 public int getSectionYFromSectionIndex(int i){return i-4;}
 public void setUnsaved(boolean b){unsaved=b;}
 public BlockState setBlockState(BlockPos p,BlockState state,boolean moved){
  throw new AssertionError("chunk-level mutation would recursively wait for its own load future");
 }
 public java.util.Map<BlockPos,Entity> entities=new java.util.HashMap<>();
 public static class Entity {public void setRemoved(){}}
 public Entity getBlockEntity(BlockPos p){
  var section=sections[Math.floorDiv(p.y,16)+4];
  if(section.getBlockState(Math.floorMod(p.x,16),Math.floorMod(p.y,16),Math.floorMod(p.z,16)).kind().equals("detail"))return new Entity();
  return null;
 }
 public void removeBlockEntity(BlockPos p){
  if(Math.floorDiv(p.x,16)!=pos.x || Math.floorDiv(p.z,16)!=pos.z)throw new AssertionError("wrong chunk coordinates");
  if(getBlockEntity(p)!=null)removedDetail++;
 }
 public java.util.Map<BlockPos,Entity> getBlockEntities(){return entities;}
 public static class Status {public java.util.Set<Object> heightmapsAfter(){return java.util.Set.of();}}
 public Status getPersistedStatus(){return new Status();}
 public boolean primed;
 public int writes(){return sections[0].changes+sections[1].changes;}

}''',
    'net/minecraft/world/level/levelgen/Heightmap.java': '''package net.minecraft.world.level.levelgen;
public class Heightmap {public static void primeHeightmaps(net.minecraft.world.level.chunk.LevelChunk c,java.util.Set<Object> types){c.primed=true;}}''',
    'dev/ermc/bridge/ErBridgeMod.java': '''package dev.ermc.bridge;
import net.minecraft.world.level.block.state.BlockState;
public class ErBridgeMod {public static boolean isTerrain(BlockState s){return s.kind().equals("terrain")||s.kind().equals("detail");}}''',
    'dev/ermc/bridge/TerrainManager.java': '''package dev.ermc.bridge;
public class TerrainManager {public static final String BRIDGE_LEVEL_NAME="ER Bridge";}''',
}
for name, content in stubs.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
source = OUT / 'TerrainRegenerationTest.java'
source.write_text(r'''
import dev.ermc.bridge.TerrainRegeneration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public class TerrainRegenerationTest {
 static void require(boolean v,String why){if(!v)throw new AssertionError(why);}
 static LevelChunk saved(int x,int z){
  var c=new LevelChunk(x,z);
  c.sections[0].set(0,0,0,"terrain");c.sections[1].set(15,15,15,"detail");
  c.sections[0].set(1,0,0,"stone");c.sections[1].set(14,15,15,"chest");return c;
 }
 public static void main(String[] args){
  var bridge=new ServerLevel("ER Bridge");
  TerrainRegeneration.reset();
  var old=saved(-3,5);
  TerrainRegeneration.onChunkLoad(bridge,old);
  require(old.writes()==0 && old.removedDetail==0 && !old.unsaved,"joining must retain floor, walls and persisted detail until replacement is measured");
  require(old.sections[0].getBlockState(1,0,0).kind().equals("stone"),"player construction must survive cleanup");
  require(old.sections[1].getBlockState(14,15,15).kind().equals("chest"),"player container must survive cleanup");
  var reloaded=saved(-3,5);
  TerrainRegeneration.onChunkLoad(bridge,reloaded);
  require(reloaded.writes()==0,"unloading and reloading a chunk must retain this session's fresh terrain");
  var farther=saved(7,-9);
  TerrainRegeneration.onChunkLoad(bridge,farther);
  require(farther.writes()==0,"loading distant chunks cannot remove saved support");
  TerrainRegeneration.reset();
  TerrainRegeneration.onChunkLoad(bridge,reloaded);
  require(reloaded.writes()==0,"reopening must keep collision available during native startup");
  var ordinary=saved(33,44);
  TerrainRegeneration.onChunkLoad(new ServerLevel("My survival world"),ordinary);
  require(ordinary.writes()==0 && !ordinary.unsaved,"ordinary Minecraft worlds must remain unchanged");
  var nether=new ServerLevel("ER Bridge");nether.dim=new Object();
  var otherDimension=saved(33,44);
  TerrainRegeneration.onChunkLoad(nether,otherDimension);
  require(otherDimension.writes()==0,"only the host-terrain dimension may be cleaned");
  var empty=new LevelChunk(99,99);
  TerrainRegeneration.onChunkLoad(bridge,empty);
  require(!empty.unsaved && empty.writes()==0,"empty sections must avoid mutations");
  System.out.println("PASS: support retained across startup, world re-entry, distant loads and reloads; detail entities, player builds and ordinary worlds preserved");
 }
}
''', encoding='utf-8')
production = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainRegeneration.java'
subprocess.run([str(JDK/'bin/javac.exe'), '-d', str(OUT), str(production),
                *[str(OUT/name) for name in stubs], str(source)], check=True)
subprocess.run([str(JDK/'bin/java.exe'), '-cp', str(OUT), 'TerrainRegenerationTest'], check=True)
