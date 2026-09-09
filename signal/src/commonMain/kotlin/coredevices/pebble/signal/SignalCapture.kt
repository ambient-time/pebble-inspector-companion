package coredevices.pebble.signal

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

object SignalCapture {
    fun summary(readings: List<SignalObservation>, omitted: Int = 0): String {
        val available = readings.count { it.status in setOf("available", "fresh") }
        return "Capture saved on phone.\n${readings.size} readings; $available available, ${readings.size - available} unavailable or partial." +
            (if (omitted > 0) "\n$omitted readings omitted by the size limit." else "") +
            (if (readings.isEmpty()) "\nChoose sources in phone Settings to collect readings." else "") +
            "\nNo model request was made. Analyze later in phone History."
    }

    fun watchHistory(records: List<SignalRecord>, enabled: Set<String>, watch: String): String {
        val recent = records.filter { it.state == "ready" && (it.watchId.isEmpty() || it.watchId == watch) && SignalHistory.allowed(it, enabled) }
            .sortedByDescending { it.createdAt }.take(5)
        if (recent.isEmpty()) return "No recent eligible history.\nCapture or ask to save your first record. Full history is on your phone."
        val text = "Recent history\n" + recent.joinToString("\n\n") { record ->
            val date = Instant.fromEpochMilliseconds(record.createdAt).toLocalDateTime(TimeZone.currentSystemDefault())
            val stamp = "${date.date} ${date.hour.toString().padStart(2, '0')}:${date.minute.toString().padStart(2, '0')}"
            val excerpt = if (record.kind == "capture") "Capture: ${record.observations.size} readings saved" else record.summary.ifBlank { record.answer }
            "$stamp\n${SignalProviders.truncateUtf8(excerpt.replace('\n', ' '), 115)}"
        } + "\n\nFull history is on your phone."
        return SignalProviders.truncateUtf8(text, 900)
    }
}
