# Standalone Windows launcher

This folder contains the source for Elden-Craft's optional portable launcher,
maintained as a Windows-focused fork of Minecraft Ring by siddoff.
The distributable archive is published separately as a GitHub Release asset;
it includes the two redistributable package archives next to these launcher
files. Elden Ring itself, Minecraft accounts, and player saves are never
included.

## Supported configuration

- Windows x64
- Elden Ring App Ver. **1.17.1** (`eldenring.exe` ProductVersion **2.7.1.0**)
- Minecraft Java Edition **1.21.1**
- Fabric Loader **0.19.5** and Fabric API **0.116.17+1.21.1**
- Either the bundled portable Prism instance or an existing TLauncher install

The exact executable build is checked before launch. Selecting a custom
installation path is supported, but only the exact listed build is enabled.
Elden Ring 1.17.0 (`2.7.0.0`), 1.17.1.1/other point releases and older builds
remain blocked until their own native profile and runtime test are complete.

## Install and play

1. Download the Windows launcher archive from Releases and extract the complete
   folder. Keep both `MinecraftRing-0.3.0.zip` and
   `MinecraftRing-Minecraft-0.3.0.zip` beside `Launcher.ps1`.
2. Double-click `Run Minecraft Ring.cmd` (the original launcher filename).
3. Browse to your owned `eldenring.exe`. The path may be outside Steam's default
   folder; its executable build must still match the enabled profile.
4. Select bundled Prism, or select Existing TLauncher, choose its executable
   and game directory, then click **Prepare TLauncher mods**.
5. Save and close open Minecraft worlds. Click **Launch offline** in this
   launcher. It requests a normal close of any detected Minecraft game window
   and stops if one does not close cleanly. It never force-stops Java or deletes
   `session.lock` or a world.
6. Wait for the launcher to open a fresh TLauncher child. Sign in yourself if
   needed, select `fabric-loader-0.19.5-1.21.1`, then press **Enter the game**
   once. Do not manually open a second Minecraft instance.

Leave the launcher open during play. TLauncher needs the `ERMC_DIR` environment
from this fresh child process so Minecraft and Elden Ring use the same bridge
shared-memory file. The launcher's logs and runtime files stay inside its
`runtime` folder. Do not share logs without checking them for local paths first.

## Compatibility and verification

The launcher enforces offline ME3 arguments and does not enable online
matchmaking. It will not activate the native bridge when Easy Anti-Cheat is
present. Do not use it for online play or with anti-cheat bypasses.

The package self-test passes on the development PC: it checks safe package
extraction, exact-version gating, launch argument construction, stale bridge
rejection, Minecraft-process detection, the installed Fabric profile, and
required mod SHA-256 values. This test does not start either game. Elden Ring
2.7.1.0 host startup has been observed locally; the complete TLauncher shared
memory connection, F8 round trip, and cross-game scene-depth acceptance test
are not yet verified. Treat this build as experimental until those checks pass.

## Build the portable archive

After building the two package archives, run this from the repository root:

```powershell
python --version  # Python 3.8 or newer
python tools/package_launcher.py `
  --main-archive .\dist\MinecraftRing-0.3.0.zip `
  --minecraft-archive .\dist\MinecraftRing-Minecraft-0.3.0.zip `
  --output .\dist\MinecraftRing-Windows-x64-0.3.0-preview.zip
```

The packager assembles already-built payload archives; it does not compile or
download them. It verifies required archive contents, rejects save/account/game
executable paths, enforces the exact enabled Elden Ring profile, and writes a
release manifest plus SHA-256 checksums. It packages only an explicit file
allowlist; runtime logs and the local `package/` extraction are not included.

Run the release-package self-test from the extracted launcher folder:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Launcher.ps1 -SelfTest
```

The TLauncher selection, account sign-in, and pressing **Enter the game** are
intentionally manual. The launcher never reads passwords, tokens, or session
files. Its Minecraft cleanup is graceful only; a game window that will not
close must be closed by the player before retrying.

## Credits and distribution

Elden-Craft is a derivative, unofficial fan project; it is not a clean-room
replacement for Minecraft Ring. This launcher adds custom-location selection,
an optional existing TLauncher workflow, graceful Minecraft-instance cleanup,
and checksummed packaging. TLauncher sign-in and profile launch are supported
as an optional path, but the complete bridge/F8 connection is still awaiting
end-to-end verification. The native bridge is based on
[`minecraft-crossover-bridge`](https://github.com/justbustin/minecraft-crossover-bridge)
by justbustin and Minecraft Ring by siddoff; Elden-Craft's Windows launcher
and this fork's changes are maintained in this repository.
The upstream package archives retain their license and third-party notices.
See the repository's [`LICENSE`](../LICENSE) and
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). No game executable or
player data is redistributed.

Suggested GitHub repository description:

> Elden-Craft is an experimental Windows fork of Minecraft Ring, with a portable launcher and optional TLauncher workflow (end-to-end testing pending).
