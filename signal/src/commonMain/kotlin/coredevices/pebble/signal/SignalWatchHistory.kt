package coredevices.pebble.signal

import kotlinx.serialization.json.*

internal data class SignalWatchMinute(val steps: Int, val movement: Int, val orientation: Int, val light: Int?, val heartRate: Int?)

internal data class SignalWatchMinuteHistory(
    val requestedStart: Long, val requestedEnd: Long, val minutes: List<SignalWatchMinute?>,
) {
    val validMinutes get() = minutes.count { it != null }
}

internal object SignalWatchHistory {
    const val KEY = "watch.minute_history"
    const val PERIOD = "recent_15_minutes"
    val source = SignalSource(KEY, "Recent movement, light & heart rate (15 min)", "Watch")
    const val description = "Reads completed minute records already stored by the watch: steps, movement, orientation, light categories and heart rate where available. Requires Pebble Health access. Choose this switch separately."
    private val columns = listOf("steps", "vmc", "orientation", "light", "heart_rate_bpm")
    private val fields = setOf("schema", "requested_minutes", "returned_minutes", "valid_minutes", "requested_start_ms", "requested_end_ms", "columns", "minutes")

    fun parse(value: SignalObservation): SignalWatchMinuteHistory? {
        if (value.key != KEY || value.period != PERIOD || value.unit != "minute_records" ||
            value.value.encodeToByteArray().size > 1000 || value.number != null || value.boolean != null) return null
        val data = runCatching { Json.parseToJsonElement(value.value) as? JsonObject }.getOrNull() ?: return null
        if (data.keys != fields || data.integer("schema") != 1L || data.integer("requested_minutes") != 15L) return null
        val names = data["columns"] as? JsonArray ?: return null
        if (names.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } != columns) return null
        val returned = data.integer("returned_minutes")?.takeIf { it in 0..15 }?.toInt() ?: return null
        val valid = data.integer("valid_minutes")?.takeIf { it in 0..returned }?.toInt() ?: return null
        val start = data.integer("requested_start_ms") ?: return null
        val end = data.integer("requested_end_ms") ?: return null
        if (start <= 0 || end <= start || end - start != 900_000L || start % 60_000 != 0L || end % 60_000 != 0L) return null
        val rows = data["minutes"] as? JsonArray ?: return null
        if (rows.size != returned) return null
        val minutes = mutableListOf<SignalWatchMinute?>()
        for (row in rows) {
            if (row == JsonNull) { minutes += null; continue }
            val cells = row as? JsonArray ?: return null
            if (cells.size != 5) return null
            val steps = cells[0].integer()?.takeIf { it in 0..255 }?.toInt() ?: return null
            val movement = cells[1].integer()?.takeIf { it in 0..65535 }?.toInt() ?: return null
            val orientation = cells[2].integer()?.takeIf { it in 0..255 }?.toInt() ?: return null
            val light = if (cells[3] == JsonNull) null else cells[3].integer()?.takeIf { it in 1..4 }?.toInt() ?: return null
            val heartRate = if (cells[4] == JsonNull) null else cells[4].integer()?.takeIf { it in 1..255 }?.toInt() ?: return null
            minutes += SignalWatchMinute(steps, movement, orientation, light, heartRate)
        }
        if (minutes.count { it != null } != valid) return null
        if (returned == 0) {
            if (value.windowStart != null || value.windowEnd != null || value.measuredAt != null ||
                value.status !in setOf("unavailable", "permission_denied", "not_supported")) return null
        } else {
            val actualStart = value.windowStart ?: return null
            val actualEnd = value.windowEnd ?: return null
            if (actualStart < start || actualEnd > end || actualEnd <= actualStart ||
                actualEnd - actualStart != returned * 60_000L || actualStart % 60_000 != 0L || actualEnd % 60_000 != 0L) return null
            if (valid > 0) {
                if (value.status != "available" || value.measuredAt != actualEnd) return null
            } else if (value.status != "unavailable" || value.measuredAt != null) return null
        }
        return SignalWatchMinuteHistory(start, end, minutes)
    }

    fun validate(value: SignalObservation, received: Long): Boolean {
        val data = parse(value) ?: return false
        return data.requestedStart >= received - 10L * 24 * 60 * 60 * 1000 && data.requestedEnd <= received + 60_000
    }

    fun coverage(value: SignalObservation): String? {
        if (value.key != KEY) return null
        return when (parse(value)?.validMinutes) {
            15 -> "recorded for a period"
            in 1..14 -> "partial"
            else -> "unavailable"
        }
    }

    fun describe(value: SignalObservation): String? {
        val data = parse(value) ?: return null
        return buildString {
            append("${data.validMinutes} valid minutes · ${data.minutes.size - data.validMinutes} invalid · ${15 - data.minutes.size} missing of 15 requested.\n")
            append("Requested ${signalDateTime(data.requestedStart)} through ${signalDateTime(data.requestedEnd)}.\n")
            if (data.minutes.isEmpty()) append("No minute records returned.\n")
            data.minutes.forEachIndexed { index, minute ->
                append(signalDateTime(value.windowStart!! + index * 60_000L))
                if (minute == null) append(": invalid record; readings unknown.\n")
                else {
                    append(": ${minute.steps} steps; movement count ${minute.movement}; orientation code ${minute.orientation}; ")
                    append("light ${lightName(minute.light)}; ")
                    append(minute.heartRate?.let { "heart rate $it bpm" } ?: "heart rate unknown")
                    append(".\n")
                }
            }
            append("Light uses categories from very dark to very light; orientation uses the watch's own codes. Missing records do not establish inactivity or continuous wear.")
        }
    }

    private fun lightName(value: Int?) = when (value) {
        1 -> "very dark"; 2 -> "dark"; 3 -> "light"; 4 -> "very light"; else -> "unknown"
    }

    private fun JsonElement.integer(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.integer(key: String): Long? = get(key)?.integer()
}

internal fun signalWatchMotionDescription(value: SignalObservation): String? {
    if (value.key != "watch.motion") return null
    val data = runCatching { Json.parseToJsonElement(value.value) as? JsonObject }.getOrNull() ?: return null
    fun count(key: String) = (data[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val samples = count("samples") ?: return null
    val variance = (data["variance_mg2"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: return null
    return buildString {
        append("$samples accepted samples")
        count("received_samples")?.let { append(" of $it received") }
        if (samples > 0) {
            append(". Movement variance: $variance mg².\n")
            append("Higher variance means the acceleration readings changed more during this capture.\n")
        } else append(". No usable acceleration measurement.\n")
        val exclusions = listOf("vibration_excluded" to "during vibration", "timestamp_rejected" to "with invalid timestamps", "capacity_excluded" to "over the sample limit")
        exclusions.mapNotNull { (key, label) -> count(key)?.let { "$it $label" } }.takeIf { it.isNotEmpty() }
            ?.let { append("Excluded: ${it.joinToString()}.\n") }
        val first = count("first_sample_ms"); val last = count("last_sample_ms")
        if (samples > 0 && first != null && last != null && first > 0 && last >= first) append("Watch SDK timestamps span ${last - first} ms.\n")
        val hz = count("requested_hz"); val duration = count("requested_duration_ms")
        if (hz != null && duration != null) append("Requested $hz samples/second for $duration ms.")
    }.trim()
}
