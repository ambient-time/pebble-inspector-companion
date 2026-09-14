package coredevices.pebble.signal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Validate even trusted transport data before it can become evidence or durable history. */
object SignalWatchValidation {
    fun validate(value: SignalObservation, enabled: Set<String>, supported: Set<String>, received: Long): SignalObservation? {
        if (value.key !in supported || value.key !in enabled || value.source != "watch" ||
            value.value.encodeToByteArray().size > 1000 || value.unit.length > 32 ||
            value.status !in setOf("fresh", "available", "unavailable", "permission_denied", "timestamp_unknown", "calibrating", "not_supported", "not_available") ||
            (value.period !in setOf("current", "today", "day", "last_completed_sleep", "last_completed_sleep_2h_heuristic", "5_second_sample") &&
                !(value.key == SignalWatchHistory.KEY && value.period == SignalWatchHistory.PERIOD))) return null
        if (value.key == SignalWatchHistory.KEY && !SignalWatchHistory.validate(value, received)) return null
        val earliest = received - 10L * 24 * 60 * 60 * 1000
        if (value.measuredAt != null && value.measuredAt !in earliest..(received + 60_000)) return null
        if (value.windowStart != null && value.windowStart !in earliest..(received + 60_000)) return null
        if (value.windowEnd != null && value.windowEnd !in earliest..(received + 24 * 60 * 60 * 1000)) return null
        if (value.windowStart != null && value.windowEnd != null && value.windowStart > value.windowEnd) return null
        if (value.date != null) {
            val date = runCatching { LocalDate.parse(value.date) }.getOrNull() ?: return null
            // Tolerate watch/phone timezone offsets, without allowing arbitrary historical labels.
            val first = Instant.fromEpochMilliseconds(earliest).toLocalDateTime(TimeZone.UTC).date
            val last = Instant.fromEpochMilliseconds(received + 24 * 60 * 60 * 1000).toLocalDateTime(TimeZone.UTC).date
            if (date < first || date > last) return null
        }
        if (value.period == "day" && value.date == null) return null
        return value.copy(collectedAt = received, measuredAt = if (value.status == "timestamp_unknown") null else value.measuredAt)
    }
}
