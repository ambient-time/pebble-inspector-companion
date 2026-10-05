package coredevices.pebble.signal

import de.kempmobil.ktor.mqtt.MqttEngine
import de.kempmobil.ktor.mqtt.packet.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal const val SIGNAL_MQTT_PACKET_LIMIT = 65_536

/** Bound each MQTT packet before the library codec allocates its declared body. */
internal class SignalMqttFramer {
    private val buffer = ByteArray(SIGNAL_MQTT_PACKET_LIMIT)
    private var count = 0
    private var remaining = 0
    private var multiplier = 1
    private var lengthBytes = 0
    private var total = -1

    fun accept(bytes: ByteArray): List<ByteArray> {
        require(bytes.size <= SIGNAL_MQTT_PACKET_LIMIT) { "MQTT frame exceeds limit." }
        val packets = mutableListOf<ByteArray>()
        for (value in bytes) {
            require(count < buffer.size) { "MQTT packet exceeds limit." }
            buffer[count++] = value
            if (count > 1 && total < 0) {
                val unsigned = value.toInt() and 255
                lengthBytes++
                require(lengthBytes <= 4) { "Invalid MQTT length." }
                remaining += (unsigned and 127) * multiplier
                require(remaining + count <= SIGNAL_MQTT_PACKET_LIMIT) { "MQTT packet exceeds limit." }
                if (unsigned and 128 == 0) total = remaining + count
                else { require(lengthBytes < 4); multiplier *= 128 }
            }
            if (count == total) {
                require(packets.size < 256) { "MQTT packet burst exceeds limit." }
                packets += buffer.copyOf(count)
                count = 0; remaining = 0; multiplier = 1; lengthBytes = 0; total = -1
            }
        }
        return packets
    }
}

internal expect fun createSignalMqttHttpClient(): HttpClient

internal class SignalMqttWebSocketEngine(private val client: HttpClient, private val url: Url) : MqttEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packets = MutableSharedFlow<Result<Packet>>()
    override val packetResults: SharedFlow<Result<Packet>> = packets.asSharedFlow()
    private val online = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = online.asStateFlow()
    private var session: DefaultClientWebSocketSession? = null
    private var reader: Job? = null

    override suspend fun start(): Result<Unit> = try {
        packets.subscriptionCount.first { it > 0 }
        val socket = client.webSocketSession(urlString = url.toString()) { header(HttpHeaders.SecWebSocketProtocol, "mqtt") }
        session = socket
        require(socket.call.response.headers[HttpHeaders.SecWebSocketProtocol] == "mqtt") { "Broker did not negotiate MQTT." }
        online.value = true
        reader = scope.launch {
            val framer = SignalMqttFramer()
            try {
                for (frame in socket.incoming) {
                    require(frame is Frame.Binary) { "Broker sent a nonbinary MQTT frame." }
                    for (bytes in framer.accept(frame.data)) packets.emit(Result.success(ByteReadChannel(bytes).readPacket()))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { packets.emit(Result.failure(HomeException("Broker sent an invalid or oversized MQTT packet."))) }
            finally { online.value = false; socket.cancel() }
        }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.failure(HomeException("MQTT WebSocket connection failed.")) }

    override suspend fun send(packet: Packet): Result<Unit> = try {
        require(packet.type in setOf(PacketType.CONNECT, PacketType.SUBSCRIBE, PacketType.PINGREQ, PacketType.DISCONNECT, PacketType.PUBACK, PacketType.PUBREC, PacketType.PUBREL, PacketType.PUBCOMP)) { "MQTT application publishing is disabled." }
        val bytes = Buffer().apply { write(packet) }.readByteArray()
        require(bytes.size <= SIGNAL_MQTT_PACKET_LIMIT)
        requireNotNull(session).send(Frame.Binary(true, bytes))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.failure(HomeException("MQTT transport write failed.")) }

    override suspend fun disconnect() {
        online.value = false
        withTimeoutOrNull(200) { session?.close(); session?.closeReason?.await() }
        session?.cancel()
        reader?.cancel()
    }

    override fun close() {
        online.value = false
        session?.cancel()
        scope.cancel()
        client.close()
    }
}
