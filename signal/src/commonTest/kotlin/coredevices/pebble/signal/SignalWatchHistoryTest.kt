package coredevices.pebble.signal

import kotlinx.serialization.json.*
import kotlin.test.*

class SignalWatchHistoryTest {
    private val end = 1_789_000_020_000L
    private val start = end - 900_000
    private val received = end + 5_000
    private val keys = setOf(SignalWatchHistory.KEY)

    private fun reading(minutes: String = "[[0,0,0,null,null],null,[12,65535,255,4,72]]", offset: Long = 120_000): SignalObservation {
        val rows = Json.parseToJsonElement(minutes).jsonArray
        val valid = rows.count { it != JsonNull }
        return SignalObservation(SignalWatchHistory.KEY, "watch", buildJsonObject {
            put("schema", 1); put("requested_minutes", 15); put("returned_minutes", rows.size); put("valid_minutes", valid)
            put("requested_start_ms", start); put("requested_end_ms", end)
            put("columns", JsonArray(listOf("steps", "vmc", "orientation", "light", "heart_rate_bpm").map(::JsonPrimitive)))
            put("minutes", rows)
        }.toString(), "minute_records", 1, measuredAt = if (valid > 0) start + offset + rows.size * 60_000 else null,
            status = if (valid > 0) "available" else "unavailable", period = SignalWatchHistory.PERIOD,
            windowStart = if (rows.isNotEmpty()) start + offset else null,
            windowEnd = if (rows.isNotEmpty()) start + offset + rows.size * 60_000 else null)
    }

    private fun SignalObservation.changed(vararg fields: Pair<String, JsonElement>): SignalObservation = copy(
        value = JsonObject(Json.parseToJsonElement(value).jsonObject.toMutableMap().apply { putAll(fields) }).toString())

    private fun valid(value: SignalObservation, enabled: Set<String> = keys) =
        SignalWatchValidation.validate(value, enabled, keys, received)

    @Test fun partialRowsKeepActualTimingAndUnknowns() {
        val original = reading()
        val result = assertNotNull(valid(original))
        val data = assertNotNull(SignalWatchHistory.parse(result))
        assertEquals(received, result.collectedAt)
        assertEquals(start + 120_000, result.windowStart)
        assertEquals(start + 300_000, result.windowEnd)
        assertEquals(result.windowEnd, result.measuredAt)
        assertEquals(3, data.minutes.size)
        assertEquals(2, data.validMinutes)
        assertEquals(SignalWatchMinute(0, 0, 0, null, null), data.minutes[0])
        assertNull(data.minutes[1])
        assertEquals(72, data.minutes[2]?.heartRate)
        assertFalse(SignalLearning.fresh(result))
        assertEquals(original.value, result.value)
    }

    @Test fun fullHistoryFitsTheExistingValueBudget() {
        val row = "[255,65535,255,4,255]"
        val value = reading("[${List(15) { row }.joinToString()}]", offset = 0)
        assertTrue(value.value.encodeToByteArray().size < 1000)
        assertNotNull(valid(value))
    }

    @Test fun historyRequiresItsOwnExplicitSourceAndPeriod() {
        val value = reading()
        assertNull(valid(value, emptySet()))
        assertNull(valid(value, setOf("watch.motion", "health.steps", "health.heart_rate")))
        assertNull(valid(value.copy(period = "current")))
        assertNull(SignalWatchValidation.validate(value.copy(key = "health.steps"), setOf("health.steps"), setOf("health.steps"), received))
        assertNull(valid(value.copy(source = "phone")))
        assertFalse(SignalWatchHistory.KEY in SignalSettings().enabled)
        SignalContextPresets.all.forEach { assertFalse(SignalWatchHistory.KEY in it.keys) }
        assertEquals(SignalWatchHistory.KEY, SignalWatchHistory.source.key)
    }

    @Test fun noReturnedHistoryHasNoInventedMeasurementWindow() {
        val empty = reading("[]")
        for (status in listOf("unavailable", "permission_denied", "not_supported")) {
            val result = assertNotNull(valid(empty.copy(status = status)))
            assertNull(result.measuredAt); assertNull(result.windowStart); assertNull(result.windowEnd)
        }
        assertNull(valid(empty.copy(status = "available")))
        assertNull(valid(empty.copy(measuredAt = end)))
        assertNull(valid(empty.copy(windowStart = end, windowEnd = end)))
    }

