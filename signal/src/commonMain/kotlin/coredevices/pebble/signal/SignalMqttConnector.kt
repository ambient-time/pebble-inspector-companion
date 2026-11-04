package coredevices.pebble.signal

import de.kempmobil.ktor.mqtt.*
import de.kempmobil.ktor.mqtt.packet.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class SignalMqttConnector(
    @Suppress("UNUSED_PARAMETER") http: HttpClient,
    private val connection: HomeConnection,
    private val secrets: SignalSecrets,
    private val clock: () -> Long,
    private val mqttHttpFactory: () -> HttpClient = ::createSignalMqttHttpClient,
) : HomeConnector {
    private val url = validateMqttConnection(connection)
    private val topics = requireNotNull(connection.mqtt).topics
    private var active: MqttClient? = null
    private var closed = false

    override suspend fun catalog(): List<HomeEntity> {
        val result = topics.associate { it.topic to mqttWaiting(connection, it) }.toMutableMap()
        events(snapshot = true).collect { result[it.id] = it }
        return topics.map { result.getValue(it.topic) }
    }

    override fun foregroundEvents(): Flow<HomeEntity> = events(snapshot = false)

    override suspend fun execute(action: HomeAction): HomeDispatchResult = throw HomeException("MQTT connections are read only.")

    private fun events(snapshot: Boolean): Flow<HomeEntity> = flow {
        check(!closed) { "MQTT connection is closed." }
        val credentials = mqttCredentials(secrets.get(connection.credentialKey))
        val transport = SignalMqttWebSocketEngine(mqttHttpFactory(), url)
        val factory = object : MqttEngineFactory<MqttEngineConfig> {
            override fun create(block: MqttEngineConfig.() -> Unit): MqttEngine = transport
        }
        val client = MqttClient(buildConfig(factory) {
            clientId = "signal-" + List(20) { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
            username = credentials.username.takeIf { it.isNotEmpty() }
            password = credentials.password.takeIf { it.isNotEmpty() }
            ackMessageTimeout = 3.seconds
            keepAliveSeconds = 20u
            sessionExpiryInterval = 0.seconds
            maximumPacketSize = SIGNAL_MQTT_PACKET_LIMIT.toUInt()
            receiveMaximum = 1u
            topicAliasMaximum = 0u
            requestProblemInformation = false
            logging { minSeverity = co.touchlab.kermit.Severity.Assert }
        })
        active = client
        try {
            coroutineScope {
                val incoming = Channel<HomeEntity>(20)
                val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.publishedPackets.collect { packet ->
                        val mapping = topics.firstOrNull { it.topic == packet.topic.name } ?: throw HomeException("Broker sent an unrequested topic.")
                        incoming.send(mqttReading(connection, mapping, packet.payload.toByteArray(), packet.isRetainMessage, clock()))
                    }
                }
                try {
                    val subscribedInTime = withTimeoutOrNull(6_000) {
                        val ack = client.connect(isCleanStart = true).getOrElse {
                            currentCoroutineContext().ensureActive()
                            throw if (it is HomeException) it else HomeException("MQTT connection failed. Check the WebSocket endpoint and credentials.")
                        }
                        if (!ack.isSuccess) throw HomeException("MQTT broker refused the connection.")
                        val filters = buildFilterList { topics.forEach { add(it.topic, qoS = QoS.AT_MOST_ONCE, retainAsPublished = false) } }
                        val subscribed = client.subscribe(filters).getOrElse {
                            currentCoroutineContext().ensureActive()
                            throw HomeException("MQTT subscription failed.")
                        }
                        if (subscribed.hasFailure || subscribed.reasons.size != topics.size) throw HomeException("MQTT broker refused one or more topics.")
                        true
                    }
                    if (subscribedInTime != true) throw HomeException("MQTT connection timed out.")
                    topics.forEach { emit(mqttWaiting(connection, it)) }
                    val disconnection = launch {
                        transport.connected.first { !it }
                        incoming.close(HomeException("MQTT disconnected. Saved readings may be stale."))
                    }
                    try {
                        if (snapshot) withTimeoutOrNull(800) { for (entity in incoming) emit(entity) }
                        else for (entity in incoming) emit(entity)
                    } finally { disconnection.cancel() }
                } finally { receiver.cancel(); incoming.cancel() }
            }
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(300) { runCatching { client.disconnect() } } }
            client.close()
            if (active === client) active = null
        }
    }

    override fun close() { closed = true; active?.close(); active = null }
}
