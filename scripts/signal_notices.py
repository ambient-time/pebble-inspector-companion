#!/usr/bin/env python3
"""Write the third-party notices shipped inside the Signal Station app.

Reads the resolved release classpath and each library's published POM, then
writes signalApp/src/main/assets/signal-station/THIRD_PARTY_NOTICES.txt.

  ./gradlew :signalApp:dependencies --configuration releaseRuntimeClasspath --console=plain > deps.txt
  python3 scripts/signal_notices.py deps.txt            # rewrite the asset
  python3 scripts/signal_notices.py deps.txt --check    # fail if the asset is stale

License names come from the POMs in the Gradle cache (~/.gradle), with
signalApp/notices/overrides.json for libraries whose POM names none and for
bundled items that are not Maven libraries. Full texts are in
signalApp/notices/texts/.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NOTICES = ROOT / "signalApp" / "notices"
ASSET = ROOT / "signalApp/src/main/assets/signal-station/THIRD_PARTY_NOTICES.txt"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
COORDINATE = re.compile(r"([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^\s:]+))?(?: -> ([^\s]+))?")

CANONICAL = [
    (re.compile(r"apache", re.I), "Apache-2.0"),
    (re.compile(r"\bMIT\b", re.I), "MIT"),
    (re.compile(r"BSD", re.I), "BSD-3-Clause"),
    (re.compile(r"CC0|Creative Commons Zero|Public Domain", re.I), "CC0-1.0"),
    (re.compile(r"Android Software Development Kit", re.I), "Android SDK License"),
    (re.compile(r"ML Kit", re.I), "ML Kit Terms of Service"),
    (re.compile(r"LGPL|Lesser General", re.I), "LGPL-2.1-or-later"),
]


def coordinates(text):
    found = {}
    for line in text.splitlines():
        if "--- " not in line or "project :" in line:
            continue
        match = COORDINATE.search(line.split("--- ", 1)[1])
        if not match:
            continue
        group, artifact, declared, resolved = match.groups()
        version = resolved or declared
        if version:
            found[(group, artifact)] = version
    return sorted((g, a, v) for (g, a), v in found.items())


def pom(group, artifact, version):
    folder = CACHE / group / artifact / version
    hits = sorted(folder.glob(f"*/{artifact}-{version}.pom")) if folder.is_dir() else []
    return ET.parse(hits[0]).getroot() if hits else None


def strip(tag):
    return tag.split("}", 1)[-1]


def pom_licenses(group, artifact, version, depth=0):
    root = pom(group, artifact, version)
    if root is None:
        return []
    names = [name.text.strip() for lic in root.iter() if strip(lic.tag) == "license"
             for name in lic if strip(name.tag) == "name" and name.text]
    if names or depth > 3:
        return names
    for child in root:
        if strip(child.tag) == "parent":
            parts = {strip(x.tag): (x.text or "").strip() for x in child}
            return pom_licenses(parts.get("groupId", ""), parts.get("artifactId", ""), parts.get("version", ""), depth + 1)
    return []


def canonical(names):
    result = []
    for name in names:
        for pattern, label in CANONICAL:
            if pattern.search(name):
                if label not in result:
                    result.append(label)
                break
        else:
            if name not in result:
                result.append(name)
    return result


def build(deps_text):
    overrides = json.loads((NOTICES / "overrides.json").read_text())
    by_license, unknown = {}, []
    for group, artifact, version in coordinates(deps_text):
        key = f"{group}:{artifact}"
        chosen = overrides["libraries"].get(key) or next(
            (value for prefix, value in overrides["groups"].items() if group == prefix or group.startswith(prefix + ".")), None)
        labels = [chosen] if chosen else canonical(pom_licenses(group, artifact, version))
        if not labels:
            unknown.append(f"{key}:{version}")
            continue
        by_license.setdefault(" or ".join(labels), []).append(f"{key}:{version}")
    if unknown:
        raise SystemExit("No license found for: " + ", ".join(unknown) + ". Add them to signalApp/notices/overrides.json.")
    out = [overrides["preamble"].strip(), ""]
    for section in overrides["sections"]:
        out += ["", section["title"], "=" * len(section["title"]), "", section["body"].strip(), ""]
    out += ["", "Libraries on the release classpath", "==================================", "",
            "Maven coordinates and versions as resolved for this build, grouped by the license each library is used under.", ""]
    for label in sorted(by_license):
        out += ["", f"{label} ({len(by_license[label])})", "-" * (len(label) + len(str(len(by_license[label]))) + 3), ""]
        out += by_license[label]
    for entry in overrides["texts"]:
        out += ["", "", entry["title"], "=" * len(entry["title"]), ""]
        out += [(NOTICES / "texts" / entry["file"]).read_text().rstrip()]
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    text = build(Path(sys.argv[1]).read_text())
    if "--check" in sys.argv:
        if not ASSET.is_file() or ASSET.read_text() != text:
            raise SystemExit(f"{ASSET.relative_to(ROOT)} is stale. Run scripts/signal_notices.py again without --check.")
        print("Notices are current.")
        return
    ASSET.write_text(text)
    print(f"Wrote {ASSET.relative_to(ROOT)}: {len(text)} bytes.")


if __name__ == "__main__":
    main()
