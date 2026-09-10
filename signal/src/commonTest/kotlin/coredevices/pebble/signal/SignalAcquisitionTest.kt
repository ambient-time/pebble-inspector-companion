package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalAcquisitionTest {
    private fun reading(key: String, value: String = "1") = SignalObservation(key, "phone", value, collectedAt = 10)

    @Test fun presenceDependenciesAreCollectedOnceAndNotExposedAsSeparateSources() {
        val enabled = setOf("wifi", "presence.wifi", "presence.places")
        assertEquals(setOf("wifi", "presence.wifi", "presence.places", "location"), SignalAcquisitionPlan.required(enabled))
        val readings = listOf(reading("wifi"), reading("wifi.identifiers", "private"), reading("location", "coordinates"), reading("presence.places"))
        assertEquals(listOf("wifi", "presence.places"), SignalAcquisitionPlan.visible(readings, enabled).map { it.key })
    }
    @Test fun denseRadiosCannotStarveOtherSourcesAtEitherBudget() {
        val dense = (1..500).map { reading("bluetooth") } + (1..500).map { reading("wifi") }
        val other = listOf("location", "sensor.5", "health.steps", "watch.battery").map { reading(it) }
        for (limit in listOf(64, 200)) {
            val budget = SignalBudget.retain(dense + other, limit)
            assertEquals(limit, budget.observations.size)
            assertTrue(other.all { it in budget.observations })
            assertEquals(dense.size + other.size - limit, budget.omitted)
            assertTrue(budget.coverage.all { it.observed == it.retained + it.omitted })
        }
    }
    @Test fun oversizedReadingDoesNotEvictSmallMeasurementsAndProbeOmissionsSurvive() {
        val rows = listOf(reading("bluetooth", "x".repeat(100)), reading("location", "fix"),
            reading("wifi", "summary").copy(fields = mapOf("omitted" to "11")))
        val budget = SignalBudget.retain(rows, 200, 10) { it.value.length }
        assertEquals(listOf("location", "wifi"), budget.observations.map { it.key })
        assertEquals(12, budget.omitted)
        assertEquals(11, budget.coverage.single { it.key == "wifi" }.omitted)
    }
    @Test fun featuresAreNumericAndPreserveQualityWindowAndUnits() {
        val samples = listOf(SignalSensorSample(listOf(1.0, Double.NaN), 10, 2), SignalSensorSample(listOf(3.0, 9.0), 20, 3))
        val rows = SignalSensorFeatures.observations("sensor.1", "android.sensor.accelerometer", "m/s²", samples, 21)
        assertEquals(2.0, rows.single { it.metric == "axis_0.mean" }.number)
        assertEquals(1.0, rows.single { it.metric == "axis_0.stddev" }.number)
        assertEquals(1, rows.single { it.metric == "axis_1.mean" }.sampleCount)
        assertTrue(rows.all { it.windowStart == 10L && it.windowEnd == 20L && it.unit == "m/s²" && it.accuracy == 3 })
    }
    @Test fun cumulativeCounterIsNeverLabeledDailySteps() {
        val row = SignalSensorFeatures.observations("sensor.19", "counter", "steps", listOf(SignalSensorSample(listOf(14000.0), 10, 3)), 11, cumulativeCounter = true).single()
        assertEquals("since_reboot", row.period)
        assertEquals("cumulative_since_reboot", row.metric)
    }
    @Test fun angularWrapDoesNotProduceAnArithmeticHeading() {
        val samples = listOf(SignalSensorSample(listOf(359.0), 10, 3), SignalSensorSample(listOf(1.0), 20, 3))
        val row = SignalSensorFeatures.observations("sensor.3", "android.sensor.orientation", "degrees", samples, 21).single()
        assertEquals("axis_0.latest", row.metric)
        assertEquals(1.0, row.number)
    }
    @Test fun motionMagnitudeRetainsVariabilityIndependentOfAxisDirection() {
        val samples = listOf(SignalSensorSample(listOf(3.0, 0.0, 4.0), 10, 3), SignalSensorSample(listOf(0.0, 3.0, 4.0), 20, 3))
        val rows = SignalSensorFeatures.observations("sensor.1", "android.sensor.accelerometer", "m/s²", samples, 21)
        assertEquals(5.0, rows.single { it.metric == "magnitude.mean" }.number)
        assertEquals(0.0, rows.single { it.metric == "magnitude.stddev" }.number)
    }
    @Test fun additiveSchemaReadsOldOriginalWithoutChangingItsIdentityOrValue() {
        val old = """{"key":"sensor.5","source":"phone","value":"legacy sensor text","collectedAt":7,"id":"stable:0"}"""
        val row = Json.decodeFromString<SignalObservation>(old)
        assertEquals("stable:0", row.id)
        assertEquals("legacy sensor text", row.value)
        assertEquals("", row.metric)
        assertNull(row.number)
        assertEquals(row, Json.decodeFromString(Json.encodeToString(row)))
    }
}
