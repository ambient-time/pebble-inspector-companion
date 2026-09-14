package coredevices.pebble.signal

import kotlin.test.*

class SignalRadioPresentationTest {
    @Test fun phoneLoginEvidenceNeverTreatsMissingPortalDetectionAsWorkingInternet() {
        assertEquals("Phone network: sign-in required at capture", SignalRadioPresentation.connectedNetwork("active=true; transport=wifi; captivePortal=true; internetValidated=false"))
        assertEquals("Phone network: internet and sign-in unverified at capture", SignalRadioPresentation.connectedNetwork("active=true; captivePortal=false; internetValidated=false"))
        assertEquals("Phone network access unknown", SignalRadioPresentation.connectedNetwork(""))
        assertEquals("No active phone network at capture", SignalRadioPresentation.connectedNetwork("active=false; captivePortal=false"))
    }
    @Test fun wifiAccessKeepsEncryptionSeparateFromPasswordAndUnknown() {
        assertEquals(SignalWifiAccess.OPEN, SignalRadioPresentation.wifiAccess(SignalWireless.security("[ESS]")))
        assertEquals(SignalWifiAccess.OWE, SignalRadioPresentation.wifiAccess(SignalWireless.security("[RSN-OWE-CCMP][ESS]")))
        for (capabilities in listOf("[WPA2-PSK-CCMP][ESS]", "[RSN-EAP-CCMP][ESS]", "[WEP][ESS]")) {
            assertEquals(SignalWifiAccess.CREDENTIALS, SignalRadioPresentation.wifiAccess(SignalWireless.security(capabilities)))
        }
        assertTrue(SignalWifiAccess.OPEN.noPassword)
        assertTrue(SignalWifiAccess.OWE.noPassword)
        assertFalse(SignalWifiAccess.CREDENTIALS.noPassword)
        assertFalse(SignalWifiAccess.UNKNOWN.noPassword)
        for (value in listOf("", "unknown", "Free public Wi-Fi", "open", "open advertised; login verified")) {
            assertEquals(SignalWifiAccess.UNKNOWN, SignalRadioPresentation.wifiAccess(value))
        }
    }

    @Test fun wifiIsAnAccessPointWithoutInferringItsOwnerOrInternetAccess() {
        val type = SignalRadioPresentation.deviceType("wifi", "Phone hotspot")
        assertEquals("Wi-Fi access point", type.label)
        assertEquals("Observed Wi-Fi advertisement", type.evidence)
    }

    @Test fun specificAdvertisedServicesSupplyBroadTypesAndTheirEvidence() {
        val fixtures = listOf(
            Triple("0000180d-0000-1000-8000-00805f9b34fb", "Health / fitness device", "Heart Rate"),
            Triple("00001812-0000-1000-8000-00805f9b34fb", "Input device", "Human Interface Device"),
            Triple("0000181a-0000-1000-8000-00805f9b34fb", "Environmental sensor", "Environmental Sensing"),
        )
        fixtures.forEach { (uuid, label, service) ->
            val metadata = SignalBeacon.describe(emptyMap(), listOf(uuid)).description
            val type = SignalRadioPresentation.deviceType("bluetooth", metadata)
            assertEquals(label, type.label)
            assertEquals("Advertises $service service", type.evidence)
            assertEquals(label, SignalRadioPresentation.deviceType("bluetooth", "Advertised services: $uuid").label)
        }
    }

    @Test fun beaconHintsRequireARecognizedFormatOrValidIdentity() {
        assertEquals("Beacon", SignalRadioPresentation.deviceType("bluetooth", "iBeacon advertisement · Advertised company IDs: 0x004c").label)
        assertEquals("Beacon", SignalRadioPresentation.deviceType("bluetooth", "AltBeacon advertisement").label)
        val id = "ibeacon:${"0".repeat(32)}:1:2"
        assertEquals("Beacon", SignalRadioPresentation.deviceType("bluetooth", "", id).label)
        assertEquals("Unknown Bluetooth device", SignalRadioPresentation.deviceType("bluetooth", "", "$id; forged").label)
        assertEquals("Unknown Bluetooth device", SignalRadioPresentation.deviceType("bluetooth", "Multiple beacon formats advertised").label)
    }

    @Test fun namesCompaniesAndGenericServicesDoNotBecomeDeviceTypes() {
        val generic = SignalBeacon.describe(mapOf(0x004c to byteArrayOf()), listOf(
            "00001800-0000-1000-8000-00805f9b34fb", "0000180a-0000-1000-8000-00805f9b34fb", "0000180f-0000-1000-8000-00805f9b34fb",
        )).description
        for (metadata in listOf("", "Pixel 9", "Pebble Time 2", "Heart Rate (180d)", "iBeacon headphones",
            "Advertised company IDs: 0x004c", generic, "Advertised services: fake-0000180d-0000-1000-8000-00805f9b34fb")) {
            assertEquals("Unknown Bluetooth device", SignalRadioPresentation.deviceType("bluetooth", metadata).label)
        }
        assertEquals("Unknown device type", SignalRadioPresentation.deviceType("unknown", "iBeacon advertisement").label)
    }
}
