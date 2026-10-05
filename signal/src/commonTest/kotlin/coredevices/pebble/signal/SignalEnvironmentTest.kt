package coredevices.pebble.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalEnvironmentTest {
    private fun sensor(vararg values: HomeValue, available: Boolean = true) = HomeEntity(
        "test", "sensor", "Unassigned sensor", available = available, values = values.toList())

    private fun reading(value: String, sourceUnit: String?, preference: HomeTemperatureUnit = HomeTemperatureUnit.SOURCE) =
        homeDisplayReadings(sensor(HomeValue("temperature", value, sourceUnit)), preference).single()

    @Test fun celsiusAndFahrenheitConvertWithoutChangingSource() {
        val entity = sensor(HomeValue("temperature", "22", "°C", 100))
        assertEquals(HomeDisplayReading("temperature", "Temperature", "71.6", "°F", true),
            homeDisplayReadings(entity, HomeTemperatureUnit.FAHRENHEIT).single())
        assertEquals(listOf(HomeValue("temperature", "22", "°C", 100)), entity.values)
        assertEquals("22", reading("71.6", "°F", HomeTemperatureUnit.CELSIUS).value)
        assertEquals("-40", reading("-40", "°C", HomeTemperatureUnit.FAHRENHEIT).value)
        assertEquals("0", reading("32", "°F", HomeTemperatureUnit.CELSIUS).value)
    }

    @Test fun scalarControllerReadingsUseDeclaredUnitsAndDeviceClass() {
        val ha = HomeEntity("ha", "sensor.room", "Room", state = "20", attributes = mapOf("device_class" to "temperature", "unit_of_measurement" to "°C"))
        assertEquals("68", homeDisplayReadings(ha, HomeTemperatureUnit.FAHRENHEIT).single().value)
        val openhab = HomeEntity("oh", "Thermometer", "Probe", domain = "Number:Temperature", state = "293.15 K", attributes = mapOf("unitSymbol" to "K"))
        assertEquals("20", homeDisplayReadings(openhab, HomeTemperatureUnit.CELSIUS).single().value)
        assertEquals("°C", homeDisplayReadings(openhab, HomeTemperatureUnit.CELSIUS).single().unit)
    }

    @Test fun explicitUnitKeysAndDuplicateCelsiusFahrenheitUseOneValue() {
        val entity = sensor(HomeValue("temperature_c", "20"), HomeValue("temperature_f", "68"), HomeValue("humidity", "42", "%"))
        for (preference in HomeTemperatureUnit.entries) assertEquals(2, homeDisplayReadings(entity, preference).size)
        assertEquals("20", homeDisplayReadings(entity, HomeTemperatureUnit.CELSIUS).first().value)
        assertEquals("68", homeDisplayReadings(entity, HomeTemperatureUnit.FAHRENHEIT).first().value)
        val invalidFirst = sensor(HomeValue("temperature_c", "NaN"), HomeValue("temperature_f", "68"))
        assertEquals("20", homeDisplayReadings(invalidFirst, HomeTemperatureUnit.CELSIUS).single().value)
    }

    @Test fun humidityIsExplicitAndBatteryPercentIsNotHumidity() {
        val entity = sensor(HomeValue("humidity", "45", "%"), HomeValue("battery", "78", "%"))
        val readings = homeDisplayReadings(entity, HomeTemperatureUnit.CELSIUS)
        assertEquals("Humidity", readings[0].label)
        assertEquals("Battery", readings[1].label)
        assertFalse(readings[1].prominent)
        val ha = HomeEntity("ha", "sensor.humidity", "Sensor", state = "43", attributes = mapOf("device_class" to "humidity", "unit_of_measurement" to "%"))
        assertEquals("Humidity", homeDisplayReadings(ha, HomeTemperatureUnit.SOURCE).single().label)
        assertEquals("State", homeDisplayReadings(ha.copy(attributes = mapOf("unit_of_measurement" to "%")), HomeTemperatureUnit.SOURCE).single().label)
    }

    @Test fun thermostatTargetIsNotPresentedAsAmbientReading() {
        val entity = sensor(HomeValue("temperature", "21", "°C"), HomeValue("current_temperature", "19", "°C")).copy(domain = "climate")
        val readings = homeDisplayReadings(entity, HomeTemperatureUnit.FAHRENHEIT)
        assertEquals(2, readings.size)
        assertEquals("Target temperature", readings.first().label)
        assertFalse(readings.first().prominent)
        assertEquals("Temperature", readings.last().label)
        assertTrue(readings.last().prominent)
        assertEquals("66.2", readings.last().value)
    }

    @Test fun namesDoNotClassifyBatteryTemperatureOrRoom() {
        val entity = sensor(HomeValue("battery_temperature", "37", "°C"), HomeValue("battery", "70", "%")).copy(name = "Living room humidity")
        val readings = homeDisplayReadings(entity, HomeTemperatureUnit.FAHRENHEIT)
        assertEquals("Battery temperature", readings.first().label)
        assertEquals("37", readings.first().value)
        assertFalse(readings.first().prominent)
        assertEquals("Battery", readings.last().label)
    }

    @Test fun unknownUnitsStayUnknownAndAreNeverAssumedCelsius() {
        val unknown = reading("21", null, HomeTemperatureUnit.FAHRENHEIT)
        assertEquals("21", unknown.value)
        assertEquals("unit unknown", unknown.unit)
        assertFalse(unknown.prominent)
        assertEquals("custom", reading("21", "custom", HomeTemperatureUnit.FAHRENHEIT).unit)
        val conflict = sensor(HomeValue("temperature_c", "20", "°F"))
        assertTrue(homeDisplayReadings(conflict, HomeTemperatureUnit.SOURCE).single().value.startsWith("Unavailable"))
    }

    @Test fun malformedAndNonFiniteValuesRemainVisibleButUnavailable() {
        for (value in listOf("", "unknown", "NaN", "Infinity", "-Infinity", "1e999")) {
            val reading = reading(value, "°C", HomeTemperatureUnit.FAHRENHEIT)
            assertEquals("Unavailable · $value", reading.value)
            assertFalse(reading.prominent)
        }
        assertTrue(reading("1.7976931348623157E308", "°C", HomeTemperatureUnit.FAHRENHEIT).value.startsWith("Unavailable"))
    }

    @Test fun numericBoundsPreserveUnknownRatherThanClamp() {
        assertTrue(reading("-273.16", "°C").value.startsWith("Unavailable"))
        assertFalse(reading("-273.15", "°C").value.startsWith("Unavailable"))
        assertTrue(reading("-459.68", "°F").value.startsWith("Unavailable"))
        assertTrue(reading("-1", "K").value.startsWith("Unavailable"))
        for (value in listOf("-1", "101")) assertEquals("Unavailable · $value",
            homeDisplayReadings(sensor(HomeValue("humidity", value, "%")), HomeTemperatureUnit.SOURCE).single().value)
        for (value in listOf("0", "100")) assertEquals(value,
            homeDisplayReadings(sensor(HomeValue("humidity", value, "%")), HomeTemperatureUnit.SOURCE).single().value)
    }

    @Test fun retainedUntimestampedValueCannotBecomeFreshOnReconnect() {
        val entity = sensor(HomeValue("temperature", "22", "°C")).copy(retained = true, observedAt = 99_000, expiresAt = 200_000)
        assertFalse(homeEntityAvailable(entity, 100_000))
        assertEquals("Retained value · age unknown", homeReadingStatus(entity, 100_000))
        assertEquals("Measurement time unknown", homeReadingAge(entity, 100_000))
        assertEquals("22", homeDisplayReadings(entity, HomeTemperatureUnit.SOURCE).single().value)
        assertFalse(homeEntityAvailable(entity.copy(observedAt = 100_000), 100_000))
    }

    @Test fun mqttTtlExpiresAtBoundaryWithoutChangingValue() {
        val entity = sensor(HomeValue("temperature", "22", "°C")).copy(expiresAt = 100_000, observedAt = 90_000)
        assertTrue(homeEntityAvailable(entity, 99_999))
        assertFalse(homeEntityAvailable(entity, 100_000))
        assertEquals("Stale reading", homeReadingStatus(entity, 100_000))
        assertEquals("22", homeDisplayReadings(entity, HomeTemperatureUnit.SOURCE).single().value)
        assertTrue(homeEntityAvailable(entity.copy(expiresAt = null), 999_999))
        assertFalse(homeEntityAvailable(entity.copy(available = false), 95_000))
    }

    @Test fun measuredReportedAndReceivedTimesHaveDifferentLabels() {
        val entity = sensor(HomeValue("temperature", "22", "°C", 40_000)).copy(updatedAt = 90_000, observedAt = 100_000)
        assertEquals("Measured 1 min ago", homeReadingAge(entity, 100_000))
        val unmeasured = entity.copy(values = listOf(HomeValue("temperature", "22", "°C")))
        assertEquals("Reported 10 sec ago · measurement time unknown", homeReadingAge(unmeasured, 100_000))
        assertEquals("Received just now · measurement time unknown", homeReadingAge(unmeasured.copy(updatedAt = null), 100_000))
        assertEquals("Measurement and receipt times unknown", homeReadingAge(unmeasured.copy(updatedAt = null, observedAt = 0), 100_000))
        assertTrue(homeEntityAvailable(unmeasured.copy(retained = true), 100_000))
        assertEquals("Retained value", homeReadingStatus(unmeasured.copy(retained = true), 100_000))
    }

    @Test fun multipleMeasurementTimesDoNotHideOlderOrUnknownMeasurements() {
        val entity = sensor(HomeValue("temperature", "22", "°C", 10_000), HomeValue("humidity", "50", "%", 40_000))
        assertEquals("Oldest measurement 1 min ago", homeReadingAge(entity, 100_000))
        assertEquals("Oldest measurement 1 min ago · other measurement times unknown", homeReadingAge(entity.copy(values = entity.values + HomeValue("battery", "70", "%")), 100_000))
        assertEquals("Measured time is in the future", homeReadingAge(sensor(HomeValue("temperature", "22", "°C", 200_000)), 100_000))
    }

    @Test fun waitingKeepsItsActualStateAndHasNoInventedNumber() {
        val entity = HomeEntity("test", "sensor", "Sensor", state = "Waiting for a message", observationBasis = "mqtt_waiting")
        assertEquals("Waiting for a reading", homeReadingStatus(entity, 100_000))
        assertEquals("Waiting for a message", homeDisplayReadings(entity, HomeTemperatureUnit.SOURCE).single().value)
        assertFalse(homeEntityAvailable(entity, 100_000))
    }
}
