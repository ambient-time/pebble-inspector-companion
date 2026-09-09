package coredevices.pebble.signal

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignalWatchHandshakeTest {
    private fun session(id: String, connection: String) = object : SignalWatchSession {
        override val watchId = id
        override val connectionId = connection
        override val ready = true
        override suspend fun sendConfigMessage(message: String) {}
    }

    @Test fun launchAcceptsNewConnectionInsteadOfWaitingForOldSnapshot() = runTest {
        var watch = SignalWatch("watch", "Pebble", true, "before-launch")
        var runner: SignalWatchSession? = null
        val connected = session("watch", "after-launch")
        launch {
            delay(100)
            watch = watch.copy(connectionId = "after-launch", appOpen = true)
            delay(100)
            runner = connected
        }
        assertSame(connected, awaitSignalWatchSession("watch", { watch }, { runner }, 1000))
    }

    @Test fun reconnectRejectsStaleAndDifferentWatchSessions() = runTest {
        val watch = SignalWatch("watch", "Pebble", true, "new", true)
        assertNull(awaitSignalWatchSession("watch", { watch }, { session("watch", "old") }, 300))
        assertNull(awaitSignalWatchSession("watch", { watch }, { session("other", "new") }, 300))
        assertNull(awaitSignalWatchSession("watch", { watch.copy(connected = false) }, { session("watch", "new") }, 300))
        assertNull(awaitSignalWatchSession("watch", { watch.copy(appOpen = false) }, { session("watch", "new") }, 300))
    }
}
