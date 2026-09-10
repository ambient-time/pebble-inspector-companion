package coredevices.pebble.signal

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

data class ProjectedContext(val original: SignalRecord, val excerpt: SignalRecord, val score: Int)

/** Derived prose is indivisible: every contributing source must still be enabled. */
object SignalHistory {
    fun allowed(record: SignalRecord, enabled: Set<String>): Boolean =
        (record.sourceKeys + record.observations.map { it.key }).all { it in enabled }

    fun retrieve(
        records: List<SignalRecord>, query: String, enabled: Set<String>, limit: Int = 30,
        now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<SignalRecord> = project(records, query, enabled, limit, now, zone).map { it.excerpt }

    fun project(
        records: List<SignalRecord>, query: String, enabled: Set<String>, limit: Int = 30,
        now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<ProjectedContext> {
        if (limit <= 0) return emptyList()
        val dates = dateWindow(query, Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date)
        if (!dates.valid) return emptyList()
        val lower = query.lowercase()
        val sourceHints = sourceAliases.filter { (_, words) -> words.any { word -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower) } }.keys + enabled.filter { key -> Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(lower) }
        val terms = lower.replace(Regex("\\d{4}-\\d{2}-\\d{2}"), " ").split(Regex("[^\\p{L}\\p{N}._-]+"))
            .filter { it.length > 2 && it !in stopWords && it.toIntOrNull() == null }
        // Topological provenance check: cycles, missing references and disabled ancestors
        // stay ineligible. Avoid recursion/exponential walks through long histories.
        val byId = records.associateBy { it.id }
        val remaining = records.associate { it.id to it.references.distinct().size }.toMutableMap()
        val dependents = mutableMapOf<String, MutableSet<String>>()
        records.forEach { record -> record.references.distinct().forEach { ref -> dependents.getOrPut(ref) { mutableSetOf() }.add(record.id) } }
        val safeIds = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        records.filter { it.references.isEmpty() && allowed(it, enabled) }.forEach { queue.addLast(it.id) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!safeIds.add(id)) continue
            dependents[id].orEmpty().forEach { dependent ->
                remaining[dependent] = remaining.getValue(dependent) - 1
                if (remaining[dependent] == 0 && byId[dependent]?.let { allowed(it, enabled) } == true) queue.addLast(dependent)
            }
        }
        return records.asSequence().filter { it.state == "ready" && it.id in safeIds }.mapNotNull { record ->
            val readings = record.observations.filter { observation ->
                val date = observation.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: Instant.fromEpochMilliseconds(observation.measuredAt ?: observation.collectedAt).toLocalDateTime(zone).date
                dates.contains(date) && (sourceHints.isEmpty() || sourceHints.any { observation.key == it || observation.key.startsWith("$it.") })
            }
            val recordDate = Instant.fromEpochMilliseconds(record.createdAt).toLocalDateTime(zone).date
            if (record.observations.isEmpty()) {
                if (sourceHints.isNotEmpty() || !dates.contains(recordDate)) return@mapNotNull null
            } else if (readings.isEmpty()) return@mapNotNull null
            val text = "${record.question} ${record.answer} ${readings.joinToString { "${it.key} ${it.value}" }}".lowercase()
            val score = terms.count { text.contains(it) }
            if (terms.isNotEmpty() && score == 0 && sourceHints.isEmpty()) return@mapNotNull null
            // A dated/metric excerpt cannot safely reuse prose derived from a broader snapshot.
            val excerpt = if ((dates.constrained || sourceHints.isNotEmpty()) && record.observations.isNotEmpty())
                record.copy(observations = readings, question = "Selected observations", answer = "", summary = "") else record
            ProjectedContext(record, excerpt, score)
        }.sortedWith(order).take(limit).toList()
    }

    val order = compareByDescending<ProjectedContext> { it.score }.thenByDescending { it.original.createdAt }.thenBy { it.original.id }

