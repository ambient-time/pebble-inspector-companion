package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalHomeEnvironmentCompatibilityTest {
    @Test fun existingHomeDocumentsAcquireDefaultsWithoutChangingConnections() {
        val home = Json.decodeFromString<HomeState>("""{"connections":[{"id":"home","name":"Home","kind":"HOME_ASSISTANT","baseUrl":"https://example.test"}],"snapshot":{"entities":[{"connectionId":"home","id":"sensor.room","name":"Room","state":"22"}]}}""")
        assertEquals(HomeTemperatureUnit.SOURCE, home.temperatureUnit)
        assertNull(home.connections.single().mqtt)
        assertNull(home.snapshot.entities.single().expiresAt)
        assertFalse(home.snapshot.entities.single().retained)
        assertEquals("", home.snapshot.entities.single().observationBasis)
    }

    @Test fun existingActionConnectionBindingIsByteForByteUnchanged() {
        val connection = HomeConnection("home", "Home", HomeConnectorKind.HOME_ASSISTANT, "https://example.test/")
        assertEquals("""["home","HOME_ASSISTANT","https://example.test","home:home","false","1"]""", homeConnectionBinding(connection))
    }

    @Test fun mqttMappingChangesInvalidateTheConnectionBinding() {
        val topic = HomeMqttTopic("test/room", "Room")
        val connection = HomeConnection("mqtt", "Broker", HomeConnectorKind.MQTT, "wss://example.test/mqtt", mqtt = HomeMqttConfig(listOf(topic)))
        val original = homeConnectionBinding(connection)
        listOf(topic.copy(topic = "test/other"), topic.copy(temperaturePath = "other"), topic.copy(temperatureUnit = "°F"), topic.copy(staleAfterSeconds = 60)).forEach { changed ->
            assertNotEquals(original, homeConnectionBinding(connection.copy(mqtt = HomeMqttConfig(listOf(changed)))))
        }
    }

    @Test fun displayPreferenceRoundTripsWithoutRewritingSourceValues() {
        val entity = HomeEntity("home", "sensor", "Room", values = listOf(HomeValue("temperature", "20", "°C", 1)))
        val home = HomeState(snapshot = HomeSnapshot(listOf(entity)), temperatureUnit = HomeTemperatureUnit.FAHRENHEIT)
        val restored = Json.decodeFromString<HomeState>(Json.encodeToString(home))
        assertEquals(home, restored)
        assertEquals("20", restored.snapshot.entities.single().values.single().value)
        assertEquals("°C", restored.snapshot.entities.single().values.single().unit)
    }
}
