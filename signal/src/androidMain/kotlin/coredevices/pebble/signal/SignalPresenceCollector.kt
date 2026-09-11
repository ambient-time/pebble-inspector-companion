package coredevices.pebble.signal

import android.content.Context

/** Uses the same acquisition coordinator as ordinary captures. */
class SignalPresenceCollector(context: Context, private val collectors: SignalCollectors = SignalCollectors(context)) {
    suspend fun collect(settings: SignalSettings): SignalPresenceResult {
        val acquisition = collectors.acquire(settings.copy(enabled = settings.enabled.filter { it.startsWith("presence.") }.toSet()))
        return SignalPresenceResult(acquisition.observations, acquisition.candidates)
    }
}
