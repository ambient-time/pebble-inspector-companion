package coredevices.pebble.signal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

data class SignalWatchCapabilities(
    val messages: Boolean = false,
    val customTranscription: Boolean = false,
    val install: Boolean = false,
    val description: String = "Connect through your Pebble app when ready.",
)

data class SignalWatchResponse(val result: String, val status: Int)

interface SignalWatchSession {
    val watchId: String
    val connectionId: String
    val ready: Boolean
    suspend fun sendConfigMessage(message: String)
}

interface SignalWatchLink {
    val watches: StateFlow<List<SignalWatch>>
    val capabilities: SignalWatchCapabilities
    fun initialize(scope: CoroutineScope)
    fun configureTranscription(providers: SignalProviders, selected: () -> Boolean, enabled: suspend () -> Boolean) {}
    suspend fun isTrusted(session: SignalWatchSession): Boolean
    suspend fun refresh(watchId: String) {}
    suspend fun launch(watchId: String)
    suspend fun install(watchId: String)
}
