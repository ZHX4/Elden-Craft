# Minecraft Ring

At the moment, there are some bugs; they will be fixed in the near future.

There are bugs with F8, and there may be bugs with overlaying the Minecraft window.

Minecraft building and movement in the Lands Between.

Minecraft Ring runs Minecraft alongside Elden Ring and brings Minecraft's player,
blocks and inventory into Elden Ring's view. Minecraft drives movement and block
interaction; Elden Ring supplies the world, enemies and native interactions.
The two games share player and camera state, terrain information and rendered
frames through a native bridge and a Fabric mod.

**Status: experimental Windows port.** Local play has been tested, including
block occlusion and breaking Elden Ring props. Distant areas and transitions
still need wider testing; this is a research mod with rough edges.

## What you can do

- Move, jump and look around with Minecraft controls.
- Place and break Minecraft blocks in Elden Ring's world.
- Use Minecraft's inventory and switch between first and third person.
- Interact with Elden Ring doors, items and Sites of Grace.
- Switch control back to Elden Ring with F8.

## How the bridge works

| Component | Role |
| --- | --- |
| `er-bridge/src/` | Native Windows loader and D3D12 bridge: host state, camera, terrain and compositing. |
| `mc-bridge/src/` | Fabric mod: Minecraft player, terrain collision, block interaction and frame capture. |
| `er-bridge/include/bridge_protocol.h` | Shared protocol used to exchange state between the games. |
| `Launch.ps1` | Starts Elden Ring, waits for the bridge, then launches Minecraft. |
| `tools/` | Native build script and diagnostics for the local bridge. |

Native and Fabric sources live under `bridge-base/elden-ring/`. On supported
hardware, frames use shared OpenGL/D3D12 GPU textures; a memory transfer path is
available as a fallback. Borderless rendering supports frame sizes up to
3840 x 2160. Both games run at the same time, so performance depends on the
combined CPU, GPU and memory load.

## Requirements

- Windows x64 and hardware capable of running both games simultaneously.
- Your own copies of Elden Ring and Minecraft Java Edition.
- Elden Ring executable version `2.7.1.0` / game version `1.17.1`, the version
  targeted by this port. Other builds have not been verified.
- Minecraft `1.21.1`; Fabric versions are pinned in `mc-bridge/gradle.properties`.
- Python 3 and LLVM-MinGW for the native build.
- JDK 25 for Gradle, plus a JDK 21 toolchain for Minecraft.
- Gradle `9.7.1` for the current Windows launcher.
- Borderless Fullscreen `2.4.1` and b100lib `0.2.2` for Minecraft `1.21.1`.

Use the bridge for offline single-player play. The launcher starts
`eldenring.exe` directly, and the loader requires its launcher marker and refuses
to load while Easy Anti-Cheat is present.

## Build and install

This repository contains source, not a ready-to-play game bundle. Downloaded
runtimes, compiled DLLs, game files and saves are deliberately excluded.

1. Clone the repository:

   ```powershell
   git clone https://github.com/siddoff/Minecraft-Ring.git
   cd Minecraft-Ring
   ```

2. Prepare the layout expected by the current build scripts:

   ```text
   .tools/
     compiler/<llvm-mingw-folder>/bin/clang++.exe
     java/<jdk-25-folder>/bin/java.exe
     gradle/<gradle-folder>/bin/gradle.bat
     mods/BorderlessFullscreen-v2.4.1-mc1.21.1.jar
     mods/b100lib-0.2.2-1.21.1.jar
   ```

   Install JDK 21 where Gradle can discover it. Only put JDK 25 in `.tools/java/`,
   because the launcher selects the first directory there. Toolchains and mod
   dependencies must be obtained separately; they are not uploaded to GitHub.

3. Build the native bridge from the repository root:

   ```powershell
   python tools/build_native.py
   ```

   This creates `dist/dinput8.dll` and `dist/erbridge_core.dll`. The launcher
   builds the Fabric mod with `remapJar` before `runClient`. For a standalone
   Fabric build with JDK 25 configured:

   ```powershell
   .\bridge-base\elden-ring\mc-bridge\gradlew.bat -p .\bridge-base\elden-ring\mc-bridge build
   ```

