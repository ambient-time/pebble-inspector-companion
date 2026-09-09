import hashlib
import io
import json
import unittest
import zipfile
from verify_inspector_apk import validate_signal_assets, VerificationError


def package(pin=None, pbw_digest=None, uuid="e2fd86ec-dfb8-460c-afc1-ebe4d071657a"):
    script = b"trusted native bridge script"
    digest = hashlib.sha256(script).hexdigest()
    pbw = io.BytesIO()
    with zipfile.ZipFile(pbw, "w") as z:
        z.writestr("pebble-js-app.js", script)
        z.writestr("appinfo.json", json.dumps(dict(uuid=uuid, versionLabel="1.2.0", targetPlatforms=["basalt", "chalk", "diorite", "emery", "flint", "gabbro"])))
    data = pbw.getvalue()
    apk = io.BytesIO()
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("assets/signal-station/signal-station.pbw", data)
        z.writestr("assets/signal-station/pkjs.sha256", pin or digest)
        z.writestr("assets/signal-station/watch-provenance.json", json.dumps(dict(pbw_sha256=pbw_digest or hashlib.sha256(data).hexdigest(), pkjs_sha256=digest, source_commit="a"*40)))
    apk.seek(0)
    return zipfile.ZipFile(apk)


class SignalAssetTests(unittest.TestCase):
    def test_matching_pair(self):
        with package() as z: self.assertEqual(validate_signal_assets(z)["source_commit"], "a"*40)

    def test_mismatched_script_pbw_and_uuid_rejected(self):
        for args in [dict(pin="b"*64), dict(pbw_digest="b"*64), dict(uuid="other")]:
            with self.subTest(args=args), package(**args) as z, self.assertRaises(VerificationError):
                validate_signal_assets(z)
