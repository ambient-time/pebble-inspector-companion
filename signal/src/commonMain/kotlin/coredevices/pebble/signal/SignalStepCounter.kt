package coredevices.pebble.signal

/** Two observed cumulative endpoints within this process and phone boot, never a daily total. */
class SignalStepCounter {
    private data class Endpoint(val row: SignalObservation, val elapsed: Long, val boot: String)
    private var previous: Endpoint? = null
    fun reset() { previous = null }
    fun observe(row: SignalObservation, elapsed: Long, boot: String): SignalObservation? {
        val value = row.number?.takeIf { it.isFinite() && it >= 0 } ?: return null
        if (!SignalLearning.fresh(row)) return null
        val old = previous
        previous = Endpoint(row, elapsed, boot)
        if (old == null || old.boot != boot || elapsed <= old.elapsed || row.measuredAt!! <= old.row.measuredAt!! || value < old.row.number!!) return null
        val delta = value - old.row.number
        return row.copy(value = delta.toString(), number = delta, metric = "step_delta", period = "endpoint_interval",
            windowStart = old.row.measuredAt, windowEnd = row.measuredAt, sampleCount = 2,
            fields = row.fields + mapOf("bootScope" to boot, "elapsedStart" to old.elapsed.toString(), "elapsedEnd" to elapsed.toString(),
                "coverage" to "Difference between two counter readings; intervening motion was not continuously sampled."))
    }
}