    /** Latest watch + metric + date + period wins; missing and invalid values remain unknown. */
    fun summarize(records: List<SignalRecord>, enabled: Set<String>): String {
        val unique = records.filter { allowed(it, enabled) }.sortedByDescending { it.createdAt }.flatMap { record ->
            record.observations.filter { it.key in enabled && it.period == "day" && it.date != null }
                .map { Triple(record.watchId.ifBlank { it.source }, record.id, it) }
        }.distinctBy { (watch, _, o) -> listOf(watch, o.source, o.key, o.date, o.period) }
        return unique.groupBy { (watch, _, o) -> listOf(watch, o.key, o.unit) }.map { (key, rows) ->
            val valid = rows.mapNotNull { row ->
                row.third.value.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number >= 0 && (row.third.key != "health.heart_rate" || number > 0) && row.third.status in setOf("available", "fresh") }?.let { row to it }
            }
            val values = valid.map { it.second }
            val additive = key[1] in additiveMetrics
            "${key.joinToString("/")}: observedDays=${values.size}, reportedDays=${rows.size}, unknownDays=${rows.size - values.size}, " +
                (if (additive && values.isNotEmpty()) "total=${values.sum()}, " else "") +
                "unweightedDailyMean=${if (values.isEmpty()) "unavailable" else values.average()}, " +
                "fullWearCoverage=unknown, dates=${valid.map { it.first.third.date }.sortedBy { it }.joinToString()}, " +
                "refs=${rows.map { it.second }.distinct().joinToString()}"
        }.joinToString("\n")
    }

    fun deletionClosure(records: List<SignalRecord>, initial: Set<String>): Set<String> {
        var ids = initial
        do { val previous = ids; ids = ids + records.filter { it.references.any { ref -> ref in ids } }.map { it.id } } while (ids != previous)
        return ids
    }

    private data class Window(val start: LocalDate? = null, val end: LocalDate? = null, val valid: Boolean = true) {
        val constrained get() = start != null || end != null
        fun contains(date: LocalDate) = valid && (start == null || date >= start) && (end == null || date <= end)
    }
    private fun dateWindow(query: String, today: LocalDate): Window {
        val text = query.lowercase()
        val matches = Regex("\\d{4}-\\d{2}-\\d{2}").findAll(text).toList()
        val dates = matches.map { runCatching { LocalDate.parse(it.value) }.getOrNull() }
        if (dates.any { it == null }) return Window(valid = false)
        val valid = dates.filterNotNull()
        if (valid.size > 1) return Window(valid.min(), valid.max())
        if (valid.size == 1) {
            val date = valid.single()
            val prefix = text.take(matches.single().range.first).trimEnd()
            return when {
                prefix.endsWith("before") -> Window(end = date.minus(1, DateTimeUnit.DAY))
                prefix.endsWith("after") -> Window(start = date.plus(1, DateTimeUnit.DAY))
                prefix.endsWith("since") || prefix.endsWith("from") -> Window(start = date)
                prefix.endsWith("until") || prefix.endsWith("through") -> Window(end = date)
                else -> Window(date, date)
            }
        }
        val monday = today.minus(today.dayOfWeek.ordinal, DateTimeUnit.DAY)
        return when {
            "yesterday" in text -> today.minus(1, DateTimeUnit.DAY).let { Window(it, it) }
            "today" in text -> Window(today, today)
            "last week" in text -> Window(monday.minus(7, DateTimeUnit.DAY), monday.minus(1, DateTimeUnit.DAY))
            "this week" in text -> Window(monday, today)
            else -> {
                val days = Regex("(?:last|past) (\\d{1,3}) days?").find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (days != null && days > 0) Window(today.minus(days - 1, DateTimeUnit.DAY), today) else Window()
            }
        }
    }
    private val additiveMetrics = setOf("health.steps", "health.active_seconds", "health.distance", "health.active_calories", "health.resting_calories", "health.sleep", "health.restful_sleep")
    private val sourceAliases = mapOf(
        "health.steps" to listOf("steps", "step count"), "health.distance" to listOf("distance", "walked", "walking"),
        "health.active_seconds" to listOf("active", "active minutes", "exercise"),
        "health.sleep" to listOf("sleep", "slept"), "health.restful_sleep" to listOf("sleep", "restful"),
        "health.heart_rate" to listOf("heart rate", "pulse", "bpm"), "health.activity" to listOf("activity"),
        "health.active_calories" to listOf("calories", "energy"), "health.resting_calories" to listOf("calories", "energy"),
        "wifi" to listOf("wifi", "wi-fi", "wireless", "access points"), "bluetooth" to listOf("bluetooth", "ble", "beacons"),
        "weather" to listOf("weather"),
        "weather.current" to listOf("temperature", "humidity", "wind"), "weather.forecast" to listOf("forecast", "rain", "precipitation"),
        "weather.daylight" to listOf("sunrise", "sunset", "daylight"), "weather.air_quality" to listOf("air quality", "aqi", "pollution"),
        "weather.uv" to listOf("uv", "ultraviolet"),
        "location" to listOf("location", "coordinates"), "device" to listOf("device"),
        "device.battery" to listOf("battery"), "watch.battery" to listOf("battery"), "device.charging" to listOf("charging"),
        "sensor" to listOf("sensors", "motion", "pressure", "light", "temperature"), "watch.motion" to listOf("motion"),
    )
    private val stopWords = setOf("what", "when", "where", "which", "with", "from", "have", "has", "history", "compare", "about", "show", "this", "that", "these", "those", "the", "and", "for", "how", "was", "were", "been", "doing", "did", "does", "look", "tell", "saved", "records", "survey", "surveys", "today", "yesterday", "last", "past", "days", "day", "week", "since", "before", "after", "until", "through", "between", "all", "recent", "recently", "lately")
}
