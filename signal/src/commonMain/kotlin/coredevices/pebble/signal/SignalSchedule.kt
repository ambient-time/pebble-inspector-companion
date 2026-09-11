package coredevices.pebble.signal

data class SignalScheduleDecision(val enabled: Set<String>, val deferred: Set<String>, val activeWifi: Boolean)

/** Session-local monotonic timing. A process restart never resumes a schedule. */
class SignalSchedule {
    companion object {
        fun remaining(startedAt: Long, endsAt: Long, now: Long) = (endsAt - now).coerceIn(0, (endsAt - startedAt).coerceAtLeast(0))
    }
    private val attempts = mutableMapOf<String, Long>()
    private var motionUntil = Long.MIN_VALUE
    fun reset() { attempts.clear(); motionUntil = Long.MIN_VALUE }
    fun noteTransition(elapsed: Long) { motionUntil = elapsed + 3 * 60_000 }
    fun noteMeasurements(rows: List<SignalObservation>, elapsed: Long) {
        if (rows.any { it.key in setOf("sensor.1", "sensor.10") && it.metric == "magnitude.stddev" && it.status == "fresh" && (it.number ?: 0.0) > 0.6 ||
                    it.key == "sensor.18" && it.status == "fresh" && it.metric == "observed_events" && (it.number ?: 0.0) > 0 }) noteTransition(elapsed)
    }
    fun delayMillis(mode: String, elapsed: Long) = when {
        mode == "battery_saver" -> if (elapsed < motionUntil) 5 * 60_000L else 15 * 60_000L
        elapsed < motionUntil -> 60_000L
        else -> 5 * 60_000L
    }
    fun decide(selected: Set<String>, mode: String, elapsed: Long): SignalScheduleDecision {
        fun due(family: String, interval: Long): Boolean = attempts[family]?.let { elapsed < it || elapsed - it >= interval } ?: true
        val interval = delayMillis(mode, elapsed)
        val deferred = selected.filter { key ->
            when {
                key in SignalWeather.keys -> !due("weather", 30 * 60_000L)
                key == "location" || key == "presence.places" -> !due("location", interval)
                key.startsWith("bluetooth") || key == "presence.bluetooth" -> !due("bluetooth", interval)
                key.startsWith("cellular") -> !due("cellular", maxOf(interval, 60_000L))
                else -> false
            }
        }.toSet()
        val enabled = selected - deferred
        if (enabled.any { it in SignalWeather.keys }) attempts["weather"] = elapsed
        if (enabled.any { it == "location" || it == "presence.places" }) attempts["location"] = elapsed
        if (enabled.any { it.startsWith("bluetooth") || it == "presence.bluetooth" }) attempts["bluetooth"] = elapsed
        if (enabled.any { it.startsWith("cellular") }) attempts["cellular"] = elapsed
        val activeWifi = due("wifi", 30 * 60_000L)
        if (activeWifi && enabled.any { it.startsWith("wifi") || it == "presence.wifi" }) attempts["wifi"] = elapsed
        return SignalScheduleDecision(enabled, deferred, activeWifi)
    }
}
