package coredevices.pebble.signal

import kotlin.math.abs

/** Disposable view of retained originals. No persistence, provider call or inferred continuity. */
data class SignalTrendIdentity(
    val key: String, val metric: String, val unit: String, val source: String,
    val device: String, val identity: String, val period: String, val recordingMethod: String, val bootScope: String,
) {
    val id: String get() = listOf(key, metric, unit, source, device, identity, period, recordingMethod, bootScope).joinToString("|") { "${it.length}:$it" }
}

data class SignalTrendPoint(val recordId: String, val observationId: String, val measuredAt: Long,
    val value: Double, val sessionId: String, val windowStart: Long?, val windowEnd: Long?, val sampleCount: Int?)

data class SignalTrendSeries(
    val identity: SignalTrendIdentity, val points: List<SignalTrendPoint>,
    val unusableRows: Int, val incompatibleWindows: Int, val duplicateCopies: Int, val conflictingRows: Int,
    val overlappingRows: Int, val olderPointsOmitted: Int, val sourceRowsOmitted: Int,
) {
    val minimum: Double? get() = points.minOfOrNull { it.value }
    val maximum: Double? get() = points.maxOfOrNull { it.value }
    val median: Double? get() {
        val values = points.map { it.value }.sorted()
        if (values.isEmpty()) return null
        return if (values.size % 2 == 0) values[values.size / 2 - 1] / 2 + values[values.size / 2] / 2 else values[values.size / 2]
    }
    val difference: Double? get() = if (points.size < 2) null else (points.last().value - points.first().value).takeIf { it.isFinite() }
    val independentSessions: Int get() = points.map { it.sessionId }.distinct().size
}

object SignalTrend {
    private val originalKinds = setOf("capture", "presence", "observation", "health_import")

    fun series(anchor: SignalRecord, records: List<SignalRecord>, enabled: Set<String>, limit: Int = 60): List<SignalTrendSeries> {
        require(limit > 0)
        if (!eligible(anchor, enabled)) return emptyList()
        val normalized = SignalLearning.normalize(anchor)
        val targets = normalized.observations.filter { supported(it) && numeric(it) != null && SignalLearning.fresh(it) }
            .groupBy { identity(normalized, it) }.values.map { rows -> rows.maxBy { it.measuredAt!! } }
        val originals = (listOf(normalized) + records.filter { it.id != anchor.id }.map(SignalLearning::normalize))
            .filter { eligible(it, enabled) }.distinctBy { it.id }
        return targets.map { target ->
            val key = identity(normalized, target)
            val candidates = originals.flatMap { record -> record.observations.filter { identity(record, it) == key }
                .map { record to it } }.filter { (_, row) -> (row.measuredAt ?: row.collectedAt) <= target.measuredAt!! }
            val usable = candidates.filter { (_, row) -> supported(row) && numeric(row) != null && SignalLearning.fresh(row) }
            val compatible = usable.filter { (_, row) -> compatibleWindow(target, row) }
            val grouped = compatible.groupBy { (_, row) -> if (row.period == "day") "day:${row.date ?: row.measuredAt}" else "time:${row.measuredAt}" }
            var duplicates = 0
            var conflicts = 0
            val unique = grouped.values.mapNotNull { rows ->
                if (rows.map { numeric(it.second) }.distinct().size != 1) { conflicts += rows.size; return@mapNotNull null }
                duplicates += rows.size - 1
                rows.maxWith(compareBy<Pair<SignalRecord, SignalObservation>> { it.second.collectedAt }.thenBy { it.first.id })
            }.sortedByDescending { it.second.measuredAt }
            val nonoverlapping = mutableListOf<Pair<SignalRecord, SignalObservation>>()
            var overlaps = 0
            unique.forEach { candidate ->
                val row = candidate.second
                if (nonoverlapping.any { overlap(row, it.second) }) overlaps++ else nonoverlapping += candidate
            }
            val selected = nonoverlapping.take(limit).sortedBy { it.second.measuredAt }
            val sourceOmissions = candidates.map { it.first }.distinctBy { it.id }.sumOf { record ->
                record.coverage.filter { it.key == key.key }.sumOf { it.omitted.coerceAtLeast(0) }
            }
            SignalTrendSeries(key, selected.map { (record, row) -> SignalTrendPoint(record.id, row.id, row.measuredAt!!,
                numeric(row)!!, record.sessionId.ifBlank { record.id }, row.windowStart, row.windowEnd, row.sampleCount) },
                candidates.size - usable.size, usable.size - compatible.size, duplicates, conflicts, overlaps,
                (nonoverlapping.size - limit).coerceAtLeast(0), sourceOmissions)
        }.sortedWith(compareByDescending<SignalTrendSeries> { it.points.size }.thenBy { it.identity.key }.thenBy { it.identity.metric })
    }

