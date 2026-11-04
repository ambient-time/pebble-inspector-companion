package coredevices.pebble.signal

import kotlinx.serialization.json.*
import kotlin.test.*

/** Wire compatibility for the no-measurement timestamps emitted by watch 1.8.1. */
class SignalObservationCompatibilityTest {
    private val received = 1_789_000_000_000L
    private val keys = setOf("health.heart_rate", "health.steps")

    private fun decode(payload: JsonElement): SignalObservation {
        val row = assertNotNull(SignalWire.observations(buildJsonArray { add(payload) }, received)).single()
        return assertNotNull(SignalWatchValidation.validate(row, keys, keys, received))
    }

    @Test fun absentOrDeniedWatchReadingKeepsItsAttemptedWindowWithoutMeasurementTime() {
        for (status in listOf("unavailable", "permission_denied", "not_supported")) {
            val row = decode(buildJsonObject {
                put("key", "health.heart_rate"); put("source", "watch")
                put("value", JsonNull); put("unit", "bpm"); put("status", status)
                put("collectedAt", 1); put("period", "current")
                put("windowStart", received - 60_000); put("windowEnd", received)
                // The corrected watch omits measuredAt instead of using receipt time.
            })
            assertNull(row.measuredAt, status)
            assertEquals(received, row.collectedAt)
            assertEquals(received - 60_000, row.windowStart)
            assertEquals(received, row.windowEnd)
            assertEquals("", row.value)
            assertEquals(status, row.status)
        }
    }

    @Test fun realZeroKeepsItsTimestampAndUndatedHeartRateStaysUndated() {
        val zero = decode(buildJsonObject {
            put("key", "health.steps"); put("source", "watch"); put("value", 0)
            put("unit", "steps"); put("status", "fresh"); put("collectedAt", 1)
            put("measuredAt", received); put("windowStart", received); put("windowEnd", received)
        })
        assertEquals("0", zero.value)
        assertEquals(received, zero.measuredAt)

        val undated = decode(buildJsonObject {
            put("key", "health.heart_rate"); put("source", "watch"); put("value", 72)
            put("unit", "bpm"); put("status", "timestamp_unknown"); put("collectedAt", 1)
        })
        assertEquals("72", undated.value)
        assertNull(undated.measuredAt)
        assertNull(undated.windowStart)
        assertNull(undated.windowEnd)
    }
}
