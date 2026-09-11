package coredevices.pebble.signal

import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Serializable
data class SignalEvidence(val recordId: String, val observationIds: List<String>, val collectedAt: Long, val description: String)

@Serializable
data class SignalMemory(
    val id: String,
    val fingerprint: String,
    val kind: String,
    val text: String,
    val state: String = "proposed",
    val revision: Long = 1,
    val createdAt: Long,
    val evaluatedAt: Long,
    val confirmedAt: Long? = null,
    val sourceKeys: Set<String> = emptySet(),
    val evidence: List<SignalEvidence> = emptyList(),
    val coverage: String = "",
    val needsReview: Boolean = false,
    val proposedText: String = "",
    val acceptedPatternText: String = "",
    val proposedEvidence: List<SignalEvidence> = emptyList(),
    val entityIds: Set<String> = emptySet(),
)

@Serializable
data class SignalMemoryCorrection(val id: String, val memoryId: String, val createdAt: Long, val before: String, val after: String)

@Serializable
data class SignalObservationSession(
    val id: String,
    val startedAt: Long,
    val endsAt: Long,
    val sourceKeys: Set<String>,
    val state: String = "running",
    val status: String = "Sampling when Android allows.",
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val captures: Int = 0,
    val attempts: Int = 0,
    val mode: String = "standard",
)

data class SignalSourceStatus(
    val key: String, val status: String, val measuredAt: Long? = null,
    val reason: String = "", val remedy: String = "none",
    val attempted: Boolean = false, val accepted: Int = 0, val omitted: Int = 0,
    val lastSuccessAt: Long? = null,
)
data class SignalDeletionPreview(val recordIds: Set<String>, val recordCount: Int, val memories: List<SignalMemory>, val memoryId: String? = null)

object SignalLearning {
    const val WINDOW_MILLIS = 28L * 24 * 60 * 60 * 1000

    fun normalize(record: SignalRecord): SignalRecord = record.copy(
        observations = record.observations.mapIndexed { index, observation ->
            observation.copy(id = observation.id.ifBlank { "${record.id}:$index" },
                number = observation.number ?: observation.value.toDoubleOrNull()?.takeIf { it.isFinite() },
                boolean = observation.boolean ?: when (observation.value) { "true" -> true; "false" -> false; else -> null })
        }, sessionId = record.sessionId.ifBlank { record.id },
    )

    fun eligible(memory: SignalMemory, settings: SignalSettings): Boolean =
        settings.learningEnabled && memory.state in setOf("confirmed", "note") && !memory.needsReview &&
            memory.sourceKeys.all { it in settings.enabled } && memory.entityIds.all { entity ->
                when {
                    entity.startsWith("target:") -> settings.presenceTargets.any { "target:${it.id}" == entity && it.enabled }
                    entity.startsWith("fence:") -> settings.placeFences.any { "fence:${it.id}" == entity && it.enabled }
                    else -> false
                }
            }

