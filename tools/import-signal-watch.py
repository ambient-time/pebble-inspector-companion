#!/usr/bin/env python3
"""Pin a reviewed Signal Station PBW and its executable PKJS identity in the lab APK."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

UUID = "e2fd86ec-dfb8-460c-afc1-ebe4d071657a"
TARGETS = {"basalt", "chalk", "diorite", "emery", "flint", "gabbro"}

def inspect(pbw):
    with zipfile.ZipFile(pbw) as archive:
        info = json.loads(archive.read("appinfo.json"))
        if info.get("uuid") != UUID or info.get("displayName") != "Signal Station":
            raise ValueError("This is not the approved Signal Station watchapp")
        if set(info.get("targetPlatforms", [])) != TARGETS:
            raise ValueError("All six approved watch targets must be packaged")
        if info.get("versionLabel") != "1.1.0":
            raise ValueError("Signal Station version must be 1.1.0")
        for target in TARGETS:
            if not archive.read(f"{target}/pebble-app.bin"):
                raise ValueError(f"Missing binary for {target}")
        script = archive.read("pebble-js-app.js")
        if b"field-inspector.invalid/native/v1/" not in script:
            raise ValueError("Native bridge is missing from PKJS")
    return {"uuid": UUID, "version": info["versionLabel"], "targets": sorted(TARGETS),
            "pbw_sha256": hashlib.sha256(pbw.read_bytes()).hexdigest(),
            "pkjs_sha256": hashlib.sha256(script).hexdigest()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbw", type=Path)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    if len(args.source_commit) != 40 or any(ch not in "0123456789abcdef" for ch in args.source_commit):
        parser.error("source-commit must be the reviewed full watch source SHA")
    metadata = inspect(args.pbw)
    metadata["source_commit"] = args.source_commit
    destination = Path(__file__).resolve().parents[1] / "androidApp/src/inspectorLab/assets/signal-station"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.pbw, destination / "signal-station.pbw")
    (destination / "pkjs.sha256").write_text(metadata["pkjs_sha256"] + "\n")
    (destination / "watch-provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))

if __name__ == "__main__":
    main()
