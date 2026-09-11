package coredevices.pebble.signal

import kotlin.test.*

class SignalStepAndCsvTest {
    private fun counter(value: Double, at: Long) = SignalObservation("sensor.19", "phone", value.toString(), "steps", at, at,
        "fresh", number = value, period = "since_reboot", metric = "cumulative_since_reboot")
    @Test fun stepsRequireTwoFreshEndpointsAndNeverCrossBootOrRollback() {
        val tracker = SignalStepCounter()
        assertNull(tracker.observe(counter(100.0, 10), 10, "1"))
        val delta = tracker.observe(counter(105.0, 20), 20, "1")!!
        assertEquals(5.0, delta.number); assertEquals(10L, delta.windowStart); assertEquals(20L, delta.windowEnd)
        assertNull(tracker.observe(counter(200.0, 30), 30, "2"))
        assertNull(tracker.observe(counter(201.0, 40), 1, "2"))
        assertNull(tracker.observe(counter(1.0, 50), 2, "2"))
        tracker.reset(); assertNull(tracker.observe(counter(2.0, 60), 3, "2"))
    }
    @Test fun staleCounterDoesNotReplaceFreshEndpoint() {
        val tracker = SignalStepCounter()
        tracker.observe(counter(100.0, 10), 10, "1")
        assertNull(tracker.observe(counter(200.0, 20).copy(status = "cached"), 20, "1"))
        assertEquals(5.0, tracker.observe(counter(105.0, 30), 30, "1")!!.number)
    }
    @Test fun csvEscapesFormulasAndQuotesWithoutCorruptingNegativeMeasurements() {
        val row = counter(-8.5, 10).copy(source = "=external()", unit = "a,\"b\"\nline")
        val record = SignalRecord("+formula", "thread", 10, "question", provider = "local", model = "", state = "ready", observations = listOf(row), sourceKeys = setOf(row.key))
        val csv = SignalCsv.rows(record, record.sourceKeys)
        assertContains(csv, "\"'+formula\""); assertContains(csv, "\"'=external()\"")
        assertContains(csv, "\"-8.5\""); assertContains(csv, "\"a,\"\"b\"\"\nline\"")
        assertEquals("", SignalCsv.rows(record, emptySet()))
        assertEquals("", SignalCsv.rows(record.copy(observations = listOf(row.copy(number = Double.NaN))), record.sourceKeys))
    }
    @Test fun unreliableSensorCannotEnterFreshLearningOrMotion() {
        val rows = SignalSensorFeatures.observations("sensor.1", "android.sensor.accelerometer", "m/s²", listOf(SignalSensorSample(listOf(10.0), 10, 0)), 11)
        assertTrue(rows.all { it.status == "unreliable" && !SignalLearning.fresh(it) })
        val budget = SignalBudget.retain(listOf(counter(1.0, 1).copy(key = "places.nearby", status = "lookup_not_requested")), 10)
        assertFalse(budget.coverage.single().attempted)
    }
}
