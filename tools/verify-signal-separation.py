#!/usr/bin/env python3
"""Enforce the boundary between Signal Station features and watch management."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
for module in ('signal', 'signalApp'):
    for path in (root / module / 'src').rglob('*.kt'):
        source = path.read_text()
        assert 'import io.rebble.libpebblecommon' not in source, path
        assert not re.search(r'\b(connectGatt|createBond|factoryReset|resetIntoPrf)\s*\(', source), path
    dependencies = (root / module / 'build.gradle.kts').read_text()
    assert not re.search(r'project\(":(pebble|libpebble3|composeApp|experimental|util)"\)', dependencies), module
manifest = (root / 'signalApp/src/main/AndroidManifest.xml').read_text()
for component in ('BOOT_COMPLETED', 'PebbleService', 'FirmwareSideload', 'PebbleKitProvider', 'MainApplication'):
    assert component not in manifest, component
assert 'android:allowBackup="false"' in manifest
print('PASS feature modules exclude watch management and parent application components')
