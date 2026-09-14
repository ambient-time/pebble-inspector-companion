package coredevices.pebble.signal

/** A radio observation in a bounded session, never a physical position or a person. */
data class SignalLiveEntry(
    val id: String, val radio: String, val label: String, val rssi: Int,
    val firstSeenAt: Long, val lastSeenAt: Long, val samples: List<Int>, val sampleCount: Int = 1,
    val metadata: String = "", val security: String = "", val frequencyMHz: Int? = null,
    val address: String = "", val band: String = "faint", val trend: String = "unknown",
    val status: String = "fresh", val advertisedName: String = "",
    val scanCount: Int = 1,
) {
    fun fresh(now: Long) = status == "fresh" && now - lastSeenAt in 0..15_000
}

data class SignalLiveState(
    val running: Boolean = false, val scanning: Boolean = false, val startedAt: Long = 0,
    val updatedAt: Long = 0, val now: Long = 0, val entries: List<SignalLiveEntry> = emptyList(),
    val outcomes: Map<String, String> = emptyMap(), val status: String = "Ready to scan selected radios.",
    val newCount: Int = 0, val omittedAtLeast: Int = 0,
)

object SignalLive {
    const val DURATION_MS = 5 * 60_000L
    const val WIFI_INTERVAL_MS = 35_000L
    fun sources(enabled: Set<String>): Set<String> = enabled.filter { key ->
        ("wifi" in enabled && key in setOf("wifi", "wifi.names", "wifi.identifiers")) ||
            ("bluetooth" in enabled && key in setOf("bluetooth", "bluetooth.names", "bluetooth.identifiers", "bluetooth.services"))
    }.toSet()
    fun band(rssi: Int) = when { rssi >= -60 -> "strong"; rssi >= -75 -> "medium"; else -> "faint" }
    fun trend(samples: List<Int>): String {
        if (samples.size < 3) return "unknown"
        val delta = samples.takeLast(2).average() - samples.take(2).average()
        return when { delta >= 5 -> "strengthening"; delta <= -5 -> "weakening"; else -> "steady" }
    }
    fun snapshot(scene: SignalLiveState, enabled: Set<String>, at: Long): List<SignalObservation> {
        val allowed = sources(enabled)
        val rows = scene.entries.filter { it.radio in allowed && it.fresh(at) }
        if (rows.isEmpty()) throw SignalProviderException("No fresh signals to save. Start Live view and wait for a scan. Nothing was sent.")
        return buildList {
            rows.forEach { row ->
                val baseFields = mapOf("liveEntry" to row.id, "strengthBand" to row.band,
                    "signalTrend" to row.trend, "recentRssiDbm" to row.samples.joinToString(","),
                    "scope" to "radio advertisement; no person, distance or bearing established")
                add(SignalObservation(row.radio, "phone", row.rssi.toString(), "dBm", at, row.lastSeenAt, "fresh",
                    fields = baseFields + if (row.radio == "wifi") mapOf("security" to row.security, "frequencyMHz" to (row.frequencyMHz?.toString() ?: "unknown")) else emptyMap(),
                    metric = "rssi", number = row.rssi.toDouble(), sampleCount = row.sampleCount))
                if ("${row.radio}.names" in allowed) add(SignalObservation("${row.radio}.names", "phone", row.label, collectedAt = at, measuredAt = row.lastSeenAt,
                    fields = mapOf("liveEntry" to row.id, "nameOrSessionLabel" to row.label, "advertisedName" to row.advertisedName)))
                if ("${row.radio}.identifiers" in allowed && row.address.isNotBlank()) add(SignalObservation("${row.radio}.identifiers", "phone", row.address,
                    collectedAt = at, measuredAt = row.lastSeenAt, fields = mapOf("liveEntry" to row.id, "address" to row.address)))
                if (row.radio == "bluetooth" && "bluetooth.services" in allowed && row.metadata.isNotBlank()) add(SignalObservation("bluetooth.services", "phone", row.metadata,
                    collectedAt = at, measuredAt = row.lastSeenAt, fields = mapOf("liveEntry" to row.id)))
            }
            for ((radio, outcome) in scene.outcomes.filterKeys { it in allowed }) add(SignalObservation(radio, "phone",
                "Live scene; scan=$outcome; retainedFresh=${rows.count { it.radio == radio }}; omittedAtLeast=${scene.omittedAtLeast}; limited detection, not a device census",
                collectedAt = at, measuredAt = null, status = outcome, metric = "coverage",
                fields = mapOf("scope" to "live scan window", "omittedAtLeast" to scene.omittedAtLeast.toString())))
        }
    }
}

