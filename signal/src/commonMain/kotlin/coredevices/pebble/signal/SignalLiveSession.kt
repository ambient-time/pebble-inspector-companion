package coredevices.pebble.signal

import kotlinx.coroutines.*

/** Foreground-only acquisition. No storage, provider, location lookup or automatic resume. */
class SignalLiveSession(
    private val scope: CoroutineScope,
    private val acquire: suspend (SignalSettings, Boolean) -> SignalAcquisition,
    private val foreground: () -> Boolean,
    private val wall: () -> Long,
    private val monotonic: () -> Long,
    private val update: (SignalLiveState) -> Unit,
) {
    private val reducer = SignalLiveReducer()
    private var job: Job? = null
    private var epoch = 0L
    var state = SignalLiveState()
        private set
    private fun publish(next: SignalLiveState) { state = next; update(next) }
    fun stop(clear: Boolean = false, message: String = "Stopped. Save a fresh snapshot or start a new scan.") {
        ++epoch; job?.cancel(); job = null
        if (clear) reducer.reset()
        publish(if (clear) SignalLiveState(status = message, now = wall()) else state.copy(running = false, scanning = false, now = wall(), entries = reducer.current(wall()), status = message))
    }
    fun start(settings: SignalSettings) {
        stop(clear = true)
        val sources = SignalLive.sources(settings.enabled)
        if (sources.isEmpty()) { publish(state.copy(status = "Choose Bluetooth signal strength or Wi-Fi signal and frequency in Sources first.")); return }
        if (!foreground()) { publish(state.copy(status = "Open Live view before starting a scan.")); return }
        val token = ++epoch
        val start = monotonic()
        publish(SignalLiveState(running = true, startedAt = wall(), now = wall(), status = "Scanning selected radios. Nothing is saved or sent automatically."))
        job = scope.launch {
            try {
                withTimeout(SignalLive.DURATION_MS) {
                    coroutineScope {
                        launch {
                            while (isActive && epoch == token) {
                                delay(1_000)
                                if (!foreground()) { stop(message = "Stopped when the app left the foreground."); return@launch }
                                publish(state.copy(now = wall(), entries = reducer.current(wall())))
                            }
                        }
                        var lastWifi = start - SignalLive.WIFI_INTERVAL_MS
                        while (isActive && epoch == token && foreground()) {
                            publish(state.copy(scanning = true, now = wall()))
                            val activeWifi = monotonic() - lastWifi >= SignalLive.WIFI_INTERVAL_MS
                            if (activeWifi) lastWifi = monotonic()
                            val result = acquire(settings.copy(enabled = sources), activeWifi)
                            if (epoch != token || !foreground()) break
                            val at = wall()
                            val (entries, newCount) = reducer.accept(result, sources, at)
                            val outcomes = sources.filter { it in setOf("wifi", "bluetooth") }.associateWith { radio ->
                                val rows = result.observations.filter { it.key == radio }
                                rows.lastOrNull { it.metric == "coverage" }?.status ?: rows.firstOrNull()?.status ?: "unavailable"
                            }
                            val omitted = result.observations.filter { it.metric == "coverage" && it.key in setOf("wifi", "bluetooth") }
                                .sumOf { it.fields["omitted"]?.toIntOrNull()?.coerceIn(0, 100_000) ?: 0 }
                            publish(state.copy(scanning = false, updatedAt = at, now = at, entries = entries, outcomes = outcomes,
                                newCount = newCount, omittedAtLeast = omitted, status = "Scan updated. Strength bands do not measure distance or direction."))
                            delay(5_000)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (epoch == token) stop(message = "Five-minute scan finished. Start again when ready.")
            } catch (_: CancellationException) {
                // The owner stops the session; a late callback cannot revive it.
            } catch (_: Exception) {
                if (epoch == token) stop(message = "Scan stopped. Check selected radio permissions and try again.")
            } finally {
                if (epoch == token) publish(state.copy(running = false, scanning = false, now = wall(), status = "Stopped when the app left the foreground."))
            }
        }
    }
    fun label(id: String, label: String) {
        if (label.isBlank() || state.entries.none { it.id == id && it.fresh(wall()) }) return
        reducer.label(id, label)
        publish(state.copy(entries = reducer.current(wall()), now = wall()))
    }
}
