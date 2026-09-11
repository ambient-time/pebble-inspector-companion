package coredevices.pebble.signal

/** Describes quality at collection time, never promises that a saved reading is current. */
internal fun signalObservationCoverage(record: SignalRecord): String {
    if (record.observations.isEmpty()) return "No readings saved."
    val groups = record.observations.filter { it.metric != "coverage" }.groupingBy { reading ->
        when (reading.status) {
            "estimate", "coarse_estimate", "candidate", "modeled", "forecast" -> "estimated or modeled"
            "cached", "stale" -> "older or cached"
            "partial" -> "partial"
            "recorded" -> "recorded for a period"
            "timestamp_unknown" -> "age unknown"
            "available", "fresh", "observed", "inside", "outside" -> when {
                reading.measuredAt == null -> "age unknown"
                SignalLearning.fresh(reading) -> "fresh at capture"
                else -> "older or time uncertain"
            }
            else -> "unavailable"
        }
    }.eachCount()
    val order = listOf("fresh at capture", "recorded for a period", "estimated or modeled", "older or cached",
        "older or time uncertain", "age unknown", "partial", "unavailable")
    return "Readings: " + order.mapNotNull { label -> groups[label]?.let { "$it $label" } }.joinToString(" · ").ifBlank { "Scan outcomes only; no measurement readings saved." }
}
