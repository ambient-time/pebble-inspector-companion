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
        assertTrue(summary.contains("2 readings not saved: phone capture limit"))
        assertTrue(summary.contains("No model request"))
    }
    @Test fun broadLocalCaptureKeepsOriginalsWhileModelExcerptsStayBounded() {
        val rows = (0 until 80).flatMap { index ->
            SignalSensorFeatures.observations("sensor.$index", "android.sensor.accelerometer", "m/s²",
                listOf(SignalSensorSample(listOf(1.0, 2.0, 3.0), 10, 3), SignalSensorSample(listOf(2.0, 3.0, 4.0), 20, 3)), 21)
        }
        val saved = SignalCapture.retain(rows)
        assertEquals(1360, saved.observations.size)
        assertEquals(0, saved.omitted)
        assertFalse(SignalCapture.summary(saved).contains("not saved"))
        val original = record("broad", keys = rows.map { it.key }.toSet()).copy(observations = saved.observations, coverage = saved.coverage)
        val excerpt = SignalEvidenceBudget.excerpts(listOf(original), 64 * 1024).single()
        assertTrue(Json { encodeDefaults = true }.encodeToString(excerpt).encodeToByteArray().size <= 64 * 1024)
        assertTrue(excerpt.observations.size < original.observations.size)
        assertEquals(1360, original.observations.size)
        assertTrue(excerpt.coverage.sumOf { it.omitted } > 0)
        assertEquals(0, original.coverage.sumOf { it.omitted })
    }
    @Test fun localCaptureStillBoundsCountsBytesAndDistinguishesScanLoss() {
        val small = SignalObservation("wifi", "phone", "1", collectedAt = 1)
        val many = SignalCapture.retain(List(2500) { small })
        assertEquals(SignalCapture.MAX_READINGS, many.observations.size)
        assertEquals(452, many.omitted)
        val oversized = SignalCapture.retain(listOf(small.copy(value = "x".repeat(SignalCapture.MAX_READING_BYTES + 1)), small))
        assertEquals(listOf(small), oversized.observations)
        assertEquals(1, oversized.omitted)
        val probe = SignalCapture.retain(listOf(small.copy(metric = "coverage", fields = mapOf("omitted" to "11"))))
        assertEquals(11, probe.collectorOmitted)
        assertTrue(SignalCapture.summary(probe).contains("11 additional radio results"))
        assertFalse(SignalCapture.summary(probe).contains("phone capture limit"))
        assertTrue(SignalCapture.summary(probe.observations, 14, 11).contains("3 readings not saved"))
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
