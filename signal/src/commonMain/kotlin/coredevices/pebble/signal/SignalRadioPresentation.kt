package coredevices.pebble.signal

/** Access hints describe radio advertisements, not permission to connect or a login page. */
enum class SignalWifiAccess(val label: String, val noPassword: Boolean) {
    OPEN("No password advertised", true),
    OWE("No password advertised · OWE encryption", true),
    CREDENTIALS("Credentials advertised", false),
    UNKNOWN("Access requirements unknown", false),
}

data class SignalDeviceType(val label: String, val evidence: String)

object SignalRadioPresentation {
    fun connectedNetwork(value: String): String {
        val fields = value.split(";").mapNotNull { part ->
            val pair = part.trim().split("=", limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        return when {
            fields["active"] == "false" -> "No active phone network at capture"
            fields["active"] != "true" -> "Phone network access unknown"
            fields["captivePortal"] == "true" -> "Phone network: sign-in required at capture"
            fields["internetValidated"] == "true" -> "Phone network: internet validated at capture"
            else -> "Phone network: internet and sign-in unverified at capture"
        }
    }

    fun wifiAccess(security: String): SignalWifiAccess = when (security.trim().lowercase()) {
        "open advertised; internet and captive portal unknown" -> SignalWifiAccess.OPEN
        "owe encryption advertised; access and internet unknown" -> SignalWifiAccess.OWE
        "security advertised" -> SignalWifiAccess.CREDENTIALS
        else -> SignalWifiAccess.UNKNOWN
    }

    /** Callers must apply the source permission for service metadata before passing it here. */
    fun deviceType(radio: String, metadata: String, beaconId: String = ""): SignalDeviceType {
        if (radio == "wifi") return SignalDeviceType("Wi-Fi access point", "Observed Wi-Fi advertisement")
        if (radio != "bluetooth") return SignalDeviceType("Unknown device type", "No recognized radio type")
        val parts = metadata.split(" · ").map { it.trim() }
        val format = when {
            SignalBeacon.validIdentity(beaconId) -> if (beaconId.startsWith("ibeacon:")) "iBeacon" else "AltBeacon"
            "iBeacon advertisement" in parts -> "iBeacon"
            "AltBeacon advertisement" in parts -> "AltBeacon"
            else -> null
        }
        if (format != null) return SignalDeviceType("Beacon", "Advertises $format format")

        // Match complete service tokens from SignalBeacon.describe, never names or company IDs.
        val services = parts.filter { it.startsWith("Advertised services: ") }
            .flatMap { it.removePrefix("Advertised services: ").split(", ") }
            .map { it.trim().lowercase() }.toSet()
        fun has(service: String, shortUuid: String) = service.lowercase() in services ||
            "0000$shortUuid-0000-1000-8000-00805f9b34fb" in services
        return when {
            has("Heart Rate (180d)", "180d") -> SignalDeviceType("Health / fitness device", "Advertises Heart Rate service")
            has("Human Interface Device (1812)", "1812") -> SignalDeviceType("Input device", "Advertises Human Interface Device service")
            has("Environmental Sensing (181a)", "181a") -> SignalDeviceType("Environmental sensor", "Advertises Environmental Sensing service")
            else -> SignalDeviceType("Unknown Bluetooth device", "No recognized device type advertised")
        }
    }
}
