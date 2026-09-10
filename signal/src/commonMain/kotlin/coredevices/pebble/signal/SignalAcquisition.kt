package coredevices.pebble.signal

import kotlin.math.sqrt

/** One acquisition feeds collection, local presence and the optional lookup review. */
data class SignalAcquisition(
    val observations: List<SignalObservation>,
    val candidates: List<SignalRadioCandidate> = emptyList(),
    val fix: SignalPresenceFix? = null,
)

object SignalAcquisitionPlan {
    fun required(enabled: Set<String>): Set<String> = enabled + buildSet {
        if ("presence.bluetooth" in enabled) add("bluetooth")
        if ("presence.wifi" in enabled) add("wifi")
        if ("presence.places" in enabled) add("location")
    }
    fun visible(readings: List<SignalObservation>, enabled: Set<String>) = readings.filter { it.key in enabled }
}

data class SignalBudgetResult(val observations: List<SignalObservation>, val coverage: List<SignalSourceCoverage>) {
    val omitted: Int get() = coverage.sumOf { it.omitted }
}

/** Round robin by independently selected source; a dense radio cannot evict health or location. */
object SignalBudget {
    fun retain(readings: List<SignalObservation>, limit: Int, maxBytes: Int = Int.MAX_VALUE,
               size: (SignalObservation) -> Int = { 0 }): SignalBudgetResult {
        require(limit >= 0)
        val groups = readings.sortedBy { it.key }.groupBy { it.key }
        val retained = mutableListOf<SignalObservation>()
        var offset = 0
        var bytes = 0L
        while (retained.size < limit && groups.values.any { it.size > offset }) {
            for (rows in groups.values) {
                if (retained.size == limit) break
                rows.getOrNull(offset)?.let { row ->
                    val weight = size(row).coerceAtLeast(0)
                    if (bytes + weight <= maxBytes) { retained += row; bytes += weight }
                }
            }
            offset++
        }
        val counts = retained.groupingBy { it.key }.eachCount()
        return SignalBudgetResult(retained, groups.map { (key, rows) ->
            val kept = counts[key] ?: 0
            // Probe-level truncation is additional to the attachment's fair budget.
            val probeOmitted = rows.mapNotNull { it.fields["omitted"]?.toIntOrNull() }.maxOrNull() ?: 0
            SignalSourceCoverage(key, rows.size + probeOmitted, kept, rows.size - kept + probeOmitted,
                attempted = rows.any { it.status !in setOf("disabled", "rate_limited", "deferred", "background_unavailable") },
                status = if (kept < rows.size || probeOmitted > 0) "partial" else rows.firstOrNull()?.status ?: "unavailable")
        })
    }
}

data class SignalSensorSample(val values: List<Double>, val measuredAt: Long?, val accuracy: Int)

/** Numeric features keep units, sensor identity and the actual observed interval. */
object SignalSensorFeatures {
    fun observations(key: String, sensorType: String, unit: String, samples: List<SignalSensorSample>,
                     collectedAt: Long, eventCounter: Boolean = false, cumulativeCounter: Boolean = false): List<SignalObservation> {
        val times = samples.mapNotNull { it.measuredAt }.filter { it <= collectedAt }
        fun row(metric: String, number: Double, count: Int) = SignalObservation(
            key, "phone", number.toString(), unit, collectedAt, times.maxOrNull(),
            status = if (times.isEmpty()) "invalid_samples" else "fresh", period = if (cumulativeCounter) "since_reboot" else "sample_window",
            windowStart = times.minOrNull(), windowEnd = times.maxOrNull(), identity = sensorType,
            number = number, metric = metric, sampleCount = count, accuracy = samples.lastOrNull()?.accuracy,
        )
        if (samples.isEmpty()) return emptyList()
        if (eventCounter) return listOf(row("observed_events", samples.size.toDouble(), samples.size))
        if (cumulativeCounter) return samples.last().values.firstOrNull()?.takeIf { it.isFinite() }?.let {
            listOf(row("cumulative_since_reboot", it, samples.size))
        }.orEmpty()
        val angular = "rotation_vector" in sensorType || sensorType == "android.sensor.orientation"
        val axes = (0 until samples.maxOf { it.values.size }).flatMap { axis ->
            val values = samples.mapNotNull { it.values.getOrNull(axis)?.takeIf(Double::isFinite) }
            if (values.isEmpty()) emptyList() else {
                // Circular angles and quaternion components are not ordinary scalar averages.
                if (angular) return@flatMap listOf(row("axis_${axis}.latest", values.last(), values.size))
                val mean = values.average()
                val sorted = values.sorted()
                val median = if (sorted.size % 2 == 0) sorted[sorted.size / 2 - 1] / 2 + sorted[sorted.size / 2] / 2 else sorted[sorted.size / 2]
                listOf(row("axis_${axis}.mean", mean, values.size), row("axis_${axis}.min", values.min(), values.size),
                    row("axis_${axis}.median", median, values.size), row("axis_${axis}.max", values.max(), values.size),
                    row("axis_${axis}.stddev", sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size), values.size))
            }
        }
        if (sensorType !in setOf("android.sensor.accelerometer", "android.sensor.linear_acceleration", "android.sensor.gravity", "android.sensor.gyroscope", "android.sensor.magnetic_field")) return axes
        val magnitudes = samples.mapNotNull { sample -> sample.values.take(3).takeIf { it.size == 3 && it.all(Double::isFinite) }?.let { sqrt(it.sumOf { v -> v * v }) } }
        if (magnitudes.isEmpty()) return axes
        val mean = magnitudes.average()
        return axes + listOf(row("magnitude.mean", mean, magnitudes.size), row("magnitude.stddev", sqrt(magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size), magnitudes.size))
    }
}
