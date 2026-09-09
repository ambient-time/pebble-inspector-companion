package com.lukesteuber.signalstation

import android.content.Context
import coredevices.pebble.signal.*
import io.rebble.pebblekit2.client.*
import io.rebble.pebblekit2.common.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class PebbleAppLink(private val context: Context) : SignalWatchLink {
    private val mutable = MutableStateFlow<List<SignalWatch>>(emptyList())
    override val watches = mutable.asStateFlow()
    override val capabilities = SignalWatchCapabilities(messages = true, description = "Uses your existing Pebble app and its watch dictation service.")
    private val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
    private var sender = DefaultPebbleSender(context)
    private val sessions = mutableMapOf<String, LocalProtocolSession>()
    private var watchJob: Job? = null
    private lateinit var scope: CoroutineScope
    private var epoch = 0L
    private val gate = SignalSessionGate()
    private val pendingOpen = mutableMapOf<String, Job>()
    lateinit var request: suspend (String, String, String?, SignalWatchSession) -> SignalWatchResponse
    override fun initialize(scope: CoroutineScope) {
        this.scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
        refresh()
    }
    fun availableApps() = picker.getAllEligibleApps().filter { it != "coredevices.coreapp.inspectorlab" }
    suspend fun selectApp(packageName: String?) = withContext(Dispatchers.Main.immediate) {
        gate.clear(); pendingOpen.values.forEach { it.cancel() }; pendingOpen.clear()
        require(packageName == null || packageName in availableApps())
        watchJob?.cancelAndJoin()
        sessions.values.forEach { it.close() }; sessions.clear()
        mutable.value = emptyList(); ++epoch
        sender.close()
        sender = DefaultPebbleSender(context)
        picker.selectApp(packageName)
        refresh()
    }
    private fun refresh() {
        watchJob?.cancel()
        watchJob = scope.launch {
            if (picker.getCurrentlySelectedApp() == null) return@launch
            DefaultPebbleInfoRetriever(context).getConnectedWatches().flowOn(Dispatchers.IO)
                .catch { emit(emptyList()) }.collect { connected ->
                    val removed = (sessions.keys + pendingOpen.keys) - connected.map { it.id.value }.toSet()
                    removed.forEach { gate.close(it); pendingOpen.remove(it)?.cancel(); sessions.remove(it)?.close() }
                    mutable.value = connected.map {
                        val session = sessions[it.id.value]
                        SignalWatch(it.id.value, it.name, true, session?.connectionId ?: "$epoch:${it.id.value}", session != null)
                    }
                }
        }
    }
    fun opened(watchId: String) {
        pendingOpen.remove(watchId)?.cancel()
        val ticket = gate.open(watchId)
        pendingOpen[watchId] = scope.launch {
            withTimeoutOrNull(3000) { watches.first { all -> all.any { it.id == watchId && it.connected } } } ?: return@launch
            if (!gate.accepts(watchId, ticket)) return@launch
            sessions.remove(watchId)?.close()
            val session = LocalProtocolSession(context, watchId, "${++epoch}:$watchId", scope, sender) { url, method, body, caller -> request(url, method, body, caller) }
            sessions[watchId] = session
            mutable.update { all -> all.map { if (it.id == watchId) it.copy(connectionId = session.connectionId, appOpen = true) else it } }
            session.start()
        }
    }
    fun closed(watchId: String) {
        gate.close(watchId); pendingOpen.remove(watchId)?.cancel()
        sessions.remove(watchId)?.close()
        mutable.value = mutable.value.map { if (it.id == watchId) it.copy(appOpen = false, connectionId = "${++epoch}:$watchId") else it }
    }
    suspend fun receive(watchId: String, data: PebbleDictionary): ReceiveResult = withContext(Dispatchers.Main.immediate) {
        val session = sessions[watchId] ?: return@withContext ReceiveResult.Nack
        if (data.size > 32 || data.values.sumOf { it.size } > 4096) return@withContext ReceiveResult.Nack
        if (session.receive(data)) ReceiveResult.Ack else ReceiveResult.Nack
    }
    override suspend fun isTrusted(session: SignalWatchSession) = sessions[session.watchId] === session && session.ready
    override suspend fun refresh(watchId: String) { sessions[watchId]?.sendConfigMessage("{\"kind\":\"refresh\"}") }
    override suspend fun launch(watchId: String) {
        if (sessions[watchId]?.ready == true) { refresh(watchId); return }
        val id = WatchIdentifier(watchId)
        if (sender.startAppOnTheWatch(UUID.fromString(AndroidSignalStation.APP_UUID), listOf(id))?.get(id) != TransmissionResult.Success)
            throw SignalProviderException("Open Signal Station on your watch using the Pebble app.")
    }
    override suspend fun install(watchId: String) {
        throw SignalProviderException("Install the watch app through Pebble after the installation hold is lifted.")
    }
}
