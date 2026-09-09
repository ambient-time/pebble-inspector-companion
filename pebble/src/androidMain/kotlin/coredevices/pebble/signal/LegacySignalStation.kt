package coredevices.pebble.signal

import android.content.Context
import io.rebble.libpebblecommon.connection.*
import io.rebble.libpebblecommon.js.*
import io.rebble.libpebblecommon.voice.VoiceProviderOverrides
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

class LegacySignalStation(context: Context, pebble: () -> LibPebble) :
    AndroidSignalStation(context, LegacySignalWatchLink(context, pebble)), HttpInterceptor {
    override fun shouldIntercept(url: String) = available && url.startsWith(PREFIX)
    override suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid) = InterceptResponse("{}", 403)
    override suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid, caller: HttpCallerContext): InterceptResponse {
        if (appUuid.toString() != APP_UUID) return InterceptResponse("{}", 403)
        val session = (watchLink as LegacySignalWatchLink).session(caller.runner)
        val response = handleWatchRequest(url, method, body, session)
        return InterceptResponse(response.result, response.status)
    }
}

private class LegacySignalWatchLink(private val context: Context, private val pebble: () -> LibPebble) : SignalWatchLink {
    private val mutable = MutableStateFlow<List<SignalWatch>>(emptyList())
    override val watches = mutable.asStateFlow()
    override val capabilities = SignalWatchCapabilities(true, true, false, "Experimental companion connection; installation remains paused.")
    private val sessions = java.util.IdentityHashMap<JsRunner, LegacySession>()
    private val uuid = Uuid.parse(AndroidSignalStation.APP_UUID)
    fun session(runner: JsRunner): SignalWatchSession = synchronized(sessions) {
        sessions.entries.removeAll { (old, _) -> old !== runner && (!old.readyState.value || old.device.identifier == runner.device.identifier) }
        sessions.getOrPut(runner) { LegacySession(runner) }
    }
    override fun initialize(scope: CoroutineScope) {
        scope.launch {
            pebble().watches.collectLatest { all ->
                val connected = all.filterIsInstance<ConnectedPebbleDevice>()
                if (connected.isEmpty()) mutable.value = all.filterIsInstance<KnownPebbleDevice>().map { SignalWatch(it.serial, it.name, false) }
                else combine(connected.map { it.runningApp }) { apps ->
                    all.filterIsInstance<KnownPebbleDevice>().map { watch ->
                        val index = connected.indexOfFirst { it.serial == watch.serial }
                        SignalWatch(watch.serial, watch.name, index >= 0, watch.identifier.toString(), index >= 0 && apps[index] == uuid)
                    }
                }.collect { mutable.value = it }
            }
        }
    }
    override fun configureTranscription(providers: SignalProviders, selected: () -> Boolean, enabled: suspend () -> Boolean) {
        val recognition = SignalWatchTranscription(providers, enabled)
        VoiceProviderOverrides.resolve = { app -> if (app == uuid && selected()) recognition else null }
    }
    override suspend fun isTrusted(session: SignalWatchSession): Boolean = withContext(Dispatchers.IO) {
        val legacy = session as? LegacySession ?: return@withContext false
        if (legacy.runner.appInfo.uuid != AndroidSignalStation.APP_UUID) return@withContext false
        runCatching {
            val expected = context.assets.open("signal-station/pkjs.sha256").bufferedReader().use { it.readText().trim() }
            val actual = MessageDigest.getInstance("SHA-256").digest(File(legacy.runner.jsPath.toString()).readBytes()).joinToString("") { "%02x".format(it) }
            expected.length == 64 && expected == actual
        }.getOrDefault(false)
    }
    private fun watch(id: String) = pebble().watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull { it.serial == id }
        ?: throw SignalProviderException("Connect the selected watch in the Pebble app.")
    override suspend fun launch(watchId: String) { watch(watchId).launchApp(uuid) }
    override suspend fun install(watchId: String) {
        // The settings-wipe investigation has not established safe hardware installation.
        throw SignalProviderException("Watch installation is paused while the settings-wipe report is investigated.")
    }
    private class LegacySession(val runner: JsRunner) : SignalWatchSession {
        override val watchId get() = runner.device.watchInfo.serial
        override val connectionId get() = runner.device.identifier.toString()
        override val ready get() = runner.readyState.value
        override suspend fun sendConfigMessage(message: String) { runner.sendConfigMessage(message) }
    }
}
