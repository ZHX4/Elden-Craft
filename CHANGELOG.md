# Changelog

## 0.3.0 — 2026-10-06

### Terrain collision

- Improved terrain collision and reduced generation overhead.
- Added predictive refinement of doorways, stairs and narrow passages while
  preserving real walls, floors, ceilings and previously verified clearance.

### World commands

- Synchronized Minecraft weather and time commands with Elden Ring.
- Preserved world settings and native scripted weather, with consistent rain
  in bridge worlds.

### Windows overlay

- Improved overlay focus, Alt+Tab and F8 switching.
- Restored the native cursor in Minecraft menus and hid the taskbar during play.
- Kept loading screens transparent and recovered frame transfer after interruptions.

## 0.2.0 — 2026-10-03

Major update to the experimental Windows bridge.

### Movement and terrain

- Added native moving-support tracking, client travel prediction and moving
  floor collision for boarding lifts and platforms before passenger contact.
- Added handling for ascent, descent, delayed poses, jumping and landing,
  gently sloped platforms and partial contact at an edge.
- Added detailed terrain collision for narrow arches and angled passages,
  including corner, one-sided wall, body-clearance and floor-safety checks.
- Added predictive terrain prefetch and refresh of nearby stale samples.
- Preserved refined shapes across unchanged samples and invalidated pending
  terrain work after door changes.
- Checked native body collision before filling columns under overhead surfaces.
- Corrected the hidden Tarnished's facing, including assisted door interactions.

### Windows rendering and diagnostics

- Replaced external fullscreen-mod dependencies with bridge-managed borderless
  window layout and separate Windows input/transparency modes.
- Kept Minecraft windowed at launch and during overlay operation.
- Suspended camera publishing during host handoff and retired pending GPU
  captures while compositing is paused.
- Prevented a held F8 key from causing a second switch during focus handoff.
- Added native fault recording with context, thread stacks and minidumps.
- Added seven regression scripts covering terrain, platforms, facing,
  frame switching and crash recording.

### Project information

- Reworked the README with a gameplay image, feature overview, current controls,
  requirements and known limitations.
- Added Windows setup, update, removal and developer documentation.
- Updated Fabric project metadata and removed obsolete dependency notices.

## 0.1.0 — Initial Windows port

- Adapted the upstream Elden Ring bridge for Windows with native build,
  installation, launch and restoration scripts.
- Included Minecraft/Fabric integration, host compositing, terrain, interactions
  and combat, with project licenses and third-party notices.
