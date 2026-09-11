"""Contract checks for the held messaging-only addon release builder."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('addon', ROOT / 'scripts/build-signal-addon-watch.py')
addon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(addon)


class AddonReleaseTests(unittest.TestCase):
    def test_descriptor_transformation_preserves_uuid_targets_and_message_keys(self):
        descriptor = json.loads((ROOT / 'signalApp/watch-release.json').read_text())
        metadata = {'version': 'old', 'pebble': {'uuid': descriptor['uuid'], 'targetPlatforms': descriptor['targets'], 'enableMultiJS': True, 'messageKeys': ['One', 'Two']}}
        original = copy.deepcopy(metadata)
        result = addon.release_metadata(metadata, descriptor, descriptor['androidPackage'])
        self.assertEqual(metadata, original)
        self.assertEqual(result['version'], descriptor['version'])
        self.assertNotIn('enableMultiJS', result['pebble'])
        self.assertEqual(result['pebble']['messageKeys'], ['One', 'Two'])
        self.assertEqual(result['pebble']['companionApp']['android']['apps'][0]['package'], 'com.lukesteuber.signalstation')
        with self.assertRaises(ValueError): addon.release_metadata(metadata, descriptor, 'old.lab.package')
        with self.assertRaises(ValueError): addon.release_metadata(metadata, dict(descriptor, installationHold=False), descriptor['androidPackage'])

    def test_changed_bytes_cannot_replace_frozen_artifact(self):
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / 'source', Path(folder) / 'target'
            source.write_bytes(b'first')
            addon.immutable_copy(source, target); addon.immutable_copy(source, target)
            source.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'Immutable'): addon.immutable_copy(source, target)
            self.assertEqual(target.read_bytes(), b'first')


if __name__ == '__main__': unittest.main()
