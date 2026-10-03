# Development and diagnostics

[Back to Minecraft Ring](../README.md) · [Build instructions](installation.md)

## Architecture

| Component | Responsibility |
| --- | --- |
| `bridge-base/elden-ring/er-bridge/src/proxy.cpp` | `dinput8.dll` loader; gates activation and loads the core |
| `er-bridge/src/game.cpp` | Player and camera hooks, host interactions, terrain rays and moving support |
| `er-bridge/src/compositor.cpp`, `gpu_transport.h` | D3D12 compositing, depth occlusion and GPU frame acknowledgements |
| `er-bridge/src/crash.cpp` | Fault context, thread stacks and minidumps |
| `mc-bridge/src/main/java/dev/ermc/bridge/` | Server-side terrain, collision shapes, moving platforms and shared life |
| `mc-bridge/src/main/java/dev/ermc/bridge/entity/` | Host enemy proxies and combat |
| `mc-bridge/src/client/java/dev/ermc/bridge/client/` | Camera, capture, Windows overlay, input and automatic bridge-world opening |
| `Launch.ps1` | Host startup, readiness check, window settings and Fabric development-client launch |

Paths abbreviated to `er-bridge/` and `mc-bridge/` above are relative to
`bridge-base/elden-ring/`.

## Protocol and rendering

`er-bridge/include/bridge_protocol.h` and
`mc-bridge/src/main/java/dev/ermc/bridge/link/Protocol.java` mirror the shared
layout. Keep them synchronized. The bridge uses file-backed mappings under
`ERMC_DIR`; the Windows launcher sets it to the project's `runtime/` directory.
Java can also override the directory with `-Derbridge.dir=<path>`.

State and control slots use sequence counters so readers can reject an update
in progress. Terrain queries, moving-platform cells, enemy state and combat
events have their own regions. Legacy `hunter` names refer to the Tarnished.
The frame transport uses separate files and supports four layers per frame
slot, at resolutions up to 3840 × 2160.

On supported drivers, OpenGL captures are exchanged as shared GPU textures
with D3D12. The memory path remains a fallback. Minecraft captures the camera
pose alongside each frame so host rendering can use the corresponding pose.
During F8 handoff, both sides retire pending captures and publications rather
than waiting for a fresh Minecraft frame to release old buffers.

## Terrain and moving platforms

`TerrainManager` schedules coarse native collision queries and predicts where
the player will move next. Prefetch covers 12–24 metres ahead and an eight-metre
radius around the predicted position; nearby samples are refreshed every two
seconds. `TerrainDetailManager` refines nearby collision into 1/8-block
horizontal cells after floor, body and head-clearance checks. Player-built
blocks are preserved. Door invalidation rejects batches submitted before the
change, and body checks prevent overhead surfaces from filling clear space.

Native support tracking runs each game frame independently of terrain work.
A separate nearby platform table is sampled every 40 ms in half-metre cells
within three metres of the player. A floor qualifies by moving at the same
world point, with footprint and body-clearance checks. The Minecraft side
predicts travel, updates verified floor cells and carries grounded players;
jumping detaches the passenger while tracking continues for landing. Flight
or lost support ends the ride. Sloped-floor and edge handling remain subjects
for gameplay testing.

## Regression checks

Run from the repository root after preparing the toolchains. These checks
exercise collision, movement and capture behavior without launching the games:

| Command | Coverage |
| --- | --- |
| `python tools/test_terrain_clearance.py` | Narrow arches, corner and one-sided walls, stale openings and floor safety |
| `python tools/test_terrain_prefetch.py` | Predictive sampling, stale geometry and stairs |
| `python tools/test_frame_switch.py` | F8 key handoff, delayed GL completion, GPU acknowledgements and resumed capture |
| `python tools/test_moving_platform.py` | Ascent, descent, delayed ticks, settling, jumps, flight and lost support |
| `python tools/test_platform_boarding.py` | Boarding before contact, slopes, platform edges, intact walls and departed floors |
| `python tools/test_player_facing.py` | Native player headings and both sides of a slanted doorway |
| `python tools/test_crash_recorder.py` | Repeated faults, context and minidump output, unchanged exception handling |

Build the native DLLs with `python tools/build_native.py`, and build the Fabric
mod with Gradle `build` as described in the [setup guide](installation.md#build).
The regression scripts generate isolated harnesses under `build/`; those files
and compiled DLLs stay out of Git. Automated checks do not establish that every
Elden Ring area, GPU driver or gameplay transition works.

## Logs and local data

| Path | Data |
| --- | --- |
| `runtime/er-bridge.log` | Native loader, hooks, rendering and bridge log |
| `runtime/eldenring-crash.txt` | Relevant fault context, registers and thread stacks |
| `runtime/eldenring-crash.dmp` | Native minidump |
| `bridge-base/elden-ring/mc-bridge/run/logs/latest.log` | Minecraft/Fabric log |
| `bridge-base/elden-ring/mc-bridge/run/saves/er-bridge/` | Local Minecraft bridge world and builds |
| `installation.json`, `backups/` | Installation record, prior mod files and Elden Ring save backups |

For a bug report, include the exact game/mod version, GPU and driver, location,
steps to reproduce and relevant logs. Describe whether it happens in creative
or survival mode, before or after F8, and with compositing enabled. Inspect a
crash dump before sharing it: it can contain process memory and local paths.

`tools/status.py` reads bridge state and checks whether its heartbeat advances.
`tools/screenshot.py` captures the visible host window and needs Pillow; it
writes `runtime/game.png`. Minecraft's own screenshot captures may contain only
the Minecraft layer when Elden Ring is compositing the final frame.

## Repository hygiene

The root `.gitignore` allows project sources, documentation and the selected
README screenshot explicitly. New top-level files require an allow rule.
Toolchains, caches, logs, crash dumps, runtime mappings, saves, installation
records, binaries, reference checkouts and unrelated upstream game modules
remain excluded.

The Windows Python builder is the supported native build entry point here.
The native Makefile is retained from the cross-compilation workflow; the
original macOS/CrossOver launcher scripts are not part of this Windows package.
