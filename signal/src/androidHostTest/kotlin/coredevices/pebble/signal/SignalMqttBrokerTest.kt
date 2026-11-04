package coredevices.pebble.signal

import de.kempmobil.ktor.mqtt.*
import de.kempmobil.ktor.mqtt.packet.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Loopback-only synthetic MQTT 5 broker with an actual WebSocket handshake. */
class SignalMqttBrokerTest {
    private val now = 1_800_000_000_000L

    @Test fun retainedSnapshotUsesLibraryProtocolAndNeverPublishes(): Unit = runBlocking {
        fixture(Mode.RETAINED) { connector, broker ->
            val entity = connector.catalog().single()
            assertEquals("21.5", entity.values.single().value)
            assertTrue(entity.retained)
            assertFalse(entity.available)
            assertEquals("mqtt_retained", entity.observationBasis)
            broker.await()
            assertEquals(listOf(PacketType.CONNECT, PacketType.SUBSCRIBE, PacketType.DISCONNECT), broker.received)
            assertTrue(broker.cleanStart)
            assertEquals(0u, broker.expiry)
            assertFalse(broker.hasWill)
        }
    }

    @Test fun successfulSubscriptionWithoutMessageReturnsWaitingEntities(): Unit = runBlocking {
        fixture(Mode.SILENT) { connector, broker ->
            val entity = connector.catalog().single()
            assertFalse(entity.available)
            assertEquals("mqtt_waiting", entity.observationBasis)
            assertEquals(0L, entity.observedAt)
            broker.await()
        }
    }

    @Test fun deniedAuthenticationDoesNotSubscribeOrPublish(): Unit = runBlocking {
        fixture(Mode.AUTH_DENIED) { connector, broker ->
            assertFailsWith<HomeException> { connector.catalog() }
            broker.await()
            assertEquals(listOf(PacketType.CONNECT), broker.received.filterNot { it == PacketType.DISCONNECT })
        }
    }

    @Test fun deniedSubscriptionCannotPassConnectionPreview(): Unit = runBlocking {
        fixture(Mode.SUBSCRIBE_DENIED) { connector, broker ->
            assertFailsWith<HomeException> { connector.catalog() }
            broker.await()
            assertFalse(PacketType.PUBLISH in broker.received)
        }
    }

    @Test fun silentHandshakeFailsAsConnectionErrorAndCloses(): Unit = runBlocking {
        fixture(Mode.NO_ACK) { connector, broker ->
            assertFailsWith<HomeException> { connector.catalog() }
            broker.await()
            assertFalse(PacketType.SUBSCRIBE in broker.received)
            assertFalse(PacketType.PUBLISH in broker.received)
        }
    }

    @Test fun parentCancellationDuringHandshakeIsNotReportedAsConnectionFailure(): Unit = runBlocking {
        fixture(Mode.NO_ACK) { connector, broker -> coroutineScope {
            val outcome = CompletableDeferred<Throwable>()
            val reading = launch {
                try { connector.catalog(); outcome.complete(IllegalStateException("Expected cancellation")) }
                catch (error: Throwable) { outcome.complete(error) }
            }
            broker.awaitConnect()
            reading.cancel()
            reading.join()
            assertIs<CancellationException>(outcome.await())
            broker.await()
        } }
    }

    @Test fun cancelledForegroundCollectionClosesTheSubscription(): Unit = runBlocking {
        fixture(Mode.LIVE) { connector, broker ->
            val entity = withTimeout(5_000) { connector.foregroundEvents().first { it.values.isNotEmpty() } }
            assertTrue(entity.available)
            assertFalse(entity.retained)
            assertNull(entity.values.single().measuredAt)
            broker.await()
            assertEquals(PacketType.DISCONNECT, broker.received.last())
            assertFalse(PacketType.PUBLISH in broker.received)
        }
    }

    @Test fun oversizedPacketHeaderDisconnectsBeforeReceivingBody(): Unit = runBlocking {
        fixture(Mode.OVERSIZED) { connector, broker ->
            assertFails { connector.catalog() }
            broker.await()
            assertFalse(PacketType.PUBLISH in broker.received)
        }
    }