    @Test fun invalidReturnedMinutesRetainTheWindowButNoMeasurementTime() {
        val invalid = reading("[null,null]")
        val result = assertNotNull(valid(invalid))
        assertEquals("unavailable", result.status)
        assertNull(result.measuredAt)
        assertEquals(start + 120_000, result.windowStart)
        assertEquals(start + 240_000, result.windowEnd)
        assertNull(valid(invalid.copy(status = "available")))
        assertNull(valid(invalid.copy(measuredAt = invalid.windowEnd)))
        assertNull(valid(reading().copy(status = "fresh")))
        assertNull(valid(reading().copy(status = "permission_denied")))
    }

    @Test fun schemaColumnsAndCountsMustMatch() {
        val value = reading()
        assertNull(valid(value.changed("schema" to JsonPrimitive(2))))
        assertNull(valid(value.changed("schema" to JsonPrimitive("1"))))
        assertNull(valid(value.changed("requested_minutes" to JsonPrimitive(30))))
        assertNull(valid(value.changed("returned_minutes" to JsonPrimitive(4))))
        assertNull(valid(value.changed("valid_minutes" to JsonPrimitive(3))))
        assertNull(valid(value.changed("valid_minutes" to JsonPrimitive(-1))))
        assertNull(valid(value.changed("columns" to JsonArray(listOf("vmc", "steps", "orientation", "light", "heart_rate_bpm").map(::JsonPrimitive)))))
        assertNull(valid(value.changed("unrecognized" to JsonPrimitive(1))))
        assertNull(valid(value.copy(value = "{}")))
        assertNull(valid(value.copy(value = "[invalid")))
    }

    @Test fun rejectMalformedRowsAndOutOfRangeCells() {
        for (rows in listOf("[[]]", "[[0,0,0,null]]", "[[0,0,0,null,null,0]]", "[[null,0,0,null,null]]",
            "[[0.5,0,0,null,null]]", "[[\"0\",0,0,null,null]]", "[[false,0,0,null,null]]", "[{}]",
            "[[-1,0,0,null,null]]", "[[256,0,0,null,null]]", "[[0,65536,0,null,null]]",
            "[[0,0,256,null,null]]", "[[0,0,0,0,null]]", "[[0,0,0,5,null]]",
            "[[0,0,0,null,0]]", "[[0,0,0,null,256]]")) assertNull(valid(reading(rows)), rows)
        assertNull(valid(reading("[${List(16) { "null" }.joinToString()}]", offset = 0)))
        assertNull(valid(reading().copy(number = 3.0)))
        assertNull(valid(reading().copy(boolean = true)))
        assertNull(valid(reading().copy(unit = "lux")))
    }

    @Test fun rejectMisalignedMismatchedAndFutureWindows() {
        val value = reading()
        val actualStart = assertNotNull(value.windowStart)
        val actualEnd = assertNotNull(value.windowEnd)
        val measured = assertNotNull(value.measuredAt)
        assertNull(valid(value.copy(windowStart = actualStart + 1, windowEnd = actualEnd + 1, measuredAt = measured + 1)))
        assertNull(valid(value.copy(windowStart = start - 60_000)))
        assertNull(valid(value.copy(windowEnd = actualEnd + 60_000)))
        assertNull(valid(value.copy(measuredAt = end)))
        assertNull(valid(value.copy(measuredAt = null)))
        assertNull(valid(value.changed("requested_end_ms" to JsonPrimitive(end + 1))))
        assertNull(valid(value.changed("requested_start_ms" to JsonPrimitive(start - 60_000))))
        val future = value.changed("requested_start_ms" to JsonPrimitive(start + 120_000), "requested_end_ms" to JsonPrimitive(end + 120_000))
            .copy(windowStart = actualStart + 120_000, windowEnd = actualEnd + 120_000, measuredAt = measured + 120_000)
        assertNull(valid(future))
        assertNull(valid(value.copy(value = value.value + " ".repeat(1000))))
    }

