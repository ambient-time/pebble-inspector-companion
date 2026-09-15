package coredevices.pebble.signal

import io.ktor.client.HttpClient

class SignalHomeConnectorFactory(private val http:HttpClient, private val secrets:SignalSecrets, private val clock:()->Long) {
    fun create(connection:HomeConnection):HomeConnector {
        require(connection.enabled) { "Connection is disabled." }
        val transport=SignalHomeTransport(http,connection,secrets)
        return when(connection.kind) {
            HomeConnectorKind.HOME_ASSISTANT -> SignalHomeAssistantConnector(transport,clock)
            HomeConnectorKind.OPENHAB -> SignalOpenHabConnector(transport,clock)
            HomeConnectorKind.GEEPERS -> SignalGeepersHomeConnector(transport,clock)
        }
    }
}