    private suspend fun fixture(mode: Mode, block: suspend (SignalMqttConnector, Broker) -> Unit) {
        val broker = Broker(mode)
        val http = createSignalHomeHttpClient()
        val connection = HomeConnection("test", "Synthetic", HomeConnectorKind.MQTT, "ws://127.0.0.1:${broker.port}/mqtt", allowPrivateHttp = true,
            mqtt = HomeMqttConfig(listOf(HomeMqttTopic("fixture/room", "Room", humidityPath = ""))))
        val secrets = object : SignalSecrets { override suspend fun get(provider: String) = "{}"; override suspend fun put(provider: String, key: String) = Unit }
        val connector = SignalMqttConnector(http, connection, secrets, clock = { now })
        try { block(connector, broker) } finally { connector.close(); http.close(); broker.close() }
    }

    private enum class Mode { RETAINED, SILENT, LIVE, AUTH_DENIED, SUBSCRIBE_DENIED, OVERSIZED, NO_ACK }

    private class Broker(private val mode: Mode) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 8_000 }
        private val executor = Executors.newSingleThreadExecutor()
        val port = server.localPort
        val received = mutableListOf<PacketType>()
        var cleanStart = false
        var expiry: UInt? = null
        var hasWill = true
        private val connectionOpened = CompletableDeferred<Unit>()
        private var socket: Socket? = null
        private val done = executor.submit {
            server.accept().use { accepted ->
                socket = accepted
                accepted.soTimeout = 8_000
                val input = DataInputStream(accepted.getInputStream())
                val output = DataOutputStream(accepted.getOutputStream())
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) { check(headers.length < 8192); headers.append(input.readUnsignedByte().toChar()) }
                assertTrue(headers.startsWith("GET /mqtt "))
                val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n")
                output.flush()
                val connected = read(input) as Connect
                cleanStart = connected.isCleanStart; expiry = connected.sessionExpiryInterval?.value; hasWill = connected.willMessage != null
                assertTrue(connected.clientId.startsWith("signal-"))
                assertEquals(SIGNAL_MQTT_PACKET_LIMIT.toUInt(), connected.maximumPacketSize?.value)
                connectionOpened.complete(Unit)
                if (mode != Mode.NO_ACK) write(output, Connack(false, if (mode == Mode.AUTH_DENIED) NotAuthorized else Success))
                if (mode != Mode.AUTH_DENIED && mode != Mode.NO_ACK) {
                    val subscription = read(input) as Subscribe
                    assertEquals(listOf("fixture/room"), subscription.filters.map { it.filter.name })
                    assertEquals(QoS.AT_MOST_ONCE, subscription.filters.single().subscriptionOptions.qoS)
                    write(output, Suback(subscription.packetIdentifier, listOf(if (mode == Mode.SUBSCRIBE_DENIED) NotAuthorized else GrantedQoS0)))
                    when (mode) {
                        Mode.RETAINED, Mode.LIVE -> write(output, Publish(topic = Topic("fixture/room"), isRetainMessage = mode == Mode.RETAINED, payload = ByteString("{\"temperature\":21.5}".toByteArray())))
                        Mode.OVERSIZED -> frame(output, byteArrayOf(0x30, 0xff.toByte(), 0xff.toByte(), 0x7f))
                        else -> Unit
                    }
                }
                while (true) {
                    val packet = read(input) ?: break
                    if (packet.type == PacketType.DISCONNECT) break
                    assertFalse(packet is Publish)
                }
            }
        }

        private fun read(input: DataInputStream): Packet? {
            val first = input.read()
            if (first < 0 || first and 15 == 8) return null
            assertEquals(2, first and 15)
            val flags = input.readUnsignedByte()
            assertTrue(flags and 128 != 0)
            val length = when (val small = flags and 127) { 126 -> input.readUnsignedShort(); 127 -> error("Unexpected large fixture packet"); else -> small }
            assertTrue(length <= SIGNAL_MQTT_PACKET_LIMIT)
            val mask = ByteArray(4).also { input.readFully(it) }
            val bytes = ByteArray(length).also { input.readFully(it) }
            bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
            return runBlocking { ByteReadChannel(bytes).readPacket() }.also { received += it.type }
        }

        private fun write(output: DataOutputStream, packet: Packet) = frame(output, Buffer().apply { write(packet) }.readByteArray())
        private fun frame(output: DataOutputStream, bytes: ByteArray) {
            output.writeByte(0x82)
            if (bytes.size < 126) output.writeByte(bytes.size) else { output.writeByte(126); output.writeShort(bytes.size) }
            output.write(bytes); output.flush()
        }
        fun await() { done.get(8, TimeUnit.SECONDS) }
        suspend fun awaitConnect() { withTimeout(5_000) { connectionOpened.await() } }
        override fun close() { socket?.close(); server.close(); executor.shutdownNow() }
    }
}
