# Third-party notices

Minecraft Ring is a Windows adaptation of the Elden Ring portion of
[minecraft-crossover-bridge](https://github.com/justbustin/minecraft-crossover-bridge)
by justbustin. The original MIT notice is preserved in
[bridge-base/LICENSE](bridge-base/LICENSE).

[MinHook](https://github.com/TsudaKageyu/minhook) is bundled as source under
`bridge-base/elden-ring/er-bridge/third_party/minhook/`. Its license, including
Hacker Disassembler Engine notices, is preserved in
[LICENSE.txt](bridge-base/elden-ring/er-bridge/third_party/minhook/LICENSE.txt).
Preserve these notices when distributing compiled bridge DLLs as well.

The Gradle wrapper is build infrastructure distributed under the
[Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).
Minecraft, Fabric Loader, Fabric API and other downloaded dependencies retain
their respective licenses.

The Windows overlay is managed by the bridge itself. Borderless Fullscreen and
b100lib, used in an earlier local setup, are no longer required or bundled.
Downloaded LLVM-MinGW, JDK and Gradle toolchains are excluded from this repository
and retain their own licenses.

[SkyCraft](https://github.com/chasmlol/SkyCraft) and
[ArkWeb](https://github.com/luki-1/ArkWeb) were studied as architectural references.
Their reference checkouts are excluded from this repository.

Game installations, save files, downloaded toolchains and executable dumps are
not included. Game names and assets belong to their respective owners. The MIT
license covers the bridge code; it does not grant rights to either game.
