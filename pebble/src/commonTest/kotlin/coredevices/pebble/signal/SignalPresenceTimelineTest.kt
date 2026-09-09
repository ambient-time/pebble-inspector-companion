package coredevices.pebble.signal

import kotlin.test.*

class SignalPresenceTimelineTest {
    private val now = 500_000L
    private val target = SignalPresenceTarget("desk", "bluetooth", "AA", "Desk")
    private val settings = SignalSettings(enabled = setOf("presence.bluetooth"), presenceTargets = listOf(target))
    private fun record(id: String, at: Long, status: String = "observed", measuredAt: Long? = at) = SignalRecord(
        id, "thread", at, "Presence check", provider = "local", model = "", state = "ready",
        sourceKeys = setOf("presence.bluetooth"), observations = listOf(SignalObservation(
            "presence.bluetooth", "phone", collectedAt = at, measuredAt = measuredAt, status = status, identity = "target:desk")),
    )
    private fun evidence(vararg records: SignalRecord, time: Long = now) = SignalPresenceTimeline.entries(settings, records.toList(), time).single()

    @Test fun separateFreshCapturesShowRepeatedEvidence() {
        val result = evidence(record("a", now - 60_000), record("b", now))
        assertEquals("observed_repeatedly", result.state)
        assertEquals(2, result.freshCaptures)
        assertEquals(now, result.lastSeenAt)
    }
    @Test fun singleMissingScanDoesNotBecomeDepartureOrEraseLastSeen() {
        val result = evidence(record("a", now - 10_000), record("b", now, "not_observed", null))
        assertEquals("not_observed", result.state)
        assertEquals(now - 10_000, result.lastSeenAt)
        assertEquals(1, result.freshCaptures)
    }
    @Test fun oldAndUnavailableCapturesAreUnknown() {
        assertEquals("unknown", evidence(record("a", now - 16_000)).state)
        assertEquals("unknown", evidence(record("a", now - 60_000), record("b", now, "unknown", null)).state)
        assertEquals("unknown", evidence(record("a", now, measuredAt = now - 20_000)).state)
        assertEquals("unknown", evidence(record("a", now, measuredAt = now + 1)).state)
        assertEquals("unknown", evidence(record("a", now + 1)).state)
        assertEquals("ambiguous", evidence(record("a", now, "ambiguous")).state)
    }
    @Test fun repeatedPacketCopiesAndDerivedRecordsCannotInflateCaptureCount() {
        val one = record("one", now)
        val derived = record("derived", now - 1).copy(references = listOf("one"))
        val result = evidence(one, one, one.copy(id = "copy"), derived)
        assertEquals("observed_once", result.state)
        assertEquals(1, result.freshCaptures)
        assertEquals(0, evidence(record("working", now).copy(state = "working")).freshCaptures)
    }
    @Test fun disabledSourcesTargetsAndMixedDisabledProvenanceStayHidden() {
        val rows = listOf(record("a", now))
        assertTrue(SignalPresenceTimeline.entries(settings.copy(enabled = emptySet()), rows, now).isEmpty())
        assertTrue(SignalPresenceTimeline.entries(settings.copy(presenceTargets = listOf(target.copy(enabled = false))), rows, now).isEmpty())
        assertEquals("unknown", evidence(rows.single().copy(sourceKeys = setOf("presence.bluetooth", "presence.wifi"))).state)
    }
    @Test fun fiveMinuteEvidenceWindowExpiresWithoutDroppingHistoricalLastSeen() {
        val old = record("old", now - 300_001)
        assertEquals(0, evidence(old).freshCaptures)
        assertEquals(old.createdAt, evidence(old).lastSeenAt)
        assertEquals("observed_once", evidence(old, record("current", now)).state)
    }
}
