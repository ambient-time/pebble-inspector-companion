package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalMqttTest {
    private val now = 1_800_000_000_000L
    private fun connection(mapping: HomeMqttTopic = HomeMqttTopic("test/room", "Room"), url: String = "wss://broker.example/mqtt", trust: Boolean = false) =
        HomeConnection("mqtt", "Broker", HomeConnectorKind.MQTT, url, allowPrivateHttp = trust, mqtt = HomeMqttConfig(listOf(mapping)))
    private fun reading(payload: String, retained: Boolean = false, mapping: HomeMqttTopic = HomeMqttTopic("test/room", "Room")) =
        mqttReading(connection(mapping), mapping, payload.encodeToByteArray(), retained, now)

    @Test fun urlPolicyRequiresSecureWebSocketOrExplicitPrivateTrust() {
        validateMqttConnection(connection())
        validateMqttConnection(connection(url = "ws://192.168.1.10:9001/mqtt", trust = true))
        listOf("mqtt://broker.example", "https://broker.example", "ws://broker.example", "wss://user:secret@broker.example", "wss://broker.example?token=x", "wss://broker.example/#fragment", "wss://broker.example/../other").forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { validateMqttConnection(connection(url = url, trust = true)) }
        }
        assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection(url = "ws://192.168.1.10/mqtt")) }
    }

    @Test fun topicsAndPathsAreExplicitBoundedAndUnique() {
        listOf("test/#", "test/+", "test/\u0000x", "\$share/group/test/room", "a".repeat(513)).forEach { topic ->
            assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection(HomeMqttTopic(topic))) }
        }
        listOf("a..b", "$.temperature", "a[0]", "a".repeat(129)).forEach { path ->
            assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection(HomeMqttTopic("room", temperaturePath = path))) }
        }
        assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection().copy(mqtt = HomeMqttConfig())) }
        assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection().copy(mqtt = HomeMqttConfig(List(2) { HomeMqttTopic("same") }))) }
        assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection(HomeMqttTopic("room", temperaturePath = "", humidityPath = ""))) }
        assertFailsWith<IllegalArgumentException> { validateMqttConnection(connection(HomeMqttTopic("room", staleAfterSeconds = 0))) }
    }

    @Test fun liveDeliveryUsesReceiptOnlyForFreshnessAndNeverInventsMeasurementTime() {
        val entity = reading("""{"temperature":0,"humidity":45.2}""")
        assertTrue(entity.available)
        assertEquals(now, entity.observedAt)
        assertEquals(now + 7_200_000, entity.expiresAt)
        assertEquals("mqtt_delivery", entity.observationBasis)
        assertEquals(listOf("temperature", "humidity"), entity.values.map { it.key })
        assertEquals("0.0", entity.values.first().value)
        assertTrue(entity.values.all { it.measuredAt == null })
        assertTrue(entity.capabilities.isEmpty())
    }

    @Test fun retainedDeliveryWithoutMappedTimestampHasUnknownAge() {
        val entity = reading("""{"temperature":22}""", retained = true)
        assertFalse(entity.available)
        assertNull(entity.expiresAt)
        assertEquals(now, entity.observedAt)
        assertTrue(entity.retained)
        assertEquals("mqtt_retained", entity.observationBasis)
        assertNull(entity.values.single().measuredAt)
    }

    @Test fun configuredTimestampIsRequiredAndBadOrFutureTimesDoNotFallBackToReceipt() {
        val mapping = HomeMqttTopic("room", timestampPath = "measured")
        listOf("", ",\"measured\":null", ",\"measured\":\"bad\"", ",\"measured\":-1", ",\"measured\":${now + 120_000}").forEach { suffix ->
            val entity = reading("{\"temperature\":22$suffix}", mapping = mapping)
            assertFalse(entity.available, suffix)
            assertNull(entity.expiresAt, suffix)
            assertNull(entity.values.single().measuredAt, suffix)
        }
    }

    @Test fun mappedSecondsMillisecondsAndIsoKeepSampleTimeAcrossReconnects() {
        val mapping = HomeMqttTopic("room", timestampPath = "measured", staleAfterSeconds = 60)
        val sampled = now - 30_000
        for (time in listOf((sampled / 1000).toString(), sampled.toString(), "\"${kotlin.time.Instant.fromEpochMilliseconds(sampled)}\"")) {
            val entity = reading("{\"temperature\":22,\"measured\":$time}", retained = true, mapping = mapping)
            assertTrue(entity.available)
            assertEquals(sampled, entity.values.single().measuredAt)
            assertEquals(sampled + 60_000, entity.expiresAt)
        }
        val stale = reading("{\"temperature\":22,\"measured\":${now - 60_001}}", retained = true, mapping = mapping)
        assertFalse(stale.available)
    }

    @Test fun nestedAndScalarMappingPreserveChosenUnits() {
        val mapping = HomeMqttTopic("room", temperaturePath = "climate.temp", humidityPath = "", temperatureUnit = "°F")
        assertEquals(HomeValue("temperature", "70.0", "°F"), reading("""{"climate":{"temp":70},"humidity":50}""", mapping = mapping).values.single())
        val scalar = HomeMqttTopic("room", temperaturePath = "$", humidityPath = "")
        assertEquals("19.0", reading("19", mapping = scalar).values.single().value)
        assertFalse(reading("""{"temperature":"NaN","humidity":"Infinity"}""").available)
        assertFalse(reading("""{"temperature":null,"humidity":false}""").available)
        assertFalse(reading("""{"temperature":-500,"humidity":200}""").available)
        assertFalse(reading("-1", mapping = scalar.copy(temperatureUnit = "K")).available)
        assertFalse(reading("-460", mapping = scalar.copy(temperatureUnit = "°F")).available)
    }

    @Test fun malformedOversizedOrDeepPayloadRemainsUnavailable() {
        listOf("{bad", "x".repeat(16_385), "[".repeat(30) + "0" + "]".repeat(30)).forEach { payload -> assertFalse(reading(payload).available) }
        assertFalse(mqttReading(connection(), connection().mqtt!!.topics.single(), byteArrayOf(0xff.toByte()), false, now).available)
    }

    @Test fun mappingEditsChangeIdentityAndWaitingIsNotAvailable() {
        val one = connection()
        val initial = mqttWaiting(one, one.mqtt!!.topics.single())
        val changed = mqttWaiting(one, one.mqtt!!.topics.single().copy(temperaturePath = "another"))
        assertNotEquals(initial.identity, changed.identity)
        assertFalse(initial.available)
        assertEquals(0, initial.observedAt)
        assertNull(initial.expiresAt)
    }

    @Test fun anonymousCredentialsAreExplicitAndPasswordIsNotTrimmed() {
        assertEquals(HomeMqttCredentials(), mqttCredentials("{}"))
        val credentials = HomeMqttCredentials("reader", " spaces matter ")
        assertEquals(credentials, mqttCredentials(Json.encodeToString(credentials)))
        assertFailsWith<IllegalArgumentException> { mqttCredentials(null) }
        assertFailsWith<IllegalArgumentException> { mqttCredentials("""{"password":"secret"}""") }
        assertFailsWith<HomeException> { mqttCredentials("not-json") }
    }

    @Test fun executeRejectsWithoutOpeningTransport(): Unit = runBlocking {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; error("No network request allowed") })
        val secrets = object : SignalSecrets { override suspend fun get(provider: String) = "{}"; override suspend fun put(provider: String, key: String) = Unit }
        val connector = SignalMqttConnector(http, connection(), secrets, clock = { now },
            mqttHttpFactory = { requests++; error("No MQTT transport allowed") })
        try {
            assertFailsWith<HomeException> { connector.execute(HomeAction("id", "mqtt", "room", "publish", createdAt = now)) }
            assertEquals(0, requests)
        } finally { connector.close(); http.close() }
    }

    @Test fun framingHandlesPacketSplitsAndMultiplePackets() {
        val framer = SignalMqttFramer()
        assertTrue(framer.accept(byteArrayOf(0x20, 0x03, 0x00)).isEmpty())
        val frames = framer.accept(byteArrayOf(0x00, 0x00, 0xd0.toByte(), 0x00))
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(0x20, 0x03, 0x00, 0x00, 0x00), frames[0])
        assertContentEquals(byteArrayOf(0xd0.toByte(), 0x00), frames[1])
    }

    @Test fun framingRejectsOversizedDeclaredPacketsBeforeBodyAndMalformedLengths() {
        assertFailsWith<IllegalArgumentException> { SignalMqttFramer().accept(byteArrayOf(0x30, 0xff.toByte(), 0xff.toByte(), 0x7f)) }
        assertFailsWith<IllegalArgumentException> { SignalMqttFramer().accept(byteArrayOf(0x30, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())) }
        assertFailsWith<IllegalArgumentException> { SignalMqttFramer().accept(ByteArray(SIGNAL_MQTT_PACKET_LIMIT + 1)) }
    }
}
