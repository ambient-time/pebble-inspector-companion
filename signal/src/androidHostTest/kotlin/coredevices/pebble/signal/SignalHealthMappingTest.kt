package coredevices.pebble.signal

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import kotlin.test.*

class SignalHealthMappingTest {
    private val start = Instant.parse("2026-09-01T00:00:00Z")
    private val end = Instant.parse("2026-09-02T00:00:00Z")
    @Test fun stepsPreserveStableIdIntervalAndSeparateOrigin() {
        val raw = StepsRecord(start, null, end, null, 1200, Metadata.manualEntryWithId("source-record"))
        val first = mapHealthRecord("healthconnect.steps", raw, "hc:opaque", end.plusSeconds(10).toEpochMilli())!!
        val again = mapHealthRecord("healthconnect.steps", raw, "hc:opaque", end.plusSeconds(100).toEpochMilli())!!
        assertEquals(first.id, again.id)
        assertEquals(end.toEpochMilli(), first.createdAt)
        assertEquals(1200.0, first.observations.single().number)
        assertEquals("interval:86400000", first.observations.single().period)
        assertTrue(first.answer.contains("overlapping origins must not be added"))
        assertTrue(SignalLearning.fresh(first.observations.single()))
        assertFalse(first.sourceKeys.contains("health.steps"))
    }
    @Test fun incompleteIntervalsCannotBeImportedAsFinishedReadings() {
        val raw = StepsRecord(start, null, end, null, 1200, Metadata.manualEntry())
        assertNull(mapHealthRecord("healthconnect.steps", raw, "hc:opaque", start.plusSeconds(1).toEpochMilli()))
    }
    @Test fun heartRateIsLabeledAsIntervalMeanInsteadOfCurrentPulse() {
        val raw = HeartRateRecord(start, null, end, null, listOf(HeartRateRecord.Sample(start.plusSeconds(1), 60), HeartRateRecord.Sample(start.plusSeconds(2), 100)), Metadata.manualEntry())
        val record = mapHealthRecord("healthconnect.heart_rate", raw, "hc:heart", end.toEpochMilli())!!
        val reading = record.observations.first()
        assertEquals(80.0, reading.number)
        assertEquals("bpm interval mean", reading.unit)
        assertNotEquals("current", reading.period)
        assertEquals(listOf(60.0, 100.0), record.observations.drop(1).map { it.number })
        assertEquals(listOf(start.plusSeconds(1).toEpochMilli(), start.plusSeconds(2).toEpochMilli()), record.observations.drop(1).map { it.measuredAt })
        assertTrue(record.observations.all(SignalLearning::fresh))
    }
    @Test fun sleepStagesKeepTheirIntervalsAndDoNotBecomeTotalSleepMinutes() {
        val raw = SleepSessionRecord(start, null, end, null, Metadata.manualEntry(), stages = listOf(
            SleepSessionRecord.Stage(start, start.plusSeconds(60), SleepSessionRecord.STAGE_TYPE_AWAKE),
            SleepSessionRecord.Stage(start.plusSeconds(60), start.plusSeconds(180), SleepSessionRecord.STAGE_TYPE_DEEP)))
        val mapped = mapHealthRecord("healthconnect.sleep", raw, "hc:sleep", end.toEpochMilli())!!
        assertEquals("sleep session minutes", mapped.observations.first().unit)
        assertEquals(listOf("awake", "deep"), mapped.observations.drop(1).map { it.fields["stage"] })
        assertTrue(mapped.observations.drop(1).all { it.number == null && it.metric == "sleep_stage" })
        assertEquals("2.0", mapped.observations.last().fields["durationMinutes"])
    }
    @Test fun instantaneousHrvPreservesMeasuredTimeAndUnit() {
        val raw = HeartRateVariabilityRmssdRecord(start, null, 40.0, Metadata.manualEntry())
        val reading = mapHealthRecord("healthconnect.hrv_rmssd", raw, "hc:hrv", end.toEpochMilli())!!.observations.single()
        assertEquals("instant", reading.period)
        assertEquals("ms", reading.unit)
        assertEquals(start.toEpochMilli(), reading.measuredAt)
        assertTrue(SignalLearning.fresh(reading))
    }
    @Test fun mapperRevisionRequiresSnapshotAndSeriesBoundsIncludeBothEnds() {
        assertNotEquals("healthconnect.sleep:7", healthMapperCheckpoint("healthconnect.sleep", 7))
        assertNotEquals(healthMapperCheckpoint("healthconnect.sleep", 7), healthMapperCheckpoint("healthconnect.sleep", 30))
        val retained = healthSeries((0..1000).toList())
        assertEquals(512, retained.size)
        assertEquals(0, retained.first())
        assertEquals(1000, retained.last())
        assertEquals(retained.size, retained.distinct().size)
    }
}
