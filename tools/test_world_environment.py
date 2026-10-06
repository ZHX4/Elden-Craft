"""Exercise the native world-command service with deterministic host/transport state."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
OUT = ROOT / 'build/world-environment-test'
OUT.mkdir(parents=True, exist_ok=True)
CXX = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
game = (ER / 'src/game.cpp').read_text(encoding='utf-8')
start = game.index('static bool g_environmentOk')
end = game.index('// Player and coordinate frame', start)
service = game[start:end]
service = service.replace('((RequestWorldTime)(g_base + 0x644EF0))', 'request_time')
service = service.replace('((RequestWorldWeather)(g_base + 0x6480C0))', 'request_weather')
service = service.replace('((RequestRegionalWeather)(g_base + 0x6481B0))', 'request_regional')
source = r'''
#include "bridge_protocol.h"
#include "world_environment.h"
#include <windows.h>
#include <cstring>
#include <cmath>
#include <cassert>
#include <cstdio>
using namespace mb;
static uint8_t shared[ERMC_SHM_SIZE], timeObject[0x100], weatherObject[0x220];
static uint8_t* timePointer = timeObject;
static uint8_t* weatherPointer = weatherObject;
static uint64_t clockMs = 100;
static uintptr_t g_base = 0;
static uint16_t nativeClockHold;
static int regionalCalls;
static bool acceptWeather = true;
static int timeCalls, weatherCalls, weatherType;
static uint32_t requestedSeconds;
static ErmcHeader* shm_header() { return (ErmcHeader*)shared; }
static uint64_t now_ms() { return clockMs; }
static uint8_t* global_ptr(uintptr_t rva, uintptr_t) {
    if (rva == 0x3D6D368) return timePointer;
    return weatherPointer;
}
static bool mem_readable(void* p, size_t) { return p != nullptr; }
static void request_time(void*, uint32_t h, uint32_t m, uint32_t s) {
    ++timeCalls; requestedSeconds = h * 3600 + m * 60 + s;
    *(uint64_t*)(timeObject + 8) = ((uint64_t)h << 34) | ((uint64_t)m << 39) | ((uint64_t)s << 45);
}
struct EnvironmentWeatherRequest;
static bool request_weather(void*, const EnvironmentWeatherRequest*);
static bool request_regional(void*, const uint32_t* block, bool) {
    ++regionalCalls; assert(*block >> 24 > 0);
    assert(*(int16_t*)(weatherObject + 0x2A) == -1); return true;
}
static bool mem_read(void*, void* out, size_t n) {
    assert(n == sizeof(nativeClockHold)); memcpy(out, &nativeClockHold, n); return true;
}
''' + service + r'''
static bool request_weather(void* object, const EnvironmentWeatherRequest* request) {
    assert(request->area > 0 && request->seconds == -1 && !request->scripted && !request->immediate);
    if (!acceptWeather) return false;
    ++weatherCalls; weatherType = request->kind;
    memcpy(object, request, sizeof(*request));
    return true;
}
int main() {
    static_assert(sizeof(ErmcEnvironment) == 24);
    static_assert(ERMC_OFF_HUNTER + sizeof(ErmcHunterEvents) <= ERMC_OFF_ENVIRONMENT);
    assert(environment_seconds(0) == 21600);
    assert(environment_seconds(1000) == 25200);
    assert(environment_seconds(6000) == 43200);
    assert(environment_seconds(12000) == 64800);
    assert(environment_seconds(13000) == 68400);
    assert(environment_seconds(18000) == 0);
    assert(environment_seconds(23999) == 21596);
    assert(environment_weather(0) == 1);
    assert(environment_weather(1) == 20);
    assert(environment_weather(2) == 30);
    g_environmentOk = true;
    *(float*)(timeObject + 0x38) = 2.5f;
    *(int16_t*)(weatherObject + 0x21C) = -1;
    *(int8_t*)(weatherObject + 0x20F) = -1;
    weatherObject[0xF7] = 1; // ordinary initialized regional weather
    auto& env = *(ErmcEnvironment*)(shared + ERMC_OFF_ENVIRONMENT);
    env.seq = 2; env.flags = ERMC_ENV_TIME | ERMC_ENV_WEATHER;
    env.dayTicks = 1000; env.weather = 1;
    uint32_t zone = 60u << 24;
    service_environment(true, zone);
    assert(timeCalls == 0 && weatherCalls == 0); // stale startup mailbox
    shm_header()->mcHeartbeat++;
    service_environment(true, zone);
    assert(timeCalls == 1 && weatherCalls == 1 && weatherType == 20);
    assert(requestedSeconds == 25200 && *(float*)(timeObject + 0x38) == 0);
    assert(*(int16_t*)(weatherObject + 0x21C) == -1 && !weatherObject[0xF9]);
    assert(*(int8_t*)(weatherObject + 0x20F) == 1);
    service_environment(true, zone);
    assert(timeCalls == 1 && weatherCalls == 1);
    env.timeRevision++; env.weatherRevision++;
    service_environment(true, zone);
    assert(timeCalls == 2 && weatherCalls == 1); // same weather never restarts blending
    env.dayTicks = 6000; env.weather = 2;
    service_environment(true, zone);
    assert(requestedSeconds == 43200 && weatherType == 30);
    // Ordinary current weather and a transition's queued target need no refresh.
    int initialWeatherCalls = weatherCalls;
    memset(weatherObject, 0, 12);
    *(int16_t*)(weatherObject + 0x2A) = 30;
    env.weatherRevision++;
    service_environment(true, zone);
    assert(weatherCalls == initialWeatherCalls);
    // Native scenario owners stop BOTH channels, even while new commands arrive.
    for (int mode = 0; mode < 7; ++mode) {
        if (mode == 0) weatherObject[0xF5] = 1;
        if (mode == 1) weatherObject[0xF9] = 1;
        if (mode == 2) weatherObject[0x91] = 1;
        if (mode == 3) *(int16_t*)(weatherObject + 0x21C) = 81;
        if (mode == 4) timeObject[0x45] = 1;
        if (mode == 5) *(int32_t*)(timeObject + 0x40) = 1;
        if (mode == 6) nativeClockHold = 0x100;
        int tc = timeCalls, wc = weatherCalls, rc = regionalCalls;
        env.dayTicks = (env.dayTicks + 1000) % 24000; env.weatherRevision++;
        // A pending native weather request must survive releasing the bridge.
        EnvironmentWeatherRequest script = {60, 81, 999, 1, 1, 0};
        memcpy(weatherObject + 12, &script, sizeof(script));
        service_environment(true, zone);
        assert(timeCalls == tc && weatherCalls == wc && regionalCalls == rc + (mode >= 4 ? 1 : 0));
        assert(*(float*)(timeObject + 0x38) == 2.5f);
        assert(*(int8_t*)(weatherObject + 0x20F) == -1);
        assert(memcmp(weatherObject + 12, &script, sizeof(script)) == 0);
        service_environment(true, zone);
        assert(timeCalls == tc && weatherCalls == wc);
        weatherObject[0xF5] = weatherObject[0xF9] = weatherObject[0x91] = 0;
        *(int16_t*)(weatherObject + 0x21C) = -1;
        timeObject[0x45] = 0; *(int32_t*)(timeObject + 0x40) = 0; nativeClockHold = 0;
        service_environment(true, zone);
        assert(timeCalls == tc + 1 && weatherCalls == wc + 1);
        assert(weatherType == 30 && *(float*)(timeObject + 0x38) == 0);
        assert(*(int8_t*)(weatherObject + 0x20F) == 1);
    }
    env.weather = 0;
    service_environment(true, zone);
    assert(weatherType == 1);
    int wc = weatherCalls;
    zone = 61u << 24;
    service_environment(true, zone);
    assert(weatherCalls == wc + 1);
    env.seq++; env.dayTicks = 18000;
    uint32_t oldSeconds = requestedSeconds;
    service_environment(true, zone);
    assert(requestedSeconds == oldSeconds);
    env.seq++;
    service_environment(true, zone);
    assert(requestedSeconds == 0);
    *(uint64_t*)(timeObject + 8) = (uint64_t)12 << 34;
    clockMs += 1000; shm_header()->mcHeartbeat++;
    int tc = timeCalls;
    service_environment(true, zone);
    assert(timeCalls == tc + 1 && requestedSeconds == 0);
    // A torn mailbox cannot prevent restoring native control on disconnect.
    env.seq++; clockMs += 2001;
    int rc = regionalCalls;
    service_environment(true, zone);
    assert(regionalCalls == rc + 1 && *(float*)(timeObject + 0x38) == 2.5f);
    assert(*(int8_t*)(weatherObject + 0x20F) == -1);
    assert(*(int16_t*)(weatherObject + 0x21C) == -1 && !weatherObject[0xF9]);
    env.seq++; shm_header()->mcHeartbeat++;
    service_environment(true, zone);
    assert(*(float*)(timeObject + 0x38) == 0);
    // Preserve fields changed by a native script or another mod after acquisition.
    *(float*)(timeObject + 0x38) = 4.0f;
    *(int8_t*)(weatherObject + 0x20F) = 0;
    weatherObject[0xF9] = 1;
    rc = regionalCalls;
    service_environment(true, zone);
    assert(*(float*)(timeObject + 0x38) == 4 && *(int8_t*)(weatherObject + 0x20F) == 0);
    assert(regionalCalls == rc && weatherObject[0xF9]);
    weatherObject[0xF9] = 0;
    *(int8_t*)(weatherObject + 0x20F) = -1;
    // A rejected native weather request never leaves an unowned outdoor override.
    acceptWeather = false;
    service_environment(true, zone);
    assert(*(int8_t*)(weatherObject + 0x20F) == -1);
    acceptWeather = true;
    service_environment(true, zone);
    env.flags = ERMC_ENV_WEATHER;
    service_environment(true, zone);
    assert(*(float*)(timeObject + 0x38) == 4);
    env.flags = ERMC_ENV_TIME;
    service_environment(true, zone);
    assert(*(int8_t*)(weatherObject + 0x20F) == -1);
    service_environment(false, zone);
    timePointer = nullptr; weatherPointer = nullptr;
    service_environment(true, zone);
    timePointer = timeObject; weatherPointer = weatherObject;
    env.flags = 3;
    service_environment(true, zone);
    env.flags = 0;
    service_environment(true, zone);
    assert(*(float*)(timeObject + 0x38) == 4);
    std::puts("PASS: smooth non-expiring weather, WindyRain for thunder, scenario priority, regions, seqlock and ownership release");
}
'''
cpp = OUT / 'test.cpp'
cpp.write_text(source, encoding='utf-8')
exe = OUT / 'test.exe'
subprocess.run([str(CXX), '-std=c++17', '-static', '-I' + str(ER / 'include'),
                str(cpp), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)

# Exercise publication of vanilla weather flags, durations and time revisions.
JAVA = next((ROOT / '.tools/java').glob('*/bin/java.exe'))
publisher = (ER.parent / 'mc-bridge/src/main/java/dev/ermc/bridge/WorldEnvironmentBridge.java').read_text(encoding='utf-8')
publisher = '\n'.join(line for line in publisher.splitlines()
                      if not line.startswith(('package ', 'import ')))
(OUT / 'WorldEnvironmentBridge.java').write_text(publisher, encoding='utf-8')
mixin = (ER.parent / 'mc-bridge/src/main/java/dev/ermc/bridge/mixin/ServerWeatherMixin.java').read_text(encoding='utf-8')
mixin = '\n'.join(line for line in mixin.splitlines()
                  if not line.strip().startswith(('package ', 'import ', '@Mixin', '@Redirect', 'target = ')))
(OUT / 'ServerWeatherMixin.java').write_text(mixin, encoding='utf-8')
java = r'''
import java.util.*;
class Protocol { static final int ENV_TIME = 1, ENV_WEATHER = 2; }
class TerrainManager { static boolean bridge = true; static boolean isBridgeWorld() { return bridge; } }
class ErLink {
    static final ErLink INSTANCE = new ErLink();
    int flags, time, weather; boolean connected = true;
    static ErLink get() { return INSTANCE; }
    boolean alive() { return connected; }
    void writeEnvironment(int f, int tr, int t, int wr, int w) { flags=f; time=t; weather=w; }
}
class LevelData {
    boolean rain, thunder;
    boolean isThundering() { return thunder; } boolean isRaining() { return rain; }
}
class ServerLevel {
    long day = 18000;
    LevelData data = new LevelData();
    long getDayTime() { return day; }
    LevelData getLevelData() { return data; }
    boolean isThundering() { return data.thunder; }
}
class MinecraftServer {
    ServerLevel world = new ServerLevel(); ServerLevel overworld() { return world; }
}
record CommandSourceStack(MinecraftServer server, ServerLevel level) {
    MinecraftServer getServer() { return server; } ServerLevel getLevel() { return level; }
}
public class WorldEnvironmentTest {
    public static void main(String[] args) throws Exception {
        MinecraftServer s = new MinecraftServer(); ErLink link = ErLink.get();
        TerrainManager.bridge=false;
        WorldEnvironmentBridge.onServerStarted(s);
        assert link.flags == 0;
        TerrainManager.bridge=true; s.world.day=25000;
        WorldEnvironmentBridge.onServerStarted(s);
        assert link.flags == 3 && link.time == 1000 && link.weather == 0;
        s.world.data.rain=true;
        WorldEnvironmentBridge.weatherChanged(new CommandSourceStack(s, s.world));
        assert link.weather == 1;
        s.world.data.thunder=true;
        WorldEnvironmentBridge.weatherChanged(new CommandSourceStack(s, s.world));
        assert link.weather == 2;
        var weatherTick = ServerWeatherMixin.class.getDeclaredMethod("erbridge$nativeThunderOnly", ServerLevel.class);
        weatherTick.setAccessible(true);
        ServerWeatherMixin weatherMixin = new ServerWeatherMixin() {};
        assert !(boolean) weatherTick.invoke(weatherMixin, s.world);
        TerrainManager.bridge=false;
        assert (boolean) weatherTick.invoke(weatherMixin, s.world);
        TerrainManager.bridge=true;
        WorldEnvironmentBridge.onServerTick(s);
        assert link.weather == 2;
        s.world.data.rain=false; s.world.data.thunder=false;
        WorldEnvironmentBridge.onServerTick(s);
        assert link.weather == 0;
        s.world.day=-1;
        WorldEnvironmentBridge.timeChanged(new CommandSourceStack(s, s.world));
        assert link.time == 23999;
        WorldEnvironmentBridge.reset(s);
        assert link.flags == 0;
        System.out.println("PASS: startup synchronization, weather publication, bridge-only vanilla lightning suppression, clock wrap and disconnect");
    }
}
'''
(OUT / 'WorldEnvironmentTest.java').write_text(java, encoding='utf-8')
subprocess.run([str(JAVA.with_name('javac.exe')), '-d', str(OUT),
                str(OUT / 'WorldEnvironmentBridge.java'), str(OUT / 'ServerWeatherMixin.java'), str(OUT / 'WorldEnvironmentTest.java')], check=True)
subprocess.run([str(JAVA), '-ea', '-cp', str(OUT), 'WorldEnvironmentTest'], check=True)