4. Close Elden Ring and install into your own game directory:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\Install.ps1 -GameDir 'D:\SteamLibrary\steamapps\common\ELDEN RING\Game'
   ```

   Replace the example path with your actual `Game` directory. The installer
   backs up Elden Ring saves and moves recognized existing mod loaders into
   `backups/` before installing the bridge. Local paths are recorded in the
   ignored `installation.json`.

5. For borderless play, create
   `bridge-base/elden-ring/mc-bridge/run/config/fullscreenfix.properties` with:

   ```properties
   exclusiveFullscreen:false
   autoMinimize:false
   startInFullscreen:true
   ```

   Enable fullscreen in Minecraft's video settings. Local settings and worlds
   remain outside version control.

## Playing

Double-click **start.bat** (or **Play.bat**). Wait for the native bridge to load,
then choose **Continue** in Elden Ring. Minecraft starts in its separate bridge
world and connects to the host character.

| Input | Action |
| --- | --- |
| WASD, mouse, Space | Minecraft movement, view and jump |
| F5 | First-person / third-person view |
| E | Minecraft inventory |
| Left / right mouse button | Attack or break / use or place a block |
| R | Elden Ring interaction: doors, items, Sites of Grace |
| F8 | Switch control between Elden Ring and Minecraft |
| T | Minecraft chat and commands |

The initial mode is creative. Use `/gamemode survival` for survival or
`/gamemode creative` to switch back. Spectator mode bypasses normal collisions.
Left-click also forwards attacks to breakable Elden Ring props.

## Diagnostics and removal

- `python tools/status.py` reports the bridge processes and current state.
- Native logs are written to `runtime/er-bridge.log`.
- Minecraft logs are under `bridge-base/elden-ring/mc-bridge/run/logs/`.
- With Elden Ring closed, run `Restore.ps1` to disable the bridge and restore
  previously moved mod files. Save backups are not automatically restored.

Some diagnostic tools operate on live bridge memory; inspect the script before
using it during play. `tools/screenshot.py` requires Pillow.

## Current limitations

- Addresses and hooks are tied to the targeted Elden Ring build.
- Distant terrain, area transitions and edge cases need more testing.
- Rendering and GPU sharing depend on the driver; the fallback can cost more CPU.
- Both games must stay running, with enough resources for each.
- The launcher currently expects the local tool layout shown above.
- This repository does not include a packaged release or an automatic setup tool.

## Repository contents

| Path | Contents |
| --- | --- |
| `bridge-base/elden-ring/er-bridge/` | Native bridge, protocol and MinHook source |
| `bridge-base/elden-ring/mc-bridge/` | Fabric sources and Gradle wrapper |
| `tools/` | Build and diagnostic scripts |
| `Install.ps1`, `Launch.ps1`, `Restore.ps1` | Windows setup, launch and removal |
| `LICENSE`, `THIRD_PARTY_NOTICES.md` | Project license and third-party attribution |

`.gitignore` excludes files by default and explicitly allows project files.
Downloaded `.tools/`, `references/`, `backups/`, `runtime/`, `build/`, `dist/`,
installation records, Minecraft worlds, caches and logs are excluded. Unrelated
upstream Monster Hunter code and the nested upstream Git history are excluded.
New top-level project files need an explicit allow rule.

## Credits and license

Based on [minecraft-crossover-bridge](https://github.com/justbustin/minecraft-crossover-bridge)
by justbustin, with Windows adaptation and integration by siddoff.
[SkyCraft](https://github.com/chasmlol/SkyCraft) and
[ArkWeb](https://github.com/luki-1/ArkWeb) informed the architectural research.

The bridge code is available under the [MIT License](LICENSE). See
[third-party notices](THIRD_PARTY_NOTICES.md) for bundled code and dependencies.

An unofficial fan project, unaffiliated with Mojang, Microsoft, FromSoftware
or Bandai Namco. No game installations or save files are distributed here.
