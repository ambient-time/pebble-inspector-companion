package coredevices.pebble.signal

data class SignalScheduleDecision(val enabled: Set<String>, val deferred: Set<String>, val activeWifi: Boolean)
/** One step of the wait before a sample: stay awake for the rest of it, or set an alarm [alarmIn] from now. */
data class SignalWakeStep(val holdAwake: Boolean, val alarmIn: Long)
/** The next fixed-interval sample: its slot number, when it is due, and how many whole slots were skipped. */
data class SignalSlot(val index: Long, val dueAt: Long, val missed: Long)

/** Session-local monotonic timing. A process restart never resumes a schedule. */
class SignalSchedule {
    companion object {
        fun remaining(startedAt: Long, endsAt: Long, now: Long) = (endsAt - now).coerceIn(0, (endsAt - startedAt).coerceAtLeast(0))
        fun intervalMinutes(value: Int) = value.coerceIn(1, 1440)
        /** The last stretch before a sample runs awake; earlier, the phone may sleep between alarms. */
        const val HOLD_AWAKE_MILLIS = 120_000L
        /**
         * Android may deliver an inexact allow-while-idle alarm up to 75% of its lead time late, and
         * allows 72 such alarms an hour. Waking at half the remaining time therefore always lands before
         * the sample is due, and a day-long interval needs about ten alarms.
         */
        fun wakeStep(remaining: Long): SignalWakeStep =
            if (remaining <= HOLD_AWAKE_MILLIS) SignalWakeStep(true, remaining.coerceAtLeast(0)) else SignalWakeStep(false, remaining / 2)
        /**
         * Fixed sampling keeps to a grid that starts with the session, so slow samples do not drift it.
         * A slot that is already due is taken now; whole slots that passed while the phone slept are
         * counted as missed, never filled in.
         */
        fun nextSlot(anchor: Long, intervalMillis: Long, now: Long, lastIndex: Long): SignalSlot {
            val interval = intervalMillis.coerceAtLeast(1)
            val reached = if (now <= anchor) 0 else (now - anchor) / interval
            val index = maxOf(lastIndex + 1, reached)
            return SignalSlot(index, anchor + index * interval, index - lastIndex - 1)
        }
        /** One failed sample must end neither the session nor the process. Cancellation still stops it. */
        suspend fun guardSample(sample: suspend () -> Unit, failed: suspend () -> Unit) {
            try { sample() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                try { failed() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {}
            }
        }
    }
    private val attempts = mutableMapOf<String, Long>()
    private var motionUntil = Long.MIN_VALUE
    fun reset() { attempts.clear(); motionUntil = Long.MIN_VALUE }
    fun noteTransition(elapsed: Long) { motionUntil = elapsed + 3 * 60_000 }
    fun noteMeasurements(rows: List<SignalObservation>, elapsed: Long) {
        if (rows.any { it.key in setOf("sensor.1", "sensor.10") && it.metric == "magnitude.stddev" && it.status == "fresh" && (it.number ?: 0.0) > 0.6 ||
                    it.key == "sensor.18" && it.status == "fresh" && it.metric == "observed_events" && (it.number ?: 0.0) > 0 }) noteTransition(elapsed)
    }
    fun delayMillis(mode: String, elapsed: Long, intervalMinutes: Int = 5) = when {
        mode == "fixed" -> Companion.intervalMinutes(intervalMinutes) * 60_000L
        mode == "battery_saver" -> if (elapsed < motionUntil) 5 * 60_000L else 15 * 60_000L
        elapsed < motionUntil -> 60_000L
        else -> 5 * 60_000L
    }
    fun decide(selected: Set<String>, mode: String, elapsed: Long, intervalMinutes: Int = 5): SignalScheduleDecision {
        // Fixed samples follow a grid, so one can start a little early after a late one. Half an
        // interval still keeps two scans of the same radio apart. Weather keeps its own limit.
        fun due(family: String, interval: Long): Boolean = attempts[family]?.let { elapsed < it || elapsed - it >= if (mode == "fixed" && family != "weather") interval / 2 else interval } ?: true
        val interval = delayMillis(mode, elapsed, intervalMinutes)
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
        val activeWifi = due("wifi", if (mode == "fixed") interval else 30 * 60_000L)
        if (activeWifi && enabled.any { it.startsWith("wifi") || it == "presence.wifi" }) attempts["wifi"] = elapsed
        return SignalScheduleDecision(enabled, deferred, activeWifi)
    }
}