    @Test fun historyIsReadableWithoutReinterpretingUnknownsOrCategories() {
        val value = reading()
        val description = assertNotNull(SignalWatchHistory.describe(value))
        assertContains(description, "2 valid minutes · 1 invalid · 12 missing")
        assertContains(description, "${signalDateTime(start + 120_000)}: 0 steps; movement count 0; orientation code 0")
        assertContains(description, "light unknown; heart rate unknown")
        assertContains(description, "light very light; heart rate 72 bpm")
        assertContains(description, "invalid record; readings unknown")
        assertContains(description, "Missing records do not establish inactivity")
        val stored = SignalLearning.normalize(SignalRecord("capture", "thread", received, "Capture", provider = "local", model = "", observations = listOf(value)))
        assertNull(stored.observations.single().number)
        assertEquals(value.value, stored.observations.single().value)
    }

    @Test fun motionSummaryExplainsVarianceAndSampleExclusions() {
        val value = SignalObservation("watch.motion", "watch", """{"samples":45,"variance_mg2":250000,"received_samples":50,"vibration_excluded":3,"timestamp_rejected":1,"capacity_excluded":1,"first_sample_ms":1789000010000,"last_sample_ms":1789000014900,"requested_hz":10,"requested_duration_ms":5000,"timing":"sdk_epoch_ms"}""", "mg", received)
        val description = assertNotNull(signalWatchMotionDescription(value))
        assertContains(description, "45 accepted samples of 50 received")
        assertContains(description, "250000.0 mg²")
        assertContains(description, "3 during vibration, 1 with invalid timestamps, 1 over the sample limit")
        assertContains(description, "4900 ms")
        assertContains(description, "10 samples/second for 5000 ms")
        assertNull(signalWatchMotionDescription(value.copy(value = "{\"samples\":50}")))
        assertNull(signalWatchMotionDescription(value.copy(key = SignalWatchHistory.KEY)))
    }

    @Test fun historicalLightCanBeFoundOnlyWhileItsBundleIsEnabled() {
        val record = SignalRecord("capture", "thread", received, "Capture", provider = "local", model = "", state = "ready",
            observations = listOf(reading()), sourceKeys = keys, kind = "capture")
        assertEquals(listOf("capture"), SignalHistory.retrieve(listOf(record), "light", keys, now = received).map { it.id })
        assertTrue(SignalHistory.retrieve(listOf(record), "light", setOf("sensor.5"), now = received).isEmpty())
    }

    @Test fun minuteCoverageStaysHistoricalAndPartialAcrossCaptureSummaries() {
        val complete = reading("[${List(15) { "[0,0,0,null,null]" }.joinToString()}]", offset = 0).copy(collectedAt = received)
        val partial = reading().copy(collectedAt = received)
        val unavailable = reading("[null,null]").copy(collectedAt = received)
        val oldWatchMissing = SignalObservation(SignalWatchHistory.KEY, "watch", collectedAt = received, status = "unavailable")
        fun summary(value: SignalObservation) = signalObservationCoverage(SignalRecord("capture", "thread", received, "Capture",
            provider = "local", model = "", observations = listOf(value)))
        assertEquals("Readings: 1 recorded for a period", summary(complete))
        assertEquals("Readings: 1 partial", summary(partial))
        assertEquals("Readings: 1 unavailable", summary(unavailable))
        assertEquals("Readings: 1 unavailable", summary(oldWatchMissing))
        assertContains(SignalCapture.summary(listOf(complete)), "1 available, 0 unavailable or partial")
        assertContains(SignalCapture.summary(listOf(partial)), "0 available, 1 unavailable or partial")
        assertContains(SignalCapture.summary(listOf(oldWatchMissing)), "0 available, 1 unavailable or partial")
    }

    @Test fun zeroSamplesCannotDescribeZeroVarianceAsAMeasurement() {
        val empty = SignalObservation("watch.motion", "watch", """{"samples":0,"variance_mg2":0,"received_samples":3,"vibration_excluded":3,"timestamp_rejected":0,"capacity_excluded":0,"first_sample_ms":0,"last_sample_ms":0,"requested_hz":10,"requested_duration_ms":5000,"timing":"sdk_epoch_ms"}""", "mg", received, status = "unavailable")
        val description = assertNotNull(signalWatchMotionDescription(empty))
        assertContains(description, "0 accepted samples of 3 received")
        assertContains(description, "No usable acceleration measurement")
        assertContains(description, "3 during vibration")
        assertFalse(description.contains("Movement variance:"))
        assertFalse(description.contains("timestamps span"))
    }
}
