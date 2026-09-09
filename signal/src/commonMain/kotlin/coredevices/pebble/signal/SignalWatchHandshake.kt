package coredevices.pebble.signal

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// Opening the app creates a new connection ID. Match the live connection,
// not the pre-launch snapshot, while still excluding stale sessions.
internal suspend fun awaitSignalWatchSession(
    watchId: String,
    currentWatch: () -> SignalWatch?,
    currentSession: () -> SignalWatchSession?,
    timeoutMillis: Long = 12_000,
): SignalWatchSession? = withTimeoutOrNull(timeoutMillis) {
    while (true) {
        val watch = currentWatch()
        val session = currentSession()
        if (watch?.id == watchId && watch.connected && watch.appOpen &&
            session?.watchId == watchId && session.ready && session.connectionId == watch.connectionId)
            return@withTimeoutOrNull session
        delay(100)
    }
    @Suppress("UNREACHABLE_CODE") null
}
