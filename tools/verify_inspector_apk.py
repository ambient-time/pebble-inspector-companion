#!/usr/bin/env python3
"""Inspect a built Pebble Inspector Lab APK before installing it."""

import argparse
import hashlib
import json
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "coredevices.coreapp.inspectorlab"
LABEL = "Pebble Inspector Lab"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {
    "root", "file", "database", "sharedpref", "external", "device_root",
    "device_file", "device_database", "device_sharedpref",
}


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def parse_xmltree(text):
    """Read the element/attribute subset emitted by aapt2 dump xmltree."""
    root, stack = None, []
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([\w.-]+)(?: |$)", line)
        if element:
            indent = len(element[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = ET.Element(element[2])
            if stack:
                stack[-1][1].append(node)
            else:
                require(root is None, "aapt2 returned multiple XML roots")
                root = node
            stack.append((indent, node))
            continue
        attribute = re.match(r"^\s*A: ([^=]+)=(.*)$", line)
        if attribute:
            require(stack, "aapt2 attribute has no parent element")
            name = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", attribute[1])
            if name.startswith("http://schemas.android.com/apk/res/android:"):
                name = ANDROID + name.rsplit(":", 1)[1]
            value = attribute[2]
            if value.startswith('"'):
                try:
                    value = json.JSONDecoder().raw_decode(value)[0]
                except ValueError as error:
                    raise VerificationError("Unrecognized aapt2 string") from error
            else:
                value = value.split(" (", 1)[0]
            stack[-1][1].set(name, value)
    require(root is not None, "aapt2 returned no XML elements")
    return root


def parse_badging(text):
    labels = re.findall(r"^application-label(?:-[^:]*)?:'([^']*)'$", text, re.M)
    require(labels, "aapt2 returned no resolved application label")
    require(all(label == LABEL for label in labels),
            "Resolved application labels must all be Pebble Inspector Lab")
    return sorted(set(labels))


def validate_signature(returncode, output):
    require(returncode == 0 and "Verifies" in output.splitlines(),
            "APK signature verification failed: " + output.strip())
    count = re.search(r"^Number of signers: (\d+)$", output, re.M)
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                         output, re.M)
    require(count and int(count[1]) > 0 and len(digests) == int(count[1]),
            "apksigner did not identify every signing certificate")
    return [digest.lower() for digest in digests]


def class_name(value, package):
    if value.startswith("."):
        return package + value
    return value if "." in value else package + "." + value


def validate_manifest(root, labels):
    require(root.tag == "manifest", "APK XML root is not manifest")
    require(root.get("package") == PACKAGE, "APK package must be " + PACKAGE)
    version = root.get(ANDROID + "versionName", "")
    require(version.endswith("-inspector-lab.2"), "APK version lacks -inspector-lab.2 suffix")
    code = root.get(ANDROID + "versionCode", "")
    require(code.isdecimal() and int(code) > 0, "APK has no positive version code")
    require(labels and all(label == LABEL for label in labels), "APK has the wrong visible label")
    permissions = [item.get(ANDROID + "name", "") for item in root.findall("permission")]
    require(all(name.startswith(PACKAGE + ".") for name in permissions),
            "Lab APK must not redeclare permissions owned by stock")
    apps = root.findall("application")
    require(len(apps) == 1, "APK must declare one application")
    app = apps[0]
    require(app.get(ANDROID + "debuggable") == "true", "Lab APK must be debuggable")
    require(app.get(ANDROID + "allowBackup") == "false", "Lab APK must disable backup explicitly")
    require(class_name(app.get(ANDROID + "name", ""), PACKAGE) == "coredevices.coreapp.MainApplication",
            "Application class must remain coredevices.coreapp.MainApplication")
    components = {}
    for tag in ("activity", "service", "receiver", "provider"):
        components[tag] = [class_name(item.get(ANDROID + "name", ""), PACKAGE)
                           for item in app.findall(tag)]
        require(all(not name.startswith(PACKAGE + ".") for name in components[tag]),
                "Manifest component was renamed into the lab application ID: " + tag)
    require("coredevices.coreapp.MainActivity" in components["activity"],
            "MainActivity must keep its original class name")
    require({"coredevices.coreapp.PebbleService", "coredevices.coreapp.BugReportService"}
            <= set(components["service"]), "PebbleService and BugReportService must keep their class names")
    for alias in app.findall("activity-alias"):
        require(class_name(alias.get(ANDROID + "targetActivity", ""), PACKAGE)
                in components["activity"], "Activity alias targets an undeclared activity")
    authorities = []
    for provider in app.findall("provider"):
        values = provider.get(ANDROID + "authorities", "").split(";")
        require(all(value.startswith(PACKAGE + ".") for value in values),
                "Provider authority is not lab-scoped: " + ";".join(values))
        authorities.extend(values)
    require(authorities and len(authorities) == len(set(authorities)),
            "Provider authorities are missing or duplicated")
    require(PACKAGE + ".fileprovider" in authorities, "Lab FileProvider authority is missing")
    return {
        "package": PACKAGE, "label": LABEL, "version_name": version,
        "version_code": int(code), "debuggable": True, "allow_backup": False,
        "provider_authorities": authorities, "declared_permissions": permissions,
        "manifest_component_names": components,
    }


def validate_exclusions(root, extraction=False):
    if extraction:
        require(root.tag == "data-extraction-rules", "Wrong data extraction XML root")
        sections = [root.find("cloud-backup"), root.find("device-transfer")]
    else:
        require(root.tag == "full-backup-content", "Wrong full-backup XML root")
        sections = [root]
    for section in sections:
        require(section is not None, "Cloud backup or device transfer policy is missing")
        require(not section.findall("include"), "Backup policy contains an include rule")
        excluded = {item.get("domain") for item in section.findall("exclude") if item.get("path") == "."}
        require(DOMAINS <= excluded, "Backup policy does not exclude every storage domain")


def resource_names(text):
    return {"@" + match[0].lower(): "xml/" + match[1] for match in re.findall(
        r"\bresource (0x[0-9a-fA-F]+) (?:[^\s:]+:)?xml/([^\s]+)", text)}


def discover_tool(name, sdk):
    locations = [shutil.which(name)]
    roots = [sdk, os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
             Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"]
    for value in roots:
        if value:
            root = Path(value)
            locations.extend(str(path) for pattern in (
                "cmdline-tools/*/bin/" + name, "build-tools/*/" + name, "tools/bin/" + name,
            ) for path in sorted(root.glob(pattern), reverse=True))
    for value in locations:
        if value and Path(value).is_file() and os.access(value, os.X_OK):
            return str(value)
    raise VerificationError("Cannot find Android SDK tool: " + name)


def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(str(error)) from error
    if result.returncode:
        raise VerificationError(Path(command[0]).name + " failed: " + (result.stderr or result.stdout).strip())
    return result.stdout


def validate_signal_assets(archive):
    base = "assets/signal-station/"
    try:
        provenance = json.loads(archive.read(base + "watch-provenance.json"))
        pin = archive.read(base + "pkjs.sha256").decode().strip()
        pbw = archive.read(base + "signal-station.pbw")
        require(hashlib.sha256(pbw).hexdigest() == provenance["pbw_sha256"], "Bundled watch PBW digest mismatch")
        with zipfile.ZipFile(io.BytesIO(pbw)) as watch:
            script_digest = hashlib.sha256(watch.read("pebble-js-app.js")).hexdigest()
            info = json.loads(watch.read("appinfo.json"))
            require(info["uuid"] == "e2fd86ec-dfb8-460c-afc1-ebe4d071657a", "Wrong bundled watch UUID")
            require(info["versionLabel"] == "1.1.0", "Wrong bundled watch version")
            require(set(info["targetPlatforms"]) == {"basalt", "chalk", "diorite", "emery", "flint", "gabbro"}, "Bundled watch target mismatch")
        require(script_digest == pin == provenance["pkjs_sha256"], "Native bridge script digest mismatch")
        require(re.fullmatch(r"[0-9a-f]{40}", provenance["source_commit"]) is not None, "Watch source revision missing")
        return provenance
    except (KeyError, ValueError, zipfile.BadZipFile) as error:
        raise VerificationError("Signal Station asset verification failed: " + str(error)) from error


def verify(apk, sdk=None):
    require(apk.is_file(), "APK does not exist: " + str(apk))
    aapt = discover_tool("aapt2", sdk)
    signer = discover_tool("apksigner", sdk)
    signature = run([signer, "verify", "--verbose", "--print-certs", str(apk)])
    certificates = validate_signature(0, signature)
    warnings = []
    try:
        analyzer = discover_tool("apkanalyzer", sdk)
        manifest = ET.fromstring(run([analyzer, "manifest", "print", str(apk)]))
        engine = "apkanalyzer"
    except (VerificationError, ET.ParseError) as error:
        warnings.append(str(error))
        print("Manifest reader fallback: " + str(error), file=sys.stderr)
        manifest = parse_xmltree(run([aapt, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)]))
        engine = "aapt2"
    labels = parse_badging(run([aapt, "dump", "badging", str(apk)]))
    metadata = validate_manifest(manifest, labels)
    resources = resource_names(run([aapt, "dump", "resources", str(apk)]))
    app = manifest.find("application")
    policies = []
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        signal_metadata = validate_signal_assets(archive)
        for attribute, name, extraction in (
            ("fullBackupContent", "inspector_backup_rules", False),
            ("dataExtractionRules", "inspector_data_extraction_rules", True),
        ):
            reference = app.get(ANDROID + attribute, "")
            resolved = resources.get(reference.lower(), reference.lstrip("@").split(":")[-1])
            require(resolved == "xml/" + name, attribute + " does not reference the lab exclusion policy")
            paths = [entry for entry in entries if re.fullmatch(r"res/xml(?:-[^/]+)?/" + name + r"\.xml", entry)]
            require("res/xml/" + name + ".xml" in paths, "APK lacks the default " + name + " resource")
            for path in paths:
                policy = parse_xmltree(run([aapt, "dump", "xmltree", "--file", path, str(apk)]))
                validate_exclusions(policy, extraction)
                policies.append(path)
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    metadata.update({
        "status": "verified", "apk": str(apk.resolve()), "size_bytes": apk.stat().st_size,
        "sha256": digest.hexdigest(), "signer_certificate_sha256": certificates,
        "signal_watch": signal_metadata,
        "manifest_reader": engine, "backup_policies": policies, "warnings": warnings,
        "scope": "APK signature, manifest identity and packaged backup exclusions; no install or hardware test",
    })
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, help="Android SDK directory")
    parser.add_argument("--json", type=Path, help="Write verified artifact metadata to this file")
    args = parser.parse_args()
    exit_code = 0
    try:
        require(not args.json or args.json.resolve() != args.apk.resolve(),
                "Metadata output cannot overwrite the APK")
        metadata = verify(args.apk, args.sdk)
    except (VerificationError, ET.ParseError, zipfile.BadZipFile, OSError) as error:
        metadata = {"status": "failed", "error": str(error)}
        exit_code = 1
    result = json.dumps(metadata, indent=2) + "\n"
    if args.json and args.json.resolve() != args.apk.resolve():
        try:
            args.json.write_text(result)
        except OSError as error:
            print("Cannot write metadata: " + str(error), file=sys.stderr)
            exit_code = 1
    print(result, end="")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
