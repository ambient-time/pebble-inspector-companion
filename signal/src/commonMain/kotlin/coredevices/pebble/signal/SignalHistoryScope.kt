package coredevices.pebble.signal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Dates refer to when a record was saved, never the dates of individual measurements. */
@Serializable
data class SignalHistoryQuery(
    val text: String = "", val kind: String = "all", val fromDate: String = "", val throughDate: String = "",
    val sourceKeys: Set<String> = emptySet(), val zoneId: String = TimeZone.currentSystemDefault().id,
)

data class SignalScopedHistory(
    val query: SignalHistoryQuery = SignalHistoryQuery(), val records: List<SignalRecord> = emptyList(),
    val recordIds: Set<String> = emptySet(), val matchingCount: Int = 0, val loading: Boolean = false,
    val error: String = "", val updatedAt: Long = 0,
) { val hasMore: Boolean get() = records.size < matchingCount }

/** Ephemeral revision digests freeze the exact selection; recipes persist queries or IDs instead. */
@Serializable
data class SignalEvidenceSelection(
    val recordIds: Set<String>, val query: SignalHistoryQuery?, val sourceKeys: Set<String>,
    val matchedCount: Int, val revisions: Map<String, String> = emptyMap(),
    val projectionQuery: SignalHistoryQuery = query ?: SignalHistoryQuery(),
)

object SignalHistoryScope {
    fun validate(query: SignalHistoryQuery) {
        val from = query.fromDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        val through = query.throughDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
        require(from == null || through == null || from <= through) { "Start date must not follow end date." }
        TimeZone.of(query.zoneId)
    }
    fun sources(query: SignalHistoryQuery, enabled: Set<String>) =
        if (query.sourceKeys.isEmpty()) enabled else query.sourceKeys.intersect(enabled)

    /** Source-restricted derived prose is indivisible; original raw captures may be projected. */
    fun project(record: SignalRecord, query: SignalHistoryQuery, enabled: Set<String>): SignalRecord? {
        validate(query)
        val date = Instant.fromEpochMilliseconds(record.createdAt).toLocalDateTime(TimeZone.of(query.zoneId)).date
        if (query.fromDate.isNotBlank() && date < LocalDate.parse(query.fromDate)) return null
        if (query.throughDate.isNotBlank() && date > LocalDate.parse(query.throughDate)) return null
        val kindMatches = when (query.kind) {
            "all" -> true
            "captures" -> record.kind in setOf("capture", "observation", "health_import") || record.provider == "local" && record.observations.isNotEmpty()
            "conversations" -> record.kind == "analysis" && record.provider != "local"
            "reports" -> record.kind !in setOf("capture", "observation", "analysis", "health_import")
            else -> record.kind == query.kind
        }
        if (!kindMatches) return null
        val allowed = sources(query, enabled)
        val readings = record.observations.filter { it.key in allowed }
        val restricted = query.sourceKeys.isNotEmpty() && record.observations.isNotEmpty() || !SignalHistory.allowed(record, allowed)
        if (query.sourceKeys.isNotEmpty() && record.observations.isEmpty() && record.sourceKeys.isEmpty()) return null
        if (restricted && readings.isEmpty()) return null
        // References and derived metadata can disclose excluded readings even when prose is blank.
        val projected = if (restricted) record.copy(question = "Selected observations", answer = "", summary = "",
            observations = readings, sourceKeys = readings.map { it.key }.toSet(), references = emptyList(),
            memoryReferences = emptyMap(), evidenceScope = null, fieldTest = null, watchId = "", endpoint = "",
            coverage = record.coverage.filter { it.key in allowed }) else record
        val needle = query.text.trim().lowercase()
        if (needle.isNotEmpty() && needle !in (projected.question + " " + projected.answer + " " + projected.observations.joinToString { "${it.key} ${it.value}" }).lowercase()) return null
        return projected
    }

    fun comparable(records: List<SignalRecord>): Boolean = records.size == 2 && records.all {
        it.state == "ready" && it.observations.isNotEmpty() && (it.kind in setOf("capture", "observation", "health_import") || it.provider == "local")
    } && records[0].observations.map { it.key }.intersect(records[1].observations.map { it.key }.toSet()).isNotEmpty()
}
