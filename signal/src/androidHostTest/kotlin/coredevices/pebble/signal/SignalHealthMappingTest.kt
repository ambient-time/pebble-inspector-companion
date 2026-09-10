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
        val reading = mapHealthRecord("healthconnect.heart_rate", raw, "hc:heart", end.toEpochMilli())!!.observations.single()
        assertEquals(80.0, reading.number)
        assertEquals("bpm interval mean", reading.unit)
        assertNotEquals("current", reading.period)
    }
}
