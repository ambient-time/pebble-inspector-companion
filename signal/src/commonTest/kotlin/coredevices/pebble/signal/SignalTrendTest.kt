package coredevices.pebble.signal

import kotlin.test.*

class SignalTrendTest {
    private fun row(value: Double, at: Long, key: String = "device.battery") = SignalObservation(
        key, "phone", value.toString(), "%", at, at, "fresh", number = value)
    private fun record(id: String, at: Long, vararg rows: SignalObservation) = SignalRecord(id, "thread", at, "Capture",
        provider = "local", model = "", state = "ready", observations = rows.toList(), sourceKeys = rows.map { it.key }.toSet(), kind = "capture")
    private fun trend(anchor: SignalRecord, vararg records: SignalRecord, limit: Int = 60) =
        SignalTrend.series(anchor, records.toList(), (records.toList() + anchor).flatMap { it.sourceKeys }.toSet(), limit)

    @Test fun typedAndUnambiguousLegacyValuesProduceExactLocalStatisticsAndEvidence() {
        val a = record("a", 100_000, row(80.0, 100_000).copy(number = null))
        val b = record("b", 200_000, row(60.0, 200_000))
        val c = record("c", 300_000, row(70.0, 300_000).copy(value = "battery summary"))
        val series = trend(c, a, b).single()
        assertEquals(listOf("a", "b", "c"), series.points.map { it.recordId })
        assertTrue(series.points.all { it.observationId.isNotBlank() })
        assertEquals(70.0, series.median)
        assertEquals(60.0, series.minimum)
        assertEquals(80.0, series.maximum)
        assertEquals(-10.0, series.difference)
        assertEquals(3, series.independentSessions)
    }

    @Test fun repeatedAndConflictingMeasurementsDoNotIncreaseEvidence() {
        val first = record("a", 100_000, row(80.0, 100_000))
        val duplicate = record("copy", 110_000, row(80.0, 100_000).copy(collectedAt = 110_000))
        val conflict = record("conflict", 200_000, row(70.0, 200_000), row(71.0, 200_000))
        val anchor = record("latest", 300_000, row(60.0, 300_000))
        val series = trend(anchor, first, duplicate, conflict).single()
        assertEquals(listOf(80.0, 60.0), series.points.map { it.value })
        assertEquals(1, series.duplicateCopies)
        assertEquals(2, series.conflictingRows)
    }

    @Test fun cachedStaleMissingAndNonfiniteValuesRemainExcluded() {
        val anchor = record("latest", 600_000, row(60.0, 600_000))
        val invalid = listOf(
            row(80.0, 100_000).copy(status = "cached"),
            row(70.0, 200_000).copy(measuredAt = 1),
            row(50.0, 300_000).copy(measuredAt = null),
            row(Double.POSITIVE_INFINITY, 400_000),
            row(30.0, 500_000).copy(number = null, value = "axis0 mean=30"),
        ).mapIndexed { i, row -> record("bad$i", row.collectedAt, row) }
        val series = trend(anchor, *invalid.toTypedArray()).single()
        assertEquals(listOf(60.0), series.points.map { it.value })
        assertEquals(5, series.unusableRows)
        assertNull(series.difference)
    }

    @Test fun axesUnitsDevicesAndOriginsCannotMix() {
        val reference = row(9.0, 300_000, "sensor.1").copy(metric = "axis_0.mean", unit = "m/s²", identity = "accelerometer")
        val anchor = record("latest", 300_000, reference).copy(watchId = "unused-selected-watch")
        val candidates = listOf(
            reference.copy(metric = "axis_1.mean"), reference.copy(unit = "g"),
            reference.copy(source = "other_origin"), reference.copy(identity = "other_sensor"),
            reference.copy(fields = mapOf("deviceId" to "other-device")),
        ).mapIndexed { index, row -> record("other$index", 100_000, row.copy(collectedAt = 100_000, measuredAt = 100_000)) }
        assertEquals(1, trend(anchor, *candidates.toTypedArray()).single().points.size)
        val samePhone = record("same-phone", 200_000, reference.copy(collectedAt = 200_000, measuredAt = 200_000))
        assertEquals(2, trend(anchor, samePhone).single().points.size)
    }

    @Test fun disabledOrDerivedRecordsNeverContributeAndDeletionRecomputesTheView() {
        val first = record("a", 100_000, row(80.0, 100_000))
        val anchor = record("b", 200_000, row(70.0, 200_000))
        assertTrue(SignalTrend.series(anchor, listOf(first), emptySet()).isEmpty())
        val mixed = first.copy(id = "mixed", sourceKeys = setOf("device.battery", "location"))
        val derived = first.copy(id = "derived", references = listOf(first.id), kind = "changes")
        assertEquals(1, SignalTrend.series(anchor, listOf(mixed, derived), setOf("device.battery")).single().points.size)
        assertEquals(2, trend(anchor, first).single().points.size)
        assertEquals(1, trend(anchor).single().points.size)
    }

