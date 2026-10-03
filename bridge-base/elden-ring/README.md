# Elden Ring bridge

Native and Fabric sources for [Minecraft Ring](../../README.md), the
experimental Windows x64 bridge for Elden Ring App Ver. 1.17.1
(`eldenring.exe` 2.7.1.0) and Minecraft Java Edition 1.21.1.

| Directory | Contents |
| --- | --- |
| `er-bridge/src/` | Windows loader, native game hooks, D3D12 compositor and crash recorder |
| `er-bridge/include/` | Shared protocol, game addresses and player-facing helpers |
| `er-bridge/third_party/minhook/` | Bundled MinHook source and its license |
| `mc-bridge/src/main/` | Terrain and platform collision, enemy proxies, combat, shared life and mod metadata |
| `mc-bridge/src/client/` | Camera synchronization, frame capture, overlay, input and automatic world opening |
| `mc-bridge/gradle/` | Gradle wrapper, pinned to 9.7.1 |

Build from the repository root with `python tools/build_native.py` and Gradle
`build`. Install with `Install.ps1`, launch with `start.bat` or `Play.bat`, and
disable the bridge with `Restore.ps1`. The loader activates only with the
launcher marker and without Easy Anti-Cheat.

[Windows setup](../../docs/installation.md) covers toolchains and game paths.
[Development and diagnostics](../../docs/development.md) covers protocol
synchronization, regression checks, logs and local worlds.
[Changelog](../../CHANGELOG.md) records the current 0.2.0 update.
