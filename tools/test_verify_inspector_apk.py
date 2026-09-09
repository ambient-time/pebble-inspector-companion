import unittest
import xml.etree.ElementTree as ET

from verify_inspector_apk import (
    ANDROID, LABEL, PACKAGE, VerificationError, parse_badging,
    parse_xmltree, resource_names, validate_exclusions, validate_manifest,
    validate_signature,
)


def manifest():
    return ET.fromstring('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="coredevices.coreapp.inspectorlab" android:versionCode="11100003"
      android:versionName="1.11.0.3-inspector-lab.8">
      <application android:name="coredevices.coreapp.MainApplication"
        android:debuggable="true" android:allowBackup="false" android:label="@string/app_name">
        <activity android:name="coredevices.coreapp.MainActivity" />
        <activity-alias android:name="ViewPermissionUsageActivity"
          android:targetActivity="coredevices.coreapp.MainActivity" />
        <service android:name="coredevices.pebble.signal.SignalWakeService" android:exported="false" android:foregroundServiceType="microphone" />
        <service android:name="coredevices.coreapp.PebbleService" />
        <service android:name="coredevices.coreapp.BugReportService" />
        <provider android:name="androidx.core.content.FileProvider"
          android:authorities="coredevices.coreapp.inspectorlab.fileprovider" />
      </application>
    </manifest>''')


class ManifestTests(unittest.TestCase):
    def test_lab_identity_and_original_class_namespace(self):
        result = validate_manifest(manifest(), [LABEL])
        self.assertEqual(result["package"], PACKAGE)
        self.assertEqual(result["version_code"], 11100003)

    def test_wake_service_cannot_be_exported_or_lose_microphone_type(self):
        for attribute, value in (("exported", "true"), ("foregroundServiceType", "connectedDevice")):
            root = manifest()
            root.find("application/service").set(ANDROID + attribute, value)
            with self.assertRaises(VerificationError):
                validate_manifest(root, [LABEL])

    def test_stock_package_rejected(self):
        root = manifest()
        root.set("package", "coredevices.coreapp")
        with self.assertRaisesRegex(VerificationError, "APK package"):
            validate_manifest(root, [LABEL])

    def test_isolation_flags_must_be_explicit(self):
        for name, value in (("debuggable", None), ("debuggable", "false"),
                            ("allowBackup", None), ("allowBackup", "true")):
            with self.subTest(name=name, value=value):
                root = manifest()
                app = root.find("application")
                if value is None:
                    del app.attrib[ANDROID + name]
                else:
                    app.set(ANDROID + name, value)
                with self.assertRaises(VerificationError):
                    validate_manifest(root, [LABEL])

    def test_version_and_visible_label(self):
        for version in ("1.11.0.3", "1.11.0.3-inspector-lab.10"):
            root = manifest()
            root.set(ANDROID + "versionName", version)
            with self.assertRaises(VerificationError):
                validate_manifest(root, [LABEL])
        with self.assertRaisesRegex(VerificationError, "visible label"):
            validate_manifest(manifest(), ["Pebble"])

    def test_both_stock_provider_authority_forms_rejected(self):
        for value in ("coredevices.coreapp.fileprovider", "com.getpebble.android.provider.basalt",
                      PACKAGE + ".fileprovider;coredevices.coreapp.pebblekit"):
            root = manifest()
            root.find("application/provider").set(ANDROID + "authorities", value)
            with self.assertRaisesRegex(VerificationError, "authority"):
                validate_manifest(root, [LABEL])

    def test_suffix_must_not_rename_runtime_class(self):
        for target in ("application", "application/activity", "application/service"):
            root = manifest()
            root.find(target).set(ANDROID + "name", ".MainActivity")
            with self.assertRaises(VerificationError):
                validate_manifest(root, [LABEL])

    def test_stock_owned_permission_declarations_rejected(self):
        for name in ("io.rebble.pebblekit2.permission.SEND_DATA_TO_WATCH",
                     "io.rebble.pebblekit2.permission.RECEIVE_DATA_FROM_WATCH",
                     "io.rebble.pebblekit2.permission.READ_PROVIDER"):
            root = manifest()
            ET.SubElement(root, "permission", {ANDROID + "name": name})
            with self.assertRaisesRegex(VerificationError, "permissions owned by stock"):
                validate_manifest(root, [LABEL])
        root = manifest()
        ET.SubElement(root, "permission", {ANDROID + "name": PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"})
        self.assertEqual(len(validate_manifest(root, [LABEL])["declared_permissions"]), 1)

    def test_alias_target_must_exist(self):
        root = manifest()
        root.find("application/activity-alias").set(ANDROID + "targetActivity", ".MainActivity")
        with self.assertRaisesRegex(VerificationError, "alias"):
            validate_manifest(root, [LABEL])


class ToolOutputTests(unittest.TestCase):
    def test_aapt_xmltree_keeps_namespaces_hierarchy_and_quoted_text(self):
        root = parse_xmltree('''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="coredevices.coreapp.inspectorlab" (Raw: "coredevices.coreapp.inspectorlab")
      E: application (line=3)
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true
        A: http://schemas.android.com/apk/res/android:label(0x01010001)="Signal Station" (Raw: "Signal Station")
          E: provider (line=4)
            A: http://schemas.android.com/apk/res/android:authorities(0x01010018)="example.lab"
      E: uses-sdk (line=5)
        A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=32
''')
        self.assertEqual(root.get("package"), PACKAGE)
        self.assertEqual(root.find("application").get(ANDROID + "label"), LABEL)
        self.assertEqual(root.find("application/provider").get(ANDROID + "authorities"), "example.lab")
        self.assertEqual(root.find("uses-sdk").get(ANDROID + "minSdkVersion"), "32")

    def test_empty_or_malformed_xmltree_rejected(self):
        for value in ("tool failed", ' A: package="test"', "E: one\nE: two"):
            with self.assertRaises(VerificationError):
                parse_xmltree(value)

    def test_resolved_label_in_every_locale(self):
        self.assertEqual(parse_badging("application-label:'Signal Station'\n"), [LABEL])
        for value in ("", "application-label:'Pebble'\n",
                      "application-label:'Signal Station'\napplication-label-fr:'Pebble'\n"):
            with self.assertRaises(VerificationError):
                parse_badging(value)

    def test_signature_failure_even_with_plausible_certificate(self):
        good = "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertEqual(validate_signature(0, good), ["a" * 64])
        for code, output in ((1, good), (0, good.replace("Verifies", "DOES NOT VERIFY")),
                             (0, good.replace("signers: 1", "signers: 2")), (0, "Verifies\n")):
            with self.assertRaises(VerificationError):
                validate_signature(code, output)

    def test_resource_identity_mapping(self):
        result = resource_names("""resource 0x7f120001 xml/inspector_backup_rules
resource 0x7f120002 coredevices.coreapp.inspectorlab:xml/inspector_data_extraction_rules
resource 0x7f100001 string/app_name
""")
        self.assertEqual(result, {"@0x7f120001": "xml/inspector_backup_rules",
                                  "@0x7f120002": "xml/inspector_data_extraction_rules"})


class BackupTests(unittest.TestCase):
    def test_complete_policies_and_missing_transfer_domain(self):
        entries = "".join('<exclude domain="' + domain + '" path="." />' for domain in (
            "root", "file", "database", "sharedpref", "external", "device_root",
            "device_file", "device_database", "device_sharedpref",
        ))
        validate_exclusions(ET.fromstring("<full-backup-content>" + entries + "</full-backup-content>"))
        root = ET.fromstring("<data-extraction-rules><cloud-backup>" + entries +
                             "</cloud-backup><device-transfer>" + entries +
                             "</device-transfer></data-extraction-rules>")
        validate_exclusions(root, extraction=True)
        transfer = root.find("device-transfer")
        transfer.remove(transfer.find("exclude[@domain='sharedpref']"))
        with self.assertRaisesRegex(VerificationError, "storage domain"):
            validate_exclusions(root, extraction=True)

    def test_missing_transfer_section_or_root_path_rejected(self):
        with self.assertRaises(VerificationError):
            validate_exclusions(ET.fromstring("<data-extraction-rules><cloud-backup /></data-extraction-rules>"), True)
        with self.assertRaises(VerificationError):
            validate_exclusions(ET.fromstring('<full-backup-content><exclude domain="root" path="keys" /></full-backup-content>'))


if __name__ == "__main__":
    unittest.main()