    @Test fun periodWindowsMustMatchAndOverlappingHealthIntervalsAreNotIndependent() {
        fun health(id: String, end: Long, start: Long, value: Double, origin: String = "health_connect:app", device: String = "Watch") =
            record(id, end, row(value, end, "healthconnect.steps").copy(source = origin, status = "recorded", period = "interval",
                unit = "steps", windowStart = start, windowEnd = end, fields = mapOf("deviceModel" to device))).copy(kind = "health_import")
        val anchor = health("latest", 300_000, 200_000, 30.0)
        val first = health("first", 100_000, 0, 10.0)
        val overlap = health("overlap", 250_000, 150_000, 25.0)
        val differentWindow = health("longer", 180_000, 0, 40.0)
        val otherOrigin = health("other-origin", 150_000, 50_000, 200.0, origin = "health_connect:other")
        val otherDevice = health("other-device", 190_000, 90_000, 200.0, device = "Other watch")
        val series = trend(anchor, first, overlap, differentWindow, otherOrigin, otherDevice).single()
        assertEquals(listOf(10.0, 30.0), series.points.map { it.value })
        assertEquals(1, series.overlappingRows)
        assertEquals(1, series.incompatibleWindows)
        assertEquals(20.0, series.median)
    }

    @Test fun limitAndCaptureOmissionsAreReportedWithoutInflatingSessionCount() {
        val records = (1..8).map { i -> record("$i", i * 100_000L, row(i.toDouble(), i * 100_000L))
            .copy(sessionId = "one-session", coverage = listOf(SignalSourceCoverage("device.battery", 3, 1, 2))) }
        val series = trend(records.last(), *records.dropLast(1).toTypedArray(), limit = 3).single()
        assertEquals(listOf(6.0, 7.0, 8.0), series.points.map { it.value })
        assertEquals(5, series.olderPointsOmitted)
        assertEquals(16, series.sourceRowsOmitted)
        assertEquals(1, series.independentSessions)
    }

    @Test fun unsafeRadioIdentitiesRebootCountersAndAnglesHaveNoScalarTrend() {
        for (row in listOf(row(-80.0, 100_000, "wifi"), row(-70.0, 100_000, "cellular"),
            row(50.0, 100_000, "sensor.19").copy(period = "since_reboot"),
            row(359.0, 100_000, "sensor.3").copy(identity = "android.sensor.orientation"))) {
            assertTrue(trend(record("a", 100_000, row)).isEmpty())
        }
    }

    @Test fun finiteExtremesKeepMedianFiniteAndDoNotInventARepresentableDifference() {
        val first = record("a", 100_000, row(-Double.MAX_VALUE, 100_000))
        val anchor = record("b", 200_000, row(Double.MAX_VALUE, 200_000))
        val series = trend(anchor, first).single()
        assertEquals(0.0, series.median)
        assertNull(series.difference)
    }

    @Test fun stepIntervalsRequireOneKnownBootAndMatchingDuration() {
        fun interval(id: String, end: Long, boot: String?) = record(id, end,
            row(10.0, end, "sensor.19").copy(metric = "steps_delta", unit = "steps", period = "endpoint_interval",
                windowStart = end - 100_000, windowEnd = end,
                fields = boot?.let { mapOf("bootScope" to it) }.orEmpty()))
        val anchor = interval("latest", 400_000, "boot-a")
        val series = trend(anchor, interval("same", 100_000, "boot-a"), interval("different", 200_000, "boot-b"),
            interval("unknown", 300_000, null)).single()
        assertEquals(listOf("same", "latest"), series.points.map { it.recordId })
        assertTrue(trend(interval("unknown", 300_000, null)).isEmpty())
    }

    @Test fun healthDeviceMetadataAndRecordingMethodMustMatchIncludingUnknownValues() {
        fun health(id: String, at: Long, fields: Map<String, String>) = record(id, at,
            row(60.0, at, "healthconnect.heart_rate").copy(source = "health_connect:app", status = "recorded", period = "instant",
                unit = "bpm", windowStart = at, windowEnd = at, fields = fields)).copy(kind = "health_import")
        val metadata = mapOf("deviceManufacturer" to "Maker", "deviceModel" to "Watch", "deviceType" to "6", "recordingMethod" to "2")
        val anchor = health("latest", 600_000, metadata)
        val candidates = listOf(health("same", 100_000, metadata),
            health("manufacturer", 200_000, metadata + ("deviceManufacturer" to "Other")),
            health("type", 300_000, metadata + ("deviceType" to "1")),
            health("manual", 400_000, metadata + ("recordingMethod" to "3")), health("unknown", 500_000, emptyMap()))
        assertEquals(listOf("same", "latest"), trend(anchor, *candidates.toTypedArray()).single().points.map { it.recordId })
        val explicitUnknown = listOf("deviceManufacturer", "deviceModel", "deviceType", "recordingMethod").associateWith { "unknown" }
        assertEquals(2, trend(health("unknown-latest", 700_000, explicitUnknown), health("missing", 500_000, emptyMap())).single().points.size)
    }
}