    private fun eligible(record: SignalRecord, enabled: Set<String>) = record.state == "ready" && record.kind in originalKinds &&
        record.references.isEmpty() && SignalHistory.allowed(record, enabled)

    private fun numeric(row: SignalObservation) = (row.number ?: row.value.toDoubleOrNull())?.takeIf { it.isFinite() }

    private fun supported(row: SignalObservation): Boolean {
        // Scan slots, daily counter resets and angular wraparound cannot establish scalar trends.
        if (row.key == "wifi" || row.key.startsWith("wifi.") || row.key == "bluetooth" || row.key.startsWith("bluetooth.") ||
            row.key == "cellular" || row.key.startsWith("cellular.") || row.key.startsWith("presence.")) return false
        if (row.period == "since_reboot" || row.metric == "coverage" || "rotation_vector" in row.identity ||
            row.identity == "android.sensor.orientation" || row.key == "watch.compass") return false
        if (row.period == "endpoint_interval" && row.fields["bootScope"].isNullOrBlank()) return false
        return row.sampleCount?.let { it > 0 } != false && row.accuracy?.let { it > 0 } != false
    }

    private fun identity(record: SignalRecord, row: SignalObservation) = SignalTrendIdentity(row.key, row.metric, row.unit, row.source,
        row.fields["deviceId"]?.takeIf(String::isNotBlank) ?: row.fields["device"]?.takeIf(String::isNotBlank) ?: when {
            row.source.startsWith("health_connect:") -> listOf("deviceManufacturer", "deviceModel", "deviceType")
                .joinToString(" · ") { row.fields[it]?.takeIf(String::isNotBlank) ?: "unknown" }
            row.source == "phone" -> "This phone"
            record.watchId.isNotBlank() -> record.watchId
            else -> "Device not recorded"
        }, row.identity, row.period, row.fields["recordingMethod"]?.takeIf(String::isNotBlank)
            ?: if (row.source.startsWith("health_connect:")) "unknown" else "", row.fields["bootScope"].orEmpty())

    private fun duration(row: SignalObservation): Long? {
        val start = row.windowStart ?: return null
        val end = row.windowEnd ?: return null
        return (end - start).takeIf { end >= start && it >= 0 }
    }

    private fun compatibleWindow(a: SignalObservation, b: SignalObservation): Boolean {
        if (a.windowStart == null && a.windowEnd == null && b.windowStart == null && b.windowEnd == null) return true
        val first = duration(a) ?: return false
        val second = duration(b) ?: return false
        return if (a.period == "sample_window") abs(first - second) <= maxOf(250L, maxOf(first, second) / 10) else first == second
    }

    private fun overlap(a: SignalObservation, b: SignalObservation): Boolean {
        val aStart = a.windowStart ?: return false
        val aEnd = a.windowEnd ?: return false
        val bStart = b.windowStart ?: return false
        val bEnd = b.windowEnd ?: return false
        return aStart < aEnd && bStart < bEnd && aStart < bEnd && bStart < aEnd
    }
}
