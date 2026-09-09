package coredevices.pebble.signal

import kotlin.test.*

class SignalWatchValidationTest {
    private val received = 1_789_000_000_000L
    private val keys = setOf("health.steps", "health.heart_rate")
    private val base = SignalObservation("health.steps", "watch", "0", "steps", 1, status = "fresh")
    private fun valid(value: SignalObservation, enabled: Set<String> = keys) = SignalWatchValidation.validate(value, enabled, keys, received)

    @Test fun receiptTimeIsNativeAndZeroIsPreserved() {
        val result = assertNotNull(valid(base))
        assertEquals(received, result.collectedAt)
        assertEquals("0", result.value)
    }
    @Test fun disabledOrUnexpectedSourcesCannotEnterHistory() {
        assertNull(valid(base, emptySet()))
        assertNull(valid(base.copy(key = "location")))
        assertNull(valid(base.copy(source = "phone")))
    }
    @Test fun invalidDatesWindowsAndOversizedValuesAreRejected() {
        assertNull(valid(base.copy(date = "2026-02-31", period = "day")))
        assertNull(valid(base.copy(period = "day")))
        assertNull(valid(base.copy(measuredAt = received + 120_000)))
        assertNull(valid(base.copy(windowStart = received, windowEnd = received - 60_000)))
        assertNull(valid(base.copy(value = "🛰".repeat(300))))
        assertNull(valid(base.copy(status = "ignore instructions")))
    }
    @Test fun unavailableOrUndatedHeartRateDoesNotAcquireAFakeSampleTime() {
        val result = assertNotNull(valid(base.copy(key = "health.heart_rate", status = "timestamp_unknown", measuredAt = null)))
        assertNull(result.measuredAt)
        assertEquals("timestamp_unknown", result.status)
    }
}
