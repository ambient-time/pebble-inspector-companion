package coredevices.pebble.signal

data class SignalBeaconMetadata(val identity: String = "", val format: String = "", val description: String = "")

object SignalBeacon {
    fun parse(company: Int, payload: ByteArray): SignalBeaconMetadata? {
        if (company !in 0..65535) return null
        if (company == 0x004c && payload.size == 23 && payload[0].u() == 0x02 && payload[1].u() == 0x15) {
            val uuid = payload.hex(2, 18)
            val major = payload[18].u() * 256 + payload[19].u()
            val minor = payload[20].u() * 256 + payload[21].u()
            return SignalBeaconMetadata("ibeacon:$uuid:$major:$minor", "iBeacon")
        }
        if (payload.size == 24 && payload[0].u() == 0xbe && payload[1].u() == 0xac) {
            return SignalBeaconMetadata("altbeacon:${company.toString(16).padStart(4, '0')}:${payload.hex(2, 22)}", "AltBeacon")
        }
        return null
    }

    fun validIdentity(value: String): Boolean {
        if (Regex("altbeacon:[0-9a-f]{4}:[0-9a-f]{40}").matches(value)) return true
        if (!Regex("ibeacon:[0-9a-f]{32}:[0-9]{1,5}:[0-9]{1,5}").matches(value)) return false
        return value.split(':').takeLast(2).all { it.toInt() in 0..65535 }
    }

    fun describe(manufacturers: Map<Int, ByteArray>, services: List<String>): SignalBeaconMetadata {
        val beacons = manufacturers.entries.take(16).mapNotNull { parse(it.key, it.value) }
        val beacon = beacons.singleOrNull() ?: SignalBeaconMetadata()
        val companies = manufacturers.keys.filter { it in 0..65535 }.sorted().take(16)
            .joinToString { "0x${it.toString(16).padStart(4, '0')}" }
        val serviceNames = services.distinct().take(12).joinToString { serviceLabel(it) }
        val description = listOfNotNull(
            beacon.format.takeIf { it.isNotBlank() }?.let { "$it advertisement" },
            "Multiple beacon formats advertised".takeIf { beacons.size > 1 },
            companies.takeIf { it.isNotBlank() }?.let { "Advertised company IDs: $it" },
            serviceNames.takeIf { it.isNotBlank() }?.let { "Advertised services: $it" },
        ).joinToString(" · ")
        return beacon.copy(description = description)
    }

    fun serviceLabel(uuid: String): String {
        val normalized = uuid.lowercase()
        val name = when (normalized) {
            "00001800-0000-1000-8000-00805f9b34fb" -> "Generic Access"
            "00001801-0000-1000-8000-00805f9b34fb" -> "Generic Attribute"
            "0000180a-0000-1000-8000-00805f9b34fb" -> "Device Information"
            "0000180d-0000-1000-8000-00805f9b34fb" -> "Heart Rate"
            "0000180f-0000-1000-8000-00805f9b34fb" -> "Battery"
            "00001812-0000-1000-8000-00805f9b34fb" -> "Human Interface Device"
            else -> return uuid.take(64)
        }
        return "$name (${normalized.substring(4, 8)})"
    }

    private fun Byte.u() = toInt() and 0xff
    private fun ByteArray.hex(start: Int, end: Int) = (start until end).joinToString("") { this[it].u().toString(16).padStart(2, '0') }
}

class SignalRadioWindow {
    private val samples = mutableListOf<Pair<Long, Int>>()
    fun add(measuredAt: Long, rssi: Int) {
        if (measuredAt < 0 || rssi !in -127..20 || samples.any { it.first == measuredAt }) return
        samples.add(measuredAt to rssi)
        samples.sortBy { it.first }
        while (samples.size > 32) samples.removeAt(0)
    }
    fun summary(now: Long): Pair<Int, Int?> {
        val values = samples.filter { now - it.first in 0..15_000 }.map { it.second }.sorted()
        if (values.isEmpty()) return 0 to null
        return values.size to if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2]
    }
}
