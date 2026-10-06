"""Verify stale GPU advertisements against the real Java transport without a GL context."""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/gpu-session-test'
PACKAGE = OUT / 'dev/ermc/bridge/client'
PACKAGE.mkdir(parents=True, exist_ok=True)
link = OUT / 'dev/ermc/bridge/link'
link.mkdir(parents=True, exist_ok=True)
(link / 'ErLink.java').write_text('''
package dev.ermc.bridge.link;
public class ErLink {
    public static int pid=567;
    public static ErLink get() {return new ErLink();}
    public int hostProcessId() {return pid;}
}
''', encoding='utf-8')
(PACKAGE / 'GpuSessionTest.java').write_text('''
package dev.ermc.bridge.client;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
public class GpuSessionTest {
    static void field(String name,Object value) throws Exception {
        var field=GpuTransport.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        try(FileChannel file=FileChannel.open(Path.of(args[0]),StandardOpenOption.CREATE,
                StandardOpenOption.READ,StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(new byte[1]),511);
            MappedByteBuffer shared=file.map(FileChannel.MapMode.READ_WRITE,0,512);
            for(int i=0;i<512;i++)shared.put(i,(byte)0);
            shared.order(ByteOrder.LITTLE_ENDIAN);
            shared.putInt(0x40,0x47504d43).putInt(0x44,2560).putInt(0x48,1439);
            shared.putInt(0x4c,7).putInt(0x50,123).putInt(0x54,6);
            // Pretend capabilities were checked and generation 7 was already imported.
            // Metadata validation must still reject another host process before any GL work.
            field("checked",true);field("supported",true);field("generation",7);
            check(!GpuTransport.open(shared,2560,1439),"Reject resources owned by the previous Elden Ring process");
            shared.putInt(0x50,0);
            check(!GpuTransport.open(shared,2560,1439),"Do not import resources without a host process");
            dev.ermc.bridge.link.ErLink.pid=0;
            check(!GpuTransport.open(shared,2560,1439),"Zero IDs on both sides do not represent a connected host");
            dev.ermc.bridge.link.ErLink.pid=567;
            shared.putInt(0x50,567);
            check(GpuTransport.open(shared,2560,1439),"Reuse the current host's matching generation");
            check(!GpuTransport.open(shared,1920,1080),"Reject mismatched transfer dimensions");
            shared.putInt(0x40,0);
            check(!GpuTransport.open(shared,2560,1439),"Reject resources released during host recreation");
        }
        System.out.println("PASS: stale process IDs, current generation, dimensions and withdrawn GPU resources");
    }
}
''', encoding='utf-8')
cache = ROOT / '.tools/gradle-home/caches'
jars = list((cache / 'modules-2/files-2.1/org.lwjgl').glob('*/3.3.3/*/*.jar'))
jars += list((cache / 'modules-2/files-2.1/org.slf4j/slf4j-api').glob('*/*/*.jar'))
jars += list((cache / 'fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly').glob('*/*.jar'))
classpath = os.pathsep.join(str(jar) for jar in jars)
source = ROOT / 'bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/GpuTransport.java'
jdk = next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent
subprocess.run([str(jdk / 'javac.exe'), '-cp', classpath, '-d', str(OUT),
                str(link / 'ErLink.java'), str(source), str(PACKAGE / 'GpuSessionTest.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT)+os.pathsep+classpath,
                'dev.ermc.bridge.client.GpuSessionTest', str(OUT / 'frames.shm')], check=True)
