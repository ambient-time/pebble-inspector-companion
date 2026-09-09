package coredevices.pebble.signal

import kotlin.test.*
import kotlinx.serialization.json.Json

class SignalFieldTestTest {
    @Test fun incompleteTrialIsHonestAndRoundTrips() {
        val trial = SignalFieldTest("trial", 1)
        assertTrue(trial.valid())
        assertTrue(trial.summary().contains("10 unrecorded"))
        assertTrue(trial.summary().contains("False triggers not recorded"))
        assertTrue(trial.copy(falseTriggers = 0).summary().contains("0 false triggers reported"))
        assertTrue(trial.summary().contains("still in progress"))
        assertEquals(trial, Json.decodeFromString<SignalFieldTest>(trial.encode()))
    }
    @Test fun rejectsInvalidCountsTimesAndDuplicateStops() {
        val trial = SignalFieldTest("trial", 10)
        assertFalse(trial.copy(endedAt = 9).valid())
        assertFalse(trial.copy(attempts = listOf("recognized")).valid())
        assertFalse(trial.copy(falseTriggers = -1).valid())
        assertFalse(trial.copy(startBattery = 101).valid())
        assertFalse(trial.copy(stopRecordIds = listOf("a", "a", "")).valid())
    }
    @Test fun linkedCaptureProvenanceFlowsIntoRecordAndMissingReferencesFail() {
        val trial = SignalFieldTest("trial", 10, stopRecordIds = listOf("capture", "", ""))
        val record = SignalRecord("capture", "t", 1, "capture", provider = "local", model = "", state = "ready", observations = listOf(SignalObservation("location", "phone", collectedAt = 1)))
        val result = trial.record(listOf(record))
        assertEquals(setOf("location"), result.sourceKeys)
        assertEquals(listOf("capture"), result.references)
        assertEquals("field_test", result.kind)
        assertFailsWith<IllegalStateException> { trial.record(emptyList()) }
    }
}
