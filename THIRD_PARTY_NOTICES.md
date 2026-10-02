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

The local setup uses [Borderless Fullscreen](https://modrinth.com/mod/borderless-fullscreen)
2.4.1 and b100lib 0.2.2 for Minecraft 1.21.1. Their JARs and local reference
checkouts are excluded; obtain them from their authors.

[SkyCraft](https://github.com/chasmlol/SkyCraft) and
[ArkWeb](https://github.com/luki-1/ArkWeb) were studied as architectural references.
Their reference checkouts are excluded from this repository.

Game installations, save files, downloaded toolchains and executable dumps are
not included. Game names and assets belong to their respective owners. The MIT
license covers the bridge code; it does not grant rights to either game.
