#!/usr/bin/env python3
"""Build a clean, checksummed portable Windows launcher archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "launcher"
REQUIRED_PAYLOADS = {
    "main": {
        "me3/bin/me3.exe",
        "minecraft-ring.me3",
        "bridge/erbridge_loader.dll",
        "bridge/erbridge/erbridge_core.dll",
    },
    "minecraft": {
        "Prism/prismlauncher.exe",
        "Prism/instances/MinecraftRing/.minecraft/mods/er-bridge-0.3.0.jar",
        "Prism/instances/MinecraftRing/.minecraft/mods/fabric-api-0.116.17+1.21.1.jar",
    },
}
FORBIDDEN_PARTS = {"saves", "accounts"}
FORBIDDEN_NAMES = {"eldenring.exe", "launcher_profiles.json", "session.lock"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_payload(path: Path, kind: str) -> None:
    if not path.is_file():
        raise ValueError(f"Payload archive does not exist: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            names = {item.filename.replace("\\", "/") for item in archive.infolist()}
            bad = []
            for name in names:
                parts = [part.lower() for part in name.split("/")]
                if any(part in FORBIDDEN_PARTS for part in parts):
                    bad.append(name)
                if parts and parts[-1] in FORBIDDEN_NAMES:
                    bad.append(name)
                if any(part == ".." for part in parts):
                    bad.append(name)
            if bad:
                raise ValueError(f"Unsafe or private paths in {path.name}: {', '.join(sorted(set(bad))[:8])}")
            missing = REQUIRED_PAYLOADS[kind] - names
            if missing:
                raise ValueError(f"{path.name} is missing expected files: {', '.join(sorted(missing))}")
            corrupt = archive.testzip()
            if corrupt:
                raise ValueError(f"Corrupt entry in {path.name}: {corrupt}")
    except zipfile.BadZipFile as error:
        raise ValueError(f"Invalid ZIP archive: {path}") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main-archive", type=Path, required=True, help="Built Elden-Craft/Minecraft Ring/ME3 archive")
    parser.add_argument("--minecraft-archive", type=Path, required=True, help="Built portable Prism/Fabric archive")
    parser.add_argument("--output", type=Path, required=True, help="Output .zip path")
    args = parser.parse_args()

    try:
        main_archive = Path(os.path.abspath(args.main_archive))
        minecraft_archive = Path(os.path.abspath(args.minecraft_archive))
        output = Path(os.path.abspath(args.output))
        same_as_input = any(
            output == candidate or (output.exists() and candidate.exists() and os.path.samefile(output, candidate))
            for candidate in (main_archive, minecraft_archive)
        )
        if same_as_input:
            raise ValueError("Output must not overwrite an input archive.")
        inspect_payload(main_archive, "main")
        inspect_payload(minecraft_archive, "minecraft")

        files = [
            (LAUNCHER / "Launcher.ps1", "Launcher.ps1"),
            (LAUNCHER / "compatibility-profiles.json", "compatibility-profiles.json"),
            (LAUNCHER / "Run Minecraft Ring.cmd", "Run Minecraft Ring.cmd"),
            (LAUNCHER / "Test-LauncherSource.ps1", "Test-LauncherSource.ps1"),
            (LAUNCHER / "README.md", "README.md"),
            (ROOT / "LICENSE", "LICENSE"),
            (ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
            (main_archive, "MinecraftRing-0.3.0.zip"),
            (minecraft_archive, "MinecraftRing-Minecraft-0.3.0.zip"),
        ]
        missing = [str(source) for source, _ in files if not source.is_file()]
        if missing:
            raise ValueError(f"Required release files are missing: {', '.join(missing)}")

        profiles = json.loads((LAUNCHER / "compatibility-profiles.json").read_text(encoding="utf-8"))
        enabled = [p["productVersion"] for p in profiles["profiles"] if p.get("launchAllowed")]
        if enabled != ["2.7.1.0"]:
            raise ValueError(f"Refusing to package unexpected enabled native profiles: {enabled}")

        manifest = {
            "product": "Elden-Craft",
            "packageVersion": "0.3.0-preview",
            "platform": "Windows x64",
            "eldenRing": {"appVersion": "1.17.1", "productVersion": "2.7.1.0"},
            "minecraft": {"edition": "Java", "version": "1.21.1", "fabricLoader": "0.19.5"},
            "tlauncher": {"optionalExistingInstall": True, "loginAutomated": False, "endToEndVerified": False},
            "onlinePlay": False,
            "files": [],
        }
        for source, name in files:
            manifest["files"].append({"name": name, "sizeBytes": source.stat().st_size, "sha256": sha256(source)})
        manifest_bytes = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")

        output.parent.mkdir(parents=True, exist_ok=True)
        sums = []
        with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
            for source, name in files:
                bundle.write(source, arcname=name)
                sums.append(f"{sha256(source)}  {name}")
            bundle.writestr("release-manifest.json", manifest_bytes)
            sums.append(f"{hashlib.sha256(manifest_bytes).hexdigest()}  release-manifest.json")
            bundle.writestr("SHA256SUMS.txt", "\n".join(sums) + "\n")

        with zipfile.ZipFile(output) as bundle:
            names = {item.filename for item in bundle.infolist()}
            expected = {name for _, name in files} | {"release-manifest.json", "SHA256SUMS.txt"}
            if names != expected or bundle.testzip():
                output.unlink(missing_ok=True)
                raise ValueError("Final archive verification failed; incomplete archive removed.")
        print(f"Created {output} ({output.stat().st_size:,} bytes)")
        print(f"SHA-256 {sha256(output)}")
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"package_launcher: error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
