package coredevices.pebble.signal

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json

object SignalCapture {
    // Local originals are independent of the much smaller model/context allowance.
    const val MAX_READINGS = 2048
    const val MAX_READING_BYTES = 1024 * 1024
    private val json = Json { encodeDefaults = true }
    fun retain(readings: List<SignalObservation>): SignalBudgetResult =
        SignalBudget.retain(readings, MAX_READINGS, MAX_READING_BYTES) { json.encodeToString(it).encodeToByteArray().size }

    fun summary(budget: SignalBudgetResult) = summary(budget.observations, budget.omitted, budget.collectorOmitted)
    fun summary(readings: List<SignalObservation>, omitted: Int = 0, collectorOmitted: Int = 0): String {
        val available = readings.count { reading ->
            SignalWatchHistory.coverage(reading)?.let { it == "recorded for a period" }
                ?: (reading.status in setOf("available", "fresh"))
        }
        return "Capture saved on phone.\n${readings.size} readings; $available available, ${readings.size - available} unavailable or partial." +
            (if (omitted > collectorOmitted) "\n${omitted - collectorOmitted} readings not saved: phone capture limit." else "") +
            (if (collectorOmitted > 0) "\nAt least $collectorOmitted additional radio results were not retained during scanning." else "") +
            (if (omitted > 0) "\nOpen this capture in phone History for source coverage." else "") +
            (if (readings.isEmpty()) "\nChoose sources in phone Settings to collect readings." else "") +
            "\nNo model request was made. Open Ask on your phone to chat about saved readings."
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