/** Addresses correlate samples only within this session; public entries expose them only by choice. */
class SignalLiveReducer {
    private val entries = linkedMapOf<String, SignalLiveEntry>()
    private val labels = mutableMapOf<String, String>()
    private var sequence = 0
    private var selectedSources = emptySet<String>()
    fun reset() { entries.clear(); labels.clear(); sequence = 0; selectedSources = emptySet() }
    fun label(id: String, text: String) {
        entries.entries.firstOrNull { it.value.id == id }?.let { (key, old) ->
            labels[id] = text.trim().take(100)
            entries[key] = old.copy(label = labels.getValue(id))
        }
    }
    fun current(now: Long): List<SignalLiveEntry> {
        entries.entries.removeAll { now - it.value.lastSeenAt !in 0..60_000 }
        labels.keys.retainAll(entries.values.map { it.id }.toSet())
        return entries.values.sortedWith(compareByDescending<SignalLiveEntry> { it.fresh(now) }.thenByDescending { it.rssi }.thenBy { it.id })
    }
    fun accept(acquisition: SignalAcquisition, enabled: Set<String>, now: Long): Pair<List<SignalLiveEntry>, Int> {
        val allowed = SignalLive.sources(enabled)
        if (selectedSources != allowed) { reset(); selectedSources = allowed }
        entries.entries.removeAll { it.value.radio !in allowed }
        current(now)
        var added = 0
        val counted = mutableSetOf<String>()
        for (sample in acquisition.candidates.take(128)) {
            val measured = sample.measuredAt ?: continue
            if (sample.radio !in allowed || sample.address.isBlank() || sample.rssi !in -127..20 || now - measured !in 0..60_000) continue
            val key = "${sample.radio}:${sample.address.lowercase()}"
            val previous = entries[key]
            if (previous != null && measured <= previous.lastSeenAt) continue
            val id = previous?.id ?: "signal-${++sequence}".also { if (sample.status == "fresh" && now - measured <= 15_000) added++ }
            val strength = sample.medianRssi?.takeIf { it in -127..20 } ?: sample.rssi
            val samples = (previous?.samples.orEmpty() + strength).takeLast(12)
            val name = sample.name.take(100).takeIf { "${sample.radio}.names" in allowed }.orEmpty()
            entries[key] = SignalLiveEntry(id, sample.radio, labels[id] ?: name.ifBlank { "${if (sample.radio == "wifi") "Wi-Fi" else "Bluetooth"} ${id.substringAfter('-')}" },
                strength, previous?.firstSeenAt ?: measured, measured, samples, ((previous?.sampleCount ?: 0) + sample.sampleCount.coerceIn(1, 64)).coerceAtMost(1_000_000),
                metadata = sample.metadata.take(1024).takeIf { "bluetooth.services" in allowed && sample.radio == "bluetooth" }.orEmpty(),
                security = sample.security.take(200), frequencyMHz = sample.frequencyMHz,
                address = sample.address.take(64).takeIf { "${sample.radio}.identifiers" in allowed }.orEmpty(),
                band = SignalLive.band(strength), trend = SignalLive.trend(samples), status = sample.status, advertisedName = name,
                scanCount = ((previous?.scanCount ?: 0) + if (sample.status == "fresh" && now - measured <= 15_000 && counted.add(key)) 1 else 0).coerceAtMost(1_000_000))
        }
        if (entries.size > 128) {
            val keep = entries.entries.sortedWith(compareByDescending<Map.Entry<String, SignalLiveEntry>> { it.value.lastSeenAt }.thenByDescending { it.value.rssi }).take(128).map { it.key }.toSet()
            entries.keys.retainAll(keep)
        }
        return current(now) to added
    }
}
