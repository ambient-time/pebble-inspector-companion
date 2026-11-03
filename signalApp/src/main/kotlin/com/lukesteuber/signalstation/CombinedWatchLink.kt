package com.lukesteuber.signalstation

import coredevices.pebble.signal.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Pebble and Garmin watches in one list; each call goes to the link that owns the watch. */
class CombinedWatchLink(private val primary: SignalWatchLink, private vararg val others: SignalWatchLink) : SignalWatchLink {
    private val links = listOf(primary) + others
    private val mutable = MutableStateFlow<List<SignalWatch>>(emptyList())
    override val watches = mutable.asStateFlow()
    // Transcription and install choices stay with the Pebble link.
    override val capabilities get() = primary.capabilities

    override fun initialize(scope: CoroutineScope) {
        links.forEach { it.initialize(scope) }
        scope.launch { combine(links.map { it.watches }) { sync() }.collect() }
    }

    /** Request handlers compare the live list, so a link can publish a new session immediately. */
    fun sync() { mutable.value = links.flatMap { it.watches.value } }

    override fun configureTranscription(providers: SignalProviders, selected: () -> Boolean, enabled: suspend () -> Boolean) =
        primary.configureTranscription(providers, selected, enabled)
    override suspend fun isTrusted(session: SignalWatchSession) = links.any { it.isTrusted(session) }
    override suspend fun refresh(watchId: String) { owner(watchId)?.refresh(watchId) }
    override suspend fun launch(watchId: String) = (owner(watchId) ?: throw SignalProviderException("That watch is no longer connected.")).launch(watchId)
    override suspend fun install(watchId: String) = (owner(watchId) ?: primary).install(watchId)

    private fun owner(watchId: String) = links.firstOrNull { link -> link.watches.value.any { it.id == watchId } }
}
