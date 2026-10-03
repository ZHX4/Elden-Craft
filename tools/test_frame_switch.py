"""Run the real frame-retirement and F8 handlers with deterministic GPU/key states."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring'
OUT = ROOT / 'build/frame-switch-test'
OUT.mkdir(parents=True, exist_ok=True)


def function(path, signature):
    source = path.read_text(encoding='utf-8')
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


client = ER / 'mc-bridge/src/client/java/dev/ermc/bridge/client/FramePassthrough.java'
java = r'''
import java.util.*;
public class FrameSwitchTest {
    static class Logger {void info(String s,Object... args) {}}
    static final Logger LOG=new Logger();
    static long statsAt, frameCounter;
    static int rendered,captures,published,waitingCapture,waitingHost,cur;
    static boolean worldCaptured,enabled=true,failed;
    static final int[][] PBO=new int[6][4];
    static final long[] READY=new long[6],FRAME_ID=new long[6];
    static final boolean[] PENDING=new boolean[6],GPU_FRAME=new boolean[6];
    static class Pose {long mcFrame;}
    static final Pose[] POSE=new Pose[6];
    static final List<Integer> publications=new ArrayList<>();
    static boolean fits(RenderTarget target) {return true;}
    static void captureLayer(RenderTarget main,int layer,int format,int type) {}
    static void publish(int set) {publications.add(set);}
    static class RenderTarget {int frameBufferId;}
    static class Minecraft {
        static Minecraft getInstance() {return new Minecraft();}
        RenderTarget getMainRenderTarget() {return new RenderTarget();}
    }
    static class Overlay {
        static boolean host;
        static boolean active() {return true;}
        static boolean hostMode() {return host;}
    }
    static class CameraSync {
        enum Mode {DRIVE_HOST}
        static Mode mode() {return Mode.DRIVE_HOST;}
        static boolean driving() {return true;}
    }
    static class GlStateManager {static void _glBindFramebuffer(int target,int id) {}}
    static class GL11 {static void glFlush() {}}
    static class GL12 {static final int GL_BGRA=0,GL_UNSIGNED_INT_8_8_8_8_REV=0;}
    static class GL15 {static void glBindBuffer(int target,int id) {}}
    static class GL21 {static final int GL_PIXEL_PACK_BUFFER=0;}
    static class GL30 {static final int GL_READ_FRAMEBUFFER=0;}
    static class GL32 {
        static final int GL_ALREADY_SIGNALED=1,GL_CONDITION_SATISFIED=2,GL_SYNC_GPU_COMMANDS_COMPLETE=0;
        static final Set<Long> complete=new HashSet<>();
        static int glClientWaitSync(long fence,int flags,long timeout) {return complete.contains(fence)?1:0;}
        static void glDeleteSync(long fence) {}
        static long glFenceSync(int flags,int timeout) {return 1;}
    }
    static class GpuTransport {
        static final long[] used=new long[6];
        static void discard(int set) {used[set]=0;}
        static void finish(int set,long id) {used[set]=id;}
    }
    static void pending(int set,long id,boolean complete) {
        PENDING[set]=true;GPU_FRAME[set]=true;FRAME_ID[set]=id;READY[set]=id;
        GpuTransport.used[set]=id;
        if(complete) GL32.complete.add(id);
    }
    static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
'''
java += function(client, 'public static boolean wanted()') + '\n'
java += function(client, 'public static void endFrame(Minecraft mc)') + '\n'
java += r'''
    public static void main(String[] args) {
        // F8 can occur before Overlay.onFrame has disabled the still-active overlay.
        Overlay.host=true;
        check(!wanted(),"F8 must immediately stop new captures and stale pose publication");
        for(int i=0;i<6;i++) pending(i,100+i,true);
        endFrame(new Minecraft());
        check(publications.isEmpty(),"Discarded frames must not pin the host camera again");
        for(int i=0;i<6;i++) check(GpuTransport.used[i]==0 && !PENDING[i],"Every abandoned texture must be reusable");
        // An unfinished GL capture cannot be reused; release it on a later paused frame.
        pending(2,200,false);
        endFrame(new Minecraft());
        check(PENDING[2] && GpuTransport.used[2]==200,"Do not reuse GPU work still in flight");
        GL32.complete.add(200L);
        endFrame(new Minecraft());
        check(!PENDING[2] && GpuTransport.used[2]==0,"Deferred capture must be freed while paused");
        // Returning to Minecraft publishes the newest completed pose and frees superseded ones.
        Overlay.host=false;
        pending(0,301,true);pending(1,303,true);pending(4,302,true);
        endFrame(new Minecraft());
        check(publications.equals(List.of(1)),"Publish the newest completed frame on return");
        check(GpuTransport.used[0]==0 && GpuTransport.used[4]==0 && GpuTransport.used[1]==303,
            "Only published captures should await the host acknowledgement");
        System.out.println("PASS: F8 capture retirement, delayed GL completion and resumed publication");
    }
}
'''
(OUT / 'FrameSwitchTest.java').write_text(java, encoding='utf-8')
jdk = next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent
subprocess.run([str(jdk / 'javac.exe'), '-d', str(OUT), str(OUT / 'FrameSwitchTest.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'FrameSwitchTest'], check=True)

native = r'''
#include <cassert>
#include <cstdint>
#include <cstdio>
using UINT=unsigned;using UINT64=uint64_t;using LONG64=int64_t;using HWND=int;
constexpr unsigned kGpuSlots=6,ERMC_FRAME_SLOTS=3;
struct Fence {UINT64 value=0;UINT64 GetCompletedValue() {return value;}} ready,copies;
Fence *g_gpuReady=&ready,*g_fence=&copies;
UINT64 g_gpuAckFence[kGpuSlots]={},g_gpuAckFrame[kGpuSlots]={};
UINT g_gpuGeneration=7;
alignas(8) unsigned char data[512]={};unsigned char *g_frames=data;
struct ErmcFrameHeader {UINT seq=0,width=0,height=0,flags=0;UINT64 frameId=0;};
alignas(8) unsigned char headers[ERMC_FRAME_SLOTS][64]={};
ErmcFrameHeader* slot_header(unsigned i) {return (ErmcFrameHeader*)headers[i];}
LONG64 InterlockedExchange64(volatile LONG64* p,LONG64 v) {auto old=*p;*p=v;return old;}
LONG64 InterlockedCompareExchange64(volatile LONG64* p,LONG64 v,LONG64 c) {auto old=*p;if(old==c)*p=v;return old;}
struct ErmcHeader {uint32_t mcSwitchReq=0,hostFocusReq=0;} shared;
ErmcHeader* shm_header() {return &shared;}
HWND foreground=2;bool keyDown=false;
HWND game_hwnd() {return 1;}HWND GetForegroundWindow() {return foreground;}
constexpr int VK_F8=119,SW_RESTORE=9;
int GetAsyncKeyState(int) {return keyDown?0x8000:0;}
void ShowWindow(HWND,int) {}void SetForegroundWindow(HWND h) {foreground=h;}
void SetActiveWindow(HWND) {}void SetFocus(HWND) {}void log(const char*,...) {}
bool g_f8Down=false,g_focusSeenInit=false;uint32_t g_focusSeen=0;
'''
native += function(ER / 'er-bridge/src/gpu_transport.h', 'static void gpu_acknowledge(') + '\n'
native += function(ER / 'er-bridge/src/game.cpp', 'static void handle_switching()') + '\n'
native += r'''
int main() {
    handle_switching();keyDown=true;shared.hostFocusReq++;handle_switching();
    for(int i=0;i<10;i++) handle_switching();
    assert(foreground==1 && shared.mcSwitchReq==0);
    keyDown=false;handle_switching();keyDown=true;handle_switching();
    for(int i=0;i<10;i++) handle_switching();
    assert(shared.mcSwitchReq==1);
    keyDown=false;handle_switching();foreground=2;keyDown=true;handle_switching();
    foreground=1;handle_switching();assert(shared.mcSwitchReq==1);
    keyDown=false;handle_switching();keyDown=true;handle_switching();assert(shared.mcSwitchReq==2);
    auto* publication=(UINT64*)(data+0xA0);auto* ack=(UINT64*)(data+0x60);
    publication[0]=101;ready.value=101;gpu_acknowledge(100);assert(ack[0]==0);
    gpu_acknowledge(100,true);assert(ack[0]==101);
    publication[1]=102;ready.value=102;g_gpuAckFence[1]=9;g_gpuAckFrame[1]=102;
    copies.value=8;gpu_acknowledge(100,true);assert(ack[1]==0);
    copies.value=9;gpu_acknowledge(100,true);assert(ack[1]==102 && g_gpuAckFence[1]==0);
    publication[2]=103;gpu_acknowledge(100,true);assert(ack[2]==0);
    ready.value=103;gpu_acknowledge(100,true);assert(ack[2]==103);
    puts("PASS: held F8 handoff and safe GPU acknowledgements during pause");
}
'''
(OUT / 'native.cpp').write_text(native, encoding='utf-8')
cxx = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
exe = OUT / 'native.exe'
subprocess.run([str(cxx), '-std=c++17', '-static', str(OUT / 'native.cpp'), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
