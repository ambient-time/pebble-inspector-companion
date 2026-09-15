package coredevices.pebble.signal

import kotlin.test.*

class SignalHomeReadingsTest {
    @Test fun unitsStaySeparateAndSourceUpdateDoesNotBecomeMeasurementTime() {
        for ((state, key) in listOf("22" to "unit_of_measurement", "22 °C" to "unitSymbol")) {
            val entity = HomeEntity("c", "sensor", "Temperature", state = state, updatedAt = 100, observedAt = 200, attributes = mapOf(key to "°C"))
            assertEquals(HomeValue("state", "22", "°C", null), homeReadings(entity).single())
        }
    }
    @Test fun independentNativeMeasurementTimesArePreserved() {
        val values = listOf(HomeValue("temperature", "22", "°C", 10), HomeValue("humidity", "40", "%", null))
        assertEquals(values, homeReadings(HomeEntity("c", "sensor", "Room", updatedAt = 100, observedAt = 200, values = values)))
    }
}
