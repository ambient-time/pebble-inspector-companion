package coredevices.pebble.signal

import kotlin.test.*

class SignalCaptureSummaryTest {
    private val at = 1_789_000_000_000L
    private fun reading(status: String, measured: Long? = at) = SignalObservation("test", "phone", "42", collectedAt = at, measuredAt = measured, status = status)
    private fun record(vararg readings: SignalObservation) = SignalRecord("capture", "thread", at, "Capture", provider = "local", model = "", observations = readings.toList())
    @Test fun missingAndEstimatedOutcomesAreNotPresentedAsFreshMeasurements() {
        val summary = signalObservationCoverage(record(reading("fresh"), reading("cached"), reading("modeled"), reading("permission_denied")))
        assertEquals("Readings: 1 fresh at capture · 1 estimated or modeled · 1 older or cached · 1 unavailable", summary)
    }
    @Test fun timestampsAndImportedPeriodsDoNotBorrowCaptureFreshness() {
        val summary = signalObservationCoverage(record(reading("available", null), reading("fresh", at - 120_000), reading("recorded", at - 86_400_000)))
        assertContains(summary, "1 age unknown")
        assertContains(summary, "1 older or time uncertain")
        assertContains(summary, "1 recorded for a period")
        assertFalse(summary.contains("fresh at capture"))
        assertEquals("No readings saved.", signalObservationCoverage(record()))
    }
}
