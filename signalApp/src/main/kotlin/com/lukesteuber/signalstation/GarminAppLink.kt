package com.lukesteuber.signalstation

import coredevices.pebble.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class GarminDevice(val id: Long, val name: String, val connected: Boolean)

enum class GarminOpenResult { PROMPT_SHOWN, ALREADY_RUNNING, NOT_INSTALLED, FAILED }

interface GarminConnection {
    val devices: StateFlow<List<GarminDevice>>
    val status: StateFlow<String>
    fun start(onMessage: (deviceId: Long, message: Any?) -> Unit)
    suspend fun send(deviceId: Long, message: Map<String, Any>): Boolean
    suspend fun open(deviceId: Long): GarminOpenResult
}

internal class GarminSession(
    override val watchId: String,
    override val connectionId: String,
    val deviceId: Long,
    val nonce: Int,
    private val connection: GarminConnection,
) : SignalWatchSession {
    override val ready = true
    override suspend fun sendConfigMessage(message: String) {
        // The Garmin app has no Pebble dictation or capture screens; it only re-checks the link.
        if (!connection.send(deviceId, GarminWire.reset(nonce))) throw SignalProviderException("Garmin watch connection closed.")
    }
}

/**
 * Signal Station's Garmin watch app over Garmin Connect. Each app run announces
 * a session number; requests are answered by the same handler the Pebble app uses.
 */
class GarminAppLink(private val connection: GarminConnection) : SignalWatchLink {
    private val mutable = MutableStateFlow<List<SignalWatch>>(emptyList())
    override val watches = mutable.asStateFlow()
    override val capabilities = SignalWatchCapabilities(messages = true, description = "Uses Garmin Connect on this device.")
    lateinit var request: suspend (String, String, String?, SignalWatchSession) -> SignalWatchResponse
    /** Called after every watch-list change, before any request from that session is handled. */
    var onChanged: () -> Unit = {}
    private val sessions = mutableMapOf<String, GarminSession>()
    private var epoch = 0L
    private lateinit var scope: CoroutineScope

    override fun initialize(scope: CoroutineScope) {
        this.scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
        this.scope.launch { connection.devices.collect { update(it) } }
        connection.start { device, message -> this.scope.launch { handle(device, message) } }
    }

    internal suspend fun handle(deviceId: Long, message: Any?) {
        val inbound = GarminWire.parse(message) ?: return
        val watchId = watchId(deviceId)
        if (connection.devices.value.none { it.id == deviceId && it.connected }) return
        val current = sessions[watchId]?.takeIf { it.nonce == inbound.session }
        when (inbound) {
            is GarminInbound.Hello -> if (current == null) open(deviceId, inbound.session)
            is GarminInbound.Bye -> if (current != null) close(watchId)
            is GarminInbound.Request -> {
                // Every app run checks capabilities first, so only that may start a session;
                // a late message from an earlier run is refused without replacing the current one.
                val session = current ?: if (inbound.route == "capabilities") open(deviceId, inbound.session) else {
                    connection.send(deviceId, GarminWire.response(inbound.session, inbound.id, 403, "{}"))
                    return
                }
                val response = try {
                    request(GarminWire.url(inbound), GarminWire.method(inbound.route), inbound.body.toString(), session)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { SignalWatchResponse("{}", 500) }
                if (sessions[watchId] !== session) return
                connection.send(deviceId, GarminWire.response(inbound.session, inbound.id, response.status, response.result))
            }
        }
    }

    override suspend fun isTrusted(session: SignalWatchSession) = session is GarminSession && sessions[session.watchId] === session

    override suspend fun refresh(watchId: String) {
        sessions[watchId]?.let { connection.send(it.deviceId, GarminWire.reset(it.nonce)) }
    }

    override suspend fun launch(watchId: String) {
        if (sessions[watchId] != null) { refresh(watchId); return }
        val device = connection.devices.value.firstOrNull { watchId(it.id) == watchId && it.connected }
            ?: throw SignalProviderException("Connect the Garmin watch in Garmin Connect first.")
        when (connection.open(device.id)) {
            GarminOpenResult.PROMPT_SHOWN, GarminOpenResult.ALREADY_RUNNING -> Unit
            GarminOpenResult.NOT_INSTALLED -> throw SignalProviderException("Install Signal Station on the Garmin watch first.")
            GarminOpenResult.FAILED -> throw SignalProviderException("Open Signal Station on the Garmin watch.")
        }
    }

    override suspend fun install(watchId: String) {
        throw SignalProviderException("Install Signal Station on the Garmin watch through Garmin Connect.")
    }

    private fun open(deviceId: Long, nonce: Int): GarminSession {
        val watchId = watchId(deviceId)
        val session = GarminSession(watchId, "garmin:${++epoch}:$watchId", deviceId, nonce, connection)
        sessions[watchId] = session
        update(connection.devices.value)
        return session
    }

    private fun close(watchId: String) {
        sessions.remove(watchId)
        update(connection.devices.value)
    }

    private fun update(devices: List<GarminDevice>) {
        val connected = devices.filter { it.connected }.map { watchId(it.id) }.toSet()
        sessions.keys.retainAll(connected)
        mutable.value = devices.map { device ->
            val id = watchId(device.id)
            val session = sessions[id]
            SignalWatch(id, device.name, device.connected, session?.connectionId ?: "garmin:$epoch:$id", session != null, when {
                !device.connected -> "Garmin watch is not connected to Garmin Connect."
                session == null -> "Open Signal Station on the Garmin watch."
                else -> "Garmin watch app is open."
            })
        }
        onChanged()
    }

    companion object {
        const val APP_ID = "884dd269-c882-420a-81fc-a54ee9889300"
        fun watchId(deviceId: Long) = "garmin:$deviceId"
    }
}
