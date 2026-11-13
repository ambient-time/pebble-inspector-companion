package coredevices.pebble.signal

import kotlin.test.*

class SignalRadioChangesTest {
    private fun record(id: String, at: Long, address: String? = null, slot: String = "0", level: Double = -50.0) = SignalRecord(id, "thread", at, "Capture", provider = "local", model = "", state = "ready", kind = "capture",
        observations = listOfNotNull(SignalObservation("wifi", "phone", collectedAt = at, measuredAt = at, status = "fresh", metric = "rssi", number = level, fields = mapOf("slot" to slot)),
            address?.let { SignalObservation("wifi.identifiers", "phone", collectedAt = at, measuredAt = at, status = "fresh", fields = mapOf("slot" to slot, "bssid" to it)) }))
    @Test fun changedScanOrderStillMatchesRetainedIdentity() {
        val text = SignalRadioChanges.describe(record("a", 1, "ab:cd:ef:01:02:03"), record("b", 2, "ab:cd:ef:01:02:03", "9", -55.0)).joinToString()
        assertContains(text, "Wi-Fi: 1 matched in both captures"); assertContains(text, "difference -5 dB")
        assertFalse(text.contains("ab:cd"))
    }
    @Test fun scanSlotsAndLabelsAloneNeverMatchDevices() {
        assertContains(SignalRadioChanges.describe(record("a", 1), record("b", 2)).joinToString(), "none is compared one to one")
        assertContains(SignalRadioChanges.describe(record("a", 1, "a"), record("b", 2, "b")).joinToString(), "none is compared one to one")
    }
    @Test fun duplicateOrCachedMeasurementsDoNotBecomeChange() {
        assertContains(SignalRadioChanges.describe(record("a", 2), record("b", 2)).joinToString(), "no newer scan to compare")
        val after = record("b", 3).let { it.copy(observations = it.observations.map { row -> row.copy(status = "cached") }) }
        assertTrue(SignalRadioChanges.describe(record("a", 1), after).isEmpty())
    }
}
