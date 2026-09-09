package coredevices.pebble.signal

import kotlin.test.*
import kotlinx.serialization.json.Json

class SignalCaptureTest {
    private fun record(id: String, time: Long = 1, watch: String = "watch", keys: Set<String> = emptySet()) = SignalRecord(
        id, "thread", time, "capture", provider = "local", model = "", state = "ready", watchId = watch,
        kind = "capture", sourceKeys = keys,
    )
    @Test fun defaultMigrationPreservesOldRecordsAndRequiresOwnOnboarding() {
        val settings = Json.decodeFromString<SignalSettings>("{}")
        assertFalse(settings.onboardingComplete)
        val old = Json.decodeFromString<SignalRecord>("""{"id":"old","threadId":"t","createdAt":1,"question":"hi","provider":"x","model":"y"}""")
        assertEquals("analysis", old.kind)
    }
    @Test fun unavailableReadingsAreNotCountedAsMeasurements() {
        val rows = listOf(SignalObservation("location", "phone", collectedAt = 1, status = "permission_denied"),
            SignalObservation("device.battery", "phone", value = "50", collectedAt = 1))
        val summary = SignalCapture.summary(rows, 2)
        assertTrue(summary.contains("1 available, 1 unavailable or partial"))
        assertTrue(summary.contains("2 readings omitted"))
        assertTrue(summary.contains("No model request"))
    }
    @Test fun emptyCaptureExplainsHowToChooseSources() { assertTrue(SignalCapture.summary(emptyList()).contains("Choose sources")) }
    @Test fun historyExcludesDisabledOtherWatchAndUnfinishedRecords() {
        val rows = listOf(record("ok"), record("disabled", keys = setOf("location")), record("other", watch = "other"), record("pending").copy(state = "working"))
        val result = SignalCapture.watchHistory(rows, emptySet(), "watch")
        assertEquals(1, Regex("Capture:").findAll(result).count())
    }
    @Test fun historyHasFiveNewestRecordsAndUtf8Bound() {
        val rows = (1..10).map { record("$it", it.toLong()).copy(kind = "analysis", summary = "😀".repeat(500)) }
        val result = SignalCapture.watchHistory(rows, emptySet(), "watch")
        assertTrue(result.encodeToByteArray().size <= 900)
        assertEquals(5, Regex("\\d{4}-\\d{2}-\\d{2}").findAll(result).count())
        assertFalse(result.contains('\uFFFD'))
    }
}
