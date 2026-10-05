package coredevices.pebble.signal

import io.ktor.client.HttpClient

class SignalHomeConnectorFactory(private val http:HttpClient, private val secrets:SignalSecrets, private val clock:()->Long) {
    fun create(connection:HomeConnection):HomeConnector {
        require(connection.enabled) { "Connection is disabled." }
        if (connection.kind == HomeConnectorKind.MQTT) return SignalMqttConnector(http, connection, secrets, clock)
        if (connection.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT) return SignalOutdoorConnector(http, connection, clock)
        val transport=SignalHomeTransport(http,connection,secrets)
        return when(connection.kind) {
            HomeConnectorKind.HOME_ASSISTANT -> SignalHomeAssistantConnector(transport,clock)
            HomeConnectorKind.OPENHAB -> SignalOpenHabConnector(transport,clock)
            HomeConnectorKind.GEEPERS -> SignalGeepersHomeConnector(transport,clock)
            HomeConnectorKind.MQTT -> error("MQTT uses its own transport")
            HomeConnectorKind.PUBLIC_ENVIRONMENT -> error("Public environment uses its own transport")
        }
    }
}
