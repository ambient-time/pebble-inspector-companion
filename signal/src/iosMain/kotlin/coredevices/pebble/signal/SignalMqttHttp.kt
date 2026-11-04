package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.ChannelOverflow

internal actual fun createSignalMqttHttpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    install(HttpTimeout) { connectTimeoutMillis = 3_000 }
    install(WebSockets) {
        maxFrameSize = SIGNAL_MQTT_PACKET_LIMIT.toLong()
        channels { incoming = bounded(16, ChannelOverflow.CLOSE); outgoing = bounded(16) }
    }
}
