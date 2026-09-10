package coredevices.pebble.signal

/** Local comparisons describe measurements, never inferred events between captures. */
object SignalChanges {
    private fun scope(record: SignalRecord) = record.sourceKeys + record.observations.map { it.key }
    private fun eligible(record: SignalRecord, enabled: Set<String>) = record.state == "ready" &&
        record.kind in setOf("capture", "presence", "observation", "health_import") && record.references.isEmpty() && SignalHistory.allowed(record, enabled)
    private fun family(record: SignalRecord) = if (record.kind in setOf("capture", "observation")) "snapshot" else record.kind

    fun baseline(current: SignalRecord, records: List<SignalRecord>, enabled: Set<String>): SignalRecord? {
        if (!eligible(current, enabled)) return null
        return records.filter { it.id != current.id && it.createdAt < current.createdAt &&
            family(it) == family(current) && it.watchId == current.watchId && scope(it).intersect(scope(current)).isNotEmpty() && eligible(it, enabled)
        }.maxByOrNull { it.createdAt }
    }

    /** Caller supplies a new durable ID and wall time; null means no compatible eligible baseline. */
    fun create(current: SignalRecord, records: List<SignalRecord>, enabled: Set<String>, id: String, createdAt: Long): SignalRecord? {
        val previous = baseline(current, records, enabled) ?: return null
        val before = previous.observations.groupBy(::identity)
        val after = current.observations.groupBy(::identity)
        val changes = mutableListOf<String>()
        val commonSources = scope(previous).intersect(scope(current))
        var unchanged = 0
        var unknown = 0
        for (key in before.keys + after.keys) {
            val old = before[key]?.singleOrNull()
            val next = after[key]?.singleOrNull()
            if (old == null || next == null || !comparable(old) || !comparable(next) || !compatibleWindow(old, next)) { unknown++; continue }
            if (old.measuredAt != null && next.measuredAt != null && next.measuredAt <= old.measuredAt && (old.value != next.value || old.status != next.status)) { unknown++; continue }
            val title = "${if (next.key.startsWith("presence.") && next.identity.isNotBlank()) next.value.substringBefore(": ${next.status}").take(100) else (next.key + " " + next.metric).trim().replace('.', ' ').replace('_', ' ')}${next.date?.let { " ($it)" }.orEmpty()}"
            if (next.key.startsWith("presence.")) {
                if (old.status == next.status) unchanged++
                else changes += "$title: ${old.status} → ${next.status}. This is a change in observations, not proof of arrival or departure."
            } else {
                val a = (old.number ?: old.value.toDoubleOrNull())?.takeIf { it.isFinite() }
                val b = (next.number ?: next.value.toDoubleOrNull())?.takeIf { it.isFinite() }
                if (a != null && b != null) {
                    if (!(b - a).isFinite()) { unknown++; continue }
                    if (a == b) unchanged++ else changes += "$title: $a → $b ${next.unit} (difference ${b - a})."
                } else if (next.key == "device.charging" && old.value in setOf("true", "false") && next.value in setOf("true", "false")) {
                    if (old.value == next.value) unchanged++ else changes += "$title: ${old.value} → ${next.value}."
                } else unknown++
            }
        }
        val text = buildString {
            append("Compared captures from ${signalDateTime(previous.createdAt)} and ${signalDateTime(current.createdAt)} (phone local time).\n")
            append("${changes.size} changed; $unchanged unchanged; $unknown unknown or not comparable.\n")
            val added = scope(current) - commonSources
            val removed = scope(previous) - commonSources
            if (added.isNotEmpty()) append("Newly included sources: ${added.sorted().joinToString()}.\n")
            if (removed.isNotEmpty()) append("Sources absent from the newer record: ${removed.sorted().joinToString()}.\n")
            val omitted = (previous.coverage + current.coverage).sumOf { it.omitted }
            if (omitted > 0) append("$omitted readings were omitted from these saved records; comparisons cover retained evidence.\n")
            changes.take(40).forEach { append(it); append('\n') }
            if (changes.size > 40) append("${changes.size - 40} additional changes omitted from this summary.\n")
            append("Only matching sources, watch, metric, unit and measurement periods are compared. Cached, missing, duplicate and old readings are unknown. No continuous coverage or model request.")
        }
        return SignalRecord(id, current.threadId, createdAt, "What changed?", answer = text,
            summary = SignalProviders.truncateUtf8(text, 900), provider = "local", model = "", watchId = current.watchId,
            state = "ready", sourceKeys = scope(previous) + scope(current), references = listOf(previous.id, current.id), kind = "changes")
    }

    private fun identity(o: SignalObservation) = listOf(o.key, o.source, o.identity, o.metric, o.unit, o.date.takeIf { o.period == "day" }, o.period)
    private fun compatibleWindow(a: SignalObservation, b: SignalObservation): Boolean {
        if (a.windowStart == null && a.windowEnd == null && b.windowStart == null && b.windowEnd == null) return true
        val da = a.windowEnd?.let { end -> a.windowStart?.let { end - it } } ?: return false
        val db = b.windowEnd?.let { end -> b.windowStart?.let { end - it } } ?: return false
        if (da < 0 || db < 0) return false
        // Sensor delivery can jitter within the same bounded sampling window.
        return if (a.period == "sample_window") kotlin.math.abs(da - db) <= maxOf(250L, maxOf(da, db) / 10) else da == db
    }
    private fun comparable(o: SignalObservation): Boolean {
        // Scan ordinals and labels are not durable device identities. Legacy presence has no identity.
        if (o.key.startsWith("presence.")) {
            if (o.identity.isEmpty() || o.identity.endsWith(":wifi")) return false
            if (o.status == "not_observed") return o.identity.startsWith("target:")
            return o.status in setOf("observed", "inside", "outside") && timely(o)
        }
        if (o.key == "bluetooth" || o.key.startsWith("bluetooth.") || o.key == "wifi" || o.key.startsWith("wifi.")) return false
        if (o.source.startsWith("health_connect:")) return SignalLearning.fresh(o)
        return o.status in setOf("fresh", "available") && timely(o)
    }
    private fun timely(o: SignalObservation): Boolean {
        val measured = o.measuredAt ?: return false
        if (measured > o.collectedAt) return false
        return if (o.period == "day" && o.date != null) true else o.collectedAt - measured in 0..60_000
    }
}
