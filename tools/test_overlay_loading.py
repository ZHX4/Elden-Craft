"""Exercise the actual overlay lifecycle during save loading and lost host heartbeats."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/overlay-loading-test'
OUT.mkdir(parents=True, exist_ok=True)
source = (ROOT / 'bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/Overlay.java').read_text(encoding='utf-8')


def method(signature, source=source):
    start = source.index(signature)
    opening = source.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


java = r'''
import java.util.*;
public class OverlayLoadingTest {
    static boolean transparentWindow=true,active,hostMode,busy,focusPending,hostPreparationPending=true;
    static long window=7,lastInWorldMs,lastSpinNs,lastWantMs;
    static float spinDegPerSec;
    static int lastSwitchReq=Integer.MIN_VALUE,savedX,savedY,savedW,savedH,appliedX=Integer.MIN_VALUE,appliedY,appliedW,appliedH;
    static final GameState STATE=new GameState();
    static final List<String> events=new ArrayList<>();
    static class Logger {void info(String message,Object... args) {}}
    static final Logger LOG=new Logger();
    static boolean windowedModeRequired() {return true;}
    static void setShadow(boolean value) {}
    static void objcBool(String selector,boolean value) {}
    static class Platform {
        static final Platform WINDOWS=new Platform();
        static Platform get() {return WINDOWS;}
    }
    static class Protocol {
        static final int STATE_PLAYER_VALID=1,STATE_HOST_BUSY=2,STATE_WINDOW_VALID=4;
    }
    static class GameState {
        int flags=Protocol.STATE_WINDOW_VALID;
        int winX=0,winY=0,winW=1920,winH=1080;
        boolean has(int value) {return (flags&value)!=0;}
    }
    static class TerrainManager {
        static boolean bridge=true,settled;
        static int recalls;
        static boolean isBridgeWorld() {return bridge;}
        static boolean recallSettled(long ms) {return settled;}
        static void requestRecall() {recalls++;settled=false;events.add("recall");}
    }
    static class ErLink {
        static final ErLink INSTANCE=new ErLink();
        static boolean alive;
        int switches;
        static ErLink get() {return INSTANCE;}
        boolean poll() {return alive;}
        void bumpMcHeartbeat() {}
        int mcSwitchRequests() {return switches;}
        boolean snapshot(GameState state) {return true;}
        int hostProcessId() {return 8;}
        void requestHostFocus() {events.add("host-focus");}
    }
    static class FramePassthrough {
        static boolean compositing,enabled=true;
        static boolean activeInHost() {return compositing;}
        static boolean enabled() {return enabled;}
    }
    static class CameraSync {
        enum Mode {DRIVE_HOST,FOLLOW_HOST}
        static Mode mode() {return Mode.DRIVE_HOST;}
        static void suspend() {events.add("camera-suspend");}
    }
    static class PauseScreen {}
    static class ReceivingLevelScreen {}
    static class Minecraft {
        Player player;
        Level level;
        Object screen,overlay;
        final Options options=new Options();
        final Mouse mouseHandler=new Mouse();
        Window getWindow() {return new Window();}
        Object getOverlay() {return overlay;}
        void setScreen(Object screen) {this.screen=screen;}
        void pauseGame(boolean value) {screen=new PauseScreen();}
        static class Player {
            float yaw,yRotO;
            float getYRot() {return yaw;}
            void setYRot(float value) {yaw=value;}
            Object blockPosition() {return null;}
        }
        static class Level {boolean chunk;boolean hasChunkAt(Object pos) {return chunk;}}
        static class Window {boolean isFullscreen() {return false;}void toggleFullScreen() {}}
        static class Options {Setting fullscreen() {return new Setting();}}
        static class Setting {void set(boolean value) {}}
        static class Mouse {void releaseMouse() {}}
    }
    static class GLFW {
        static final int GLFW_ICONIFIED=1,GLFW_TRUE=1,GLFW_FALSE=0,GLFW_DECORATED=2,GLFW_FLOATING=3;
        static boolean iconified,visible=true;
        static int focuses;
        static int glfwGetWindowAttrib(long window,int type) {return iconified?GLFW_TRUE:GLFW_FALSE;}
        static void glfwRestoreWindow(long window) {iconified=false;events.add("restore-mc");}
        static void glfwShowWindow(long window) {visible=true;events.add("show-mc");}
        static void glfwHideWindow(long window) {visible=false;events.add("hide-mc");}
        static void glfwFocusWindow(long window) {focuses++;events.add("focus-mc");}
        static long glfwGetWindowMonitor(long window) {return 0;}
        static void glfwGetWindowPos(long window,int[] x,int[] y) {x[0]=20;y[0]=30;}
        static void glfwGetWindowSize(long window,int[] w,int[] h) {w[0]=854;h[0]=480;}
        static void glfwSetWindowAttrib(long window,int type,int value) {}
        static void glfwSetWindowSize(long window,int w,int h) {}
        static void glfwSetWindowPos(long window,int x,int y) {}
    }
    static class WindowsOverlay {
        static int prepares,shows;
        static boolean hostFound=true,lastActive,lastHidden;
        static boolean hostAvailable(int pid) {return hostFound;}
        static boolean prepareHostForOverlay(int pid) {prepares++;return hostFound;}
        static boolean showAboveHost(long window,int pid) {
            shows++;events.add("overlay-focus");
            GLFW.glfwRestoreWindow(window);GLFW.glfwShowWindow(window);GLFW.glfwFocusWindow(window);return true;
        }
        static void updateCursor(Minecraft mc) {}
        static void update(long window,boolean active,boolean hidden,int pid) {
            lastActive=active;lastHidden=active&&hidden;events.add("windows-update");
        }
    }
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
'''
for signature in ['public static void onFrame(Minecraft mc)',
                  'private static boolean readyToFocus(Minecraft mc, boolean hostBusyNow)',
                  'private static void setActive(Minecraft mc, boolean on)',
                  'private static void follow(int x, int y, int w, int h)',
                  'public static void switchToHost(Minecraft mc)',
                  'public static void switchToMc(Minecraft mc)']:
    java += method(signature) + '\n'
java += r'''
    public static void main(String[] args) {
        Minecraft mc=new Minecraft();onFrame(mc);
        check(WindowsOverlay.prepares==1 && !active,"Restore the minimized host before it publishes frames");
        mc.level=new Minecraft.Level();mc.player=new Minecraft.Player();
        ErLink.alive=true;onFrame(mc);
        check(active && busy && WindowsOverlay.lastHidden,"Keep the void world transparent while the save loads");
        check(GLFW.visible && GLFW.focuses==0 && TerrainManager.recalls==1,"Keep Minecraft present without taking focus from the save menu");
        lastInWorldMs-=10000;lastWantMs-=10000;ErLink.alive=false;events.clear();onFrame(mc);
        check(active && busy && WindowsOverlay.lastHidden && TerrainManager.recalls==1,
            "A long host stall must not expose the void world or restart recall");
        check(events.contains("camera-suspend"),"Do not drive the host from stale state during loading");
        ErLink.alive=true;STATE.flags|=Protocol.STATE_PLAYER_VALID;TerrainManager.settled=true;
        mc.level.chunk=true;mc.screen=new ReceivingLevelScreen();onFrame(mc);
        check(GLFW.focuses==0,"Wait for client terrain receipt");
        mc.screen=null;mc.level.chunk=false;onFrame(mc);check(GLFW.focuses==0,"Wait for the client's chunk");
        mc.level.chunk=true;mc.overlay=new Object();onFrame(mc);check(GLFW.focuses==0,"Wait for resource loading");
        mc.overlay=null;STATE.flags|=Protocol.STATE_HOST_BUSY;onFrame(mc);check(GLFW.focuses==0,"Wait for the host loading screen");
        STATE.flags&=~Protocol.STATE_HOST_BUSY;onFrame(mc);
        check(!busy && focusPending && GLFW.focuses==0,"A loaded void chunk is insufficient before compositing starts");
        FramePassthrough.compositing=true;GLFW.iconified=true;events.clear();onFrame(mc);
        check(active && !busy && !GLFW.iconified && GLFW.focuses==1,"Restore and focus only once the host draws Minecraft");
        check(events.indexOf("windows-update")<events.indexOf("overlay-focus"),"Apply owner and opacity before taking focus");
        for(int i=0;i<10;i++)onFrame(mc);
        check(GLFW.focuses==1 && WindowsOverlay.shows==1,"Alt+Tab must not trigger repeated focus");
        ErLink.alive=false;lastWantMs-=10000;lastInWorldMs-=10000;onFrame(mc);
        check(active && busy && TerrainManager.recalls==1,"Retain transparency after a stalled game frame");
        ErLink.alive=true;onFrame(mc);
        check(active && !busy && GLFW.focuses==1 && TerrainManager.recalls==1,"Resume without another recall or focus grab");
        events.clear();switchToHost(mc);onFrame(mc);
        check(!active && !GLFW.visible,"F8 intentionally hides Minecraft");
        check(events.indexOf("host-focus")<events.indexOf("hide-mc"),"Publish the F8 handoff before Windows focuses the owner");
        switchToMc(mc);onFrame(mc);
        check(active && busy && GLFW.visible && TerrainManager.recalls==2,"F8 return stays transparent until recall settles");
        TerrainManager.settled=true;onFrame(mc);check(!busy && !focusPending,"Resume F8 after recall and compositing");
        ErLink.alive=false;WindowsOverlay.hostFound=false;lastWantMs-=10000;onFrame(mc);
        check(!active && !WindowsOverlay.lastActive,"Restore normal Minecraft when the host actually exits");
        System.out.println("PASS: save loading, long host stalls, compositing readiness, Alt+Tab, F8 and host exit");
    }
}
'''
(OUT / 'OverlayLoadingTest.java').write_text(java, encoding='utf-8')
jdk = next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent
subprocess.run([str(jdk / 'javac.exe'), '-d', str(OUT), str(OUT / 'OverlayLoadingTest.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'OverlayLoadingTest'], check=True)

# Exercise the actual Windows transparency cache too: busy -> compositing leaves
# hiddenContent true, but must change mouse routing from pass-through to Minecraft.
windows = (ROOT / 'bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/WindowsOverlay.java').read_text(encoding='utf-8')
window_java = r'''
public class WindowInputTest {
    static boolean perPixel,inputOnly,inputPassThrough,initialized;
    static final float INPUT_OPACITY=1.0F/255.0F;
    static class Logger {void info(String message,Object... args) {}}
    static final Logger LOG=new Logger();
    static class Pointer {static Object createConstant(long value) {return value;}}
    static class HWND {HWND(Object value) {}}
    static class GLFWNativeWin32 {static long glfwGetWin32Window(long window) {return window;}}
    static class GLFW {
        static float opacity=1.0F;
        static int updates;
        static void glfwSetWindowOpacity(long window,float value) {opacity=value;updates++;}
    }
    static class Overlay {static boolean busy=true;static boolean hostBusy() {return busy;}}
    static void updateOwner(long window,boolean active,int pid) {}
    static void updateTaskbarButton(HWND hwnd,boolean active) {}
    static void updateTaskbar(long window,boolean active) {}
    static void setFramebufferTransparency(long window,boolean value) {}
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
'''
window_java += method('public static void update(long window, boolean active, boolean hiddenContent, int hostProcessId)', windows)
window_java += r'''
    public static void main(String[] args) {
        update(7,true,true,8);
        check(GLFW.opacity==0.0F && inputPassThrough,"Let save-menu clicks reach Elden Ring");
        Overlay.busy=false;update(7,true,true,8);
        check(GLFW.opacity==INPUT_OPACITY && !inputPassThrough,"Restore Minecraft input when compositing starts");
        int updates=GLFW.updates;
        for(int i=0;i<1000;i++)update(7,true,true,8);
        check(GLFW.updates==updates,"Unchanged opacity must remain cached");
        Overlay.busy=true;update(7,true,true,8);
        check(GLFW.opacity==0.0F,"Resume click-through on another host loading screen");
        update(7,false,false,8);
        check(GLFW.opacity==1.0F && !inputPassThrough && !perPixel,"Restore the ordinary window on overlay exit");
        Overlay.busy=false;update(7,true,false,8);
        check(GLFW.opacity==1.0F && perPixel && !inputPassThrough,"Keep per-pixel fallback input enabled");
        System.out.println("PASS: host-menu click-through, gameplay input, opacity cache and normal-window restoration");
    }
}
'''
(OUT / 'WindowInputTest.java').write_text(window_java, encoding='utf-8')
subprocess.run([str(jdk / 'javac.exe'), '-d', str(OUT), str(OUT / 'WindowInputTest.java')], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'WindowInputTest'], check=True)
