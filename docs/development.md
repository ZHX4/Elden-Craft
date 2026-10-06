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

`TerrainManager` uses the terrain generation and movement path from `ffb31b5`.
Prefetch covers 12–24 metres ahead and an eight-metre radius around the predicted
position. Nearby passage refinement uses 16x16 horizontal cells with 16 vertical
layers. Immediate footing takes priority; local refinement alternates with
broader coarse generation. The native ray
mailbox retains its 2 ms frame budget; ten-chunk preloading and continuous
16x16x16 refinement are disabled.

Minecraft resolves movement against generated block collision on both client
and server. Additional native colliders, wall projection and landing position
corrections are disabled. Saved shapes remain available during chunk loading;
unknown detail shapes retain solid support until their packets arrive. The
cached shape builder reads both saved 8x8 and 16x16 data. Passage refinement
preserves saved fine cells; lift updates retain their conservative 8x8 view. Player-built
blocks are preserved, and door invalidation rejects older pending queries.

Passage refinement discovers blocking terrain in the player's next 1.5 metres,
including ordinary floor slabs, independent of coarse obstacle classification.
It measures five floor points per 1/16-block cell and checks local headroom and
bidirectional wall diagonals. It follows descending stairs and clears verified
air up to the real ceiling within four metres of each floor, leaving step and
jump room. A 1,280-ray floor batch is followed by up to four 1,088-ray local
batches. Each result updates only its measured fine cells; cells are never
downsampled between partial batches. The existing native frame budget is
unchanged. Player-origin floor/headroom and remote visibility cannot veto
independently verified local clearance. Real low ceilings remain ceiling
surfaces instead of turning the whole column into a wall.

Verified air is cached even when its world block becomes air and has no block
entity. Coarse obstacle classification changes reuse that measured shape;
explicit door/prop invalidation removes it before new local sampling. Body
batches start with cells nearest the player, and each batch gets its own timeout
so a slow multi-batch refinement does not discard its final results.

`TerrainTravelClearance` provides an urgent lane in the same mailbox. It checks
overlapping body-sized patches for four metres along movement and for 1.5 metres
in every direction around the player. One 160-ray floor batch and one body batch
of at most 1,696 rays check support, ceilings and bidirectional wall segments.
Only verified air inside the patches is removed; support and player blocks stay.
Detailed columns yield between parts so this lane can run before arrival,
alternating with background generation. Diagnostics include wave duration and
the number of patches with verified clearance.
Steeper patches retain per-cell refinement instead of becoming raised flat
platforms; that refinement starts at already measured higher ground, allowing
upcoming slopes to be prepared while the player is still below them.

Native support tracking runs each game frame independently of terrain work.
A separate nearby platform table is sampled every 40 ms in half-metre cells
within three metres of the player. A floor qualifies by moving at the same
world point, with footprint and body-clearance checks. The Minecraft side
predicts travel, updates verified floor cells and carries grounded players;
jumping detaches the passenger while tracking continues for landing. Flight
or lost support ends the ride. Sloped-floor and edge handling remain subjects
for gameplay testing.

## Regression checks

`/weather thunder` uses native WindyRain only. The bridge adds no lightning or
thunder audio and loads no additional storm resources; this requires no DLC.

Run from the repository root after preparing the toolchains. These checks
exercise collision, movement and capture behavior without launching the games:

| Command | Coverage |
| --- | --- |
| `python tools/test_stable_terrain.py` | Active block collision path and saved 8x8/16x16 compatibility |
| `python tools/test_terrain_passages.py` | Two-stage passage refinement, descending stairs, low vaults, wall retention and door invalidation |
| `python tools/test_terrain_clearance.py` | Narrow arches, corner and one-sided walls, stale openings and floor safety |
| `python tools/test_terrain_prefetch.py` | Predictive sampling, stale geometry and stairs |
| `python tools/test_frame_switch.py` | F8 key handoff, delayed GL completion, GPU acknowledgements and resumed capture |
| `python tools/test_moving_platform.py` | Lift travel, delayed ticks, floor/ceiling aliasing, static ground and lost support |
| `python tools/test_terrain_regeneration.py` | Saved collision and detail retained across startup and chunk reloads |
| `python tools/test_platform_boarding.py` | Boarding before contact, slopes, platform edges, intact walls and departed floors |
| `python tools/test_player_facing.py` | Native player headings and both sides of a slanted doorway |
| `python tools/test_crash_recorder.py` | Repeated faults, context and minidump output, unchanged exception handling |
| `python tools/test_world_environment.py` | Weather/time commands, smooth native transitions and scripted overrides |

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
Local reverse-engineering helpers that depend on the unpublished `erctl` module
are also excluded; published diagnostics are self-contained. Memory extracts,
game saves, local databases and credential directories must stay out of Git.

The Windows Python builder is the supported native build entry point here.
The native Makefile is retained from the cross-compilation workflow; the
original macOS/CrossOver launcher scripts are not part of this Windows package.