    fun suggest(memories: List<SignalMemory>, query: String, settings: SignalSettings): List<SignalMemory> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in setOf("what", "when", "where", "about", "have", "this", "that", "the", "and", "you", "remember") }.distinct()
        if (words.isEmpty()) return emptyList()
        return memories.filter { eligible(it, settings) }.map { m -> m to words.count { it in m.text.lowercase() } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<SignalMemory, Int>> { it.second }.thenByDescending { it.first.confirmedAt ?: it.first.createdAt })
            .take(5).map { it.first }
    }

    fun fresh(observation: SignalObservation): Boolean {
        val measured = observation.measuredAt ?: return false
        if (observation.source.startsWith("health_connect:") && observation.status == "recorded")
            return observation.windowStart?.let { start -> observation.windowEnd?.let { end -> (start < end || observation.period == "instant" && start == end) && end == measured && end <= observation.collectedAt } } == true
        if (measured > observation.collectedAt || observation.status !in setOf("fresh", "available", "observed", "inside", "outside")) return false
        return if (observation.period == "day") observation.windowEnd?.let { it <= observation.collectedAt } == true
        else observation.collectedAt - measured <= 60_000
    }

    fun proposals(records: List<SignalRecord>, settings: SignalSettings, now: Long): List<SignalMemory> {
        if (!settings.learningEnabled) return emptyList()
        val zone = TimeZone.currentSystemDefault()
        fun date(at: Long) = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date.toString()
        val originals = records.filter { it.kind in setOf("capture", "presence", "observation", "health_import") && it.state == "ready" && it.references.isEmpty() &&
            now - it.createdAt in 0..WINDOW_MILLIS && SignalHistory.allowed(it, settings.enabled) }.map(::normalize)
        val result = mutableListOf<SignalMemory>()
        fun proposal(fingerprint: String, kind: String, text: String, rows: List<Pair<SignalRecord, SignalObservation>>, coverage: String, entities: Set<String> = emptySet()) {
            val evidence = rows.groupBy { it.first.id }.values.map { entries ->
                SignalEvidence(entries.first().first.id, entries.map { it.second.id }.distinct(), entries.first().first.createdAt,
                    entries.joinToString("; ") { "${it.second.key}: ${it.second.value.take(150)} ${it.second.unit}" }.take(600))
            }.sortedByDescending { it.collectedAt }
            result += SignalMemory("", fingerprint, kind, text, createdAt = now, evaluatedAt = now,
                sourceKeys = rows.map { it.second.key }.toSet(), evidence = evidence, coverage = coverage, entityIds = entities)
        }
        settings.placeFences.filter { it.enabled }.forEach { place ->
            val placeId = "fence:${place.id}"
            val located = originals.mapNotNull { r -> r.observations.firstOrNull { it.identity == placeId && it.status in setOf("inside", "outside") && fresh(it) }?.let { r to it } }
            val inside = located.filter { it.second.status == "inside" }
            settings.presenceTargets.filter { it.enabled }.forEach { target ->
                val targetId = "target:${target.id}"
                val matches = inside.mapNotNull { (r, placeReading) -> r.observations.firstOrNull { it.identity == targetId && it.status == "observed" && fresh(it) }
                    ?.let { Triple(r, placeReading, it) } }.distinctBy { it.first.sessionId }
                val days = matches.map { date(it.first.createdAt) }.distinct()
                if (matches.size >= 3 && days.size >= 2) proposal("association:$placeId:$targetId", "association",
                    "${target.label} was observed with ${place.label} in sampled checks.", matches.flatMap { listOf(it.first to it.second, it.first to it.third) },
                    "${matches.size} independent sessions across ${days.size} days in the last 28 days. This does not establish a person's presence.", setOf(placeId, targetId))
            }
            located.groupBy { Instant.fromEpochMilliseconds(it.first.createdAt).toLocalDateTime(zone).hour / 2 }.forEach { (window, rows) ->
                val days = rows.groupBy { date(it.first.createdAt) }
                val supportive = days.filter { (_, samples) -> samples.count { it.second.status == "inside" }.toDouble() / samples.size >= .8 }
                val span = rows.maxOf { it.first.createdAt } - rows.minOf { it.first.createdAt }
                if (days.size >= 5 && span >= 6L * 86_400_000 && supportive.size.toDouble() / days.size >= .8)
                    proposal("routine:$placeId:$window", "routine", "${place.label} recurs in sampled ${window * 2}:00–${window * 2 + 2}:00 checks.", rows,
                        "Observed inside on ${supportive.size} of ${days.size} sampled days. Unsampled time is unknown. Timezone: ${zone.id}.", setOf(placeId))
            }
        }
        originals.flatMap { r -> r.observations.filter { it.number != null && fresh(it) && !it.key.startsWith("wifi") && !it.key.startsWith("bluetooth") && !it.key.startsWith("presence.") && !it.key.startsWith("cellular") && it.period != "since_reboot" }
            .map { r to it } }.groupBy { (r, o) -> "${o.source}|${r.watchId}|${o.key}${o.metric.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()}|${o.unit}|${o.period}|${o.identity}${listOf("deviceManufacturer", "deviceModel", "deviceType").mapNotNull { key -> o.fields[key] }.takeIf { it.isNotEmpty() }?.joinToString(":", prefix = "|device:").orEmpty()}|${zone.id}" }.forEach { (identity, rows) ->
                val samples = rows.sortedByDescending { it.first.createdAt }.distinctBy { (_, o) -> if (o.period == "day") "${o.date}:${o.windowStart}:${o.windowEnd}" else "${o.measuredAt}" }
                val days = samples.map { (_, o) -> o.date ?: date(o.measuredAt!!) }.distinct()
                if (samples.size >= 10 && days.size >= 5) {
                    val values = samples.mapNotNull { it.second.number }.sorted()
                    val median = if (values.size % 2 == 0) values[values.size / 2 - 1] / 2 + values[values.size / 2] / 2 else values[values.size / 2]
                    val o = samples.first().second
                    proposal("baseline:$identity", "baseline", "${o.key.replace('.', ' ')} ${o.metric.replace('_', ' ')}: observed median $median ${o.unit}; range ${values.first()}–${values.last()} ${o.unit}.", samples,
                        "${values.size} comparable measurements across ${days.size} days. Origin: ${o.source}; period: ${o.period}. Descriptive observations, not a health assessment.")
                }
            }
        return result
    }
}
