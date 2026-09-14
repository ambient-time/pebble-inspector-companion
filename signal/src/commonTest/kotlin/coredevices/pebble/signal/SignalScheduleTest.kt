package coredevices.pebble.signal

import kotlin.test.*

class SignalScheduleTest {
    @Test fun fixedIntervalIgnoresMotionAndRequestsWifiAgainOnlyWhenDue() {
        val schedule = SignalSchedule()
        assertTrue(schedule.decide(keys, "fixed", 1000, 5).activeWifi)
        schedule.noteTransition(2000)
        assertEquals(300_000L, schedule.delayMillis("fixed", 2001, 5))
        assertFalse(schedule.decide(keys, "fixed", 300_999, 5).activeWifi)
        val due = schedule.decide(keys, "fixed", 301_000, 5)
        assertTrue(due.activeWifi)
        assertTrue("weather.current" in due.deferred, "Weather keeps its 30-minute refresh limit")
        assertEquals(900_000L, schedule.delayMillis("fixed", 2001, 15))
    }
    @Test fun customIntervalsStayBoundedWithoutChangingAdaptiveModes() {
        val schedule = SignalSchedule()
        assertEquals(7 * 60_000L, schedule.delayMillis("fixed", 1, 7))
        assertEquals(60_000L, schedule.delayMillis("fixed", 1, 0))
        assertEquals(1440 * 60_000L, schedule.delayMillis("fixed", 1, Int.MAX_VALUE))
        assertEquals(300_000L, schedule.delayMillis("standard", 1, 7))
    }
    @Test fun permissionDelayCannotExtendChosenDurationEvenIfWallClockMovesBackward() {
        assertEquals(600L, SignalSchedule.remaining(100, 1000, 400))
        assertEquals(0L, SignalSchedule.remaining(100, 1000, 1100))
        assertEquals(900L, SignalSchedule.remaining(100, 1000, 0))
        assertEquals(900L, SignalSchedule.remaining(100, 1000, 100))
    }
    private val keys = setOf("location", "wifi", "presence.wifi", "bluetooth", "weather.current", "cellular", "sensor.1")
    @Test fun passiveWifiRemainsAvailableWhenActiveScanIsCoolingDown() {
        val schedule = SignalSchedule()
        assertTrue(schedule.decide(keys, "standard", 1000).activeWifi)
        val again = schedule.decide(keys, "standard", 61_000)
        assertFalse(again.activeWifi)
        assertTrue("wifi" in again.enabled && "presence.wifi" in again.enabled)
        assertTrue(again.deferred.containsAll(setOf("location", "bluetooth", "weather.current", "cellular")))
        assertTrue(schedule.decide(keys, "standard", 1_801_000).activeWifi)
    }
    @Test fun transitionAllowsBoundedFollowupAndThenReturnsToSlowRate() {
        val schedule = SignalSchedule()
        schedule.decide(keys, "standard", 1000)
        schedule.noteTransition(61_000)
        assertEquals(60_000, schedule.delayMillis("standard", 61_001))
        assertTrue("location" in schedule.decide(keys, "standard", 61_001).enabled)
        assertTrue("location" in schedule.decide(keys, "standard", 61_500).deferred)
        assertEquals(300_000, schedule.delayMillis("standard", 241_001))
    }
    @Test fun batterySaverAndMissingMotionHaveLowRateFallback() {
        val schedule = SignalSchedule()
        assertEquals(900_000, schedule.delayMillis("battery_saver", 1000))
        schedule.noteTransition(2000)
        assertEquals(300_000, schedule.delayMillis("battery_saver", 2001))
        assertEquals(900_000, schedule.delayMillis("battery_saver", 182_001))
    }
    @Test fun resetAndElapsedClockRollbackNeverStrandSources() {
        val schedule = SignalSchedule()
        schedule.decide(keys, "standard", 100_000)
        assertTrue(schedule.decide(keys, "standard", 100).activeWifi)
        schedule.reset()
        assertEquals(keys, schedule.decide(keys, "standard", 101).enabled)
    }
    @Test fun decisionsNeverExpandSelectedSources() {
        val schedule = SignalSchedule()
        assertEquals(emptySet(), schedule.decide(emptySet(), "standard", 1).enabled)
        assertEquals(setOf("device.battery"), schedule.decide(setOf("device.battery"), "standard", 2).enabled)
    }
    @Test fun onlyMeasuredSelectedMotionCanShortenInterval() {
        val schedule = SignalSchedule()
        val row = SignalObservation("sensor.1", "phone", collectedAt = 10, status = "cached", number = 2.0, metric = "magnitude.stddev")
        schedule.noteMeasurements(listOf(row), 1000)
        assertEquals(300_000, schedule.delayMillis("standard", 1001))
        schedule.noteMeasurements(listOf(row.copy(status = "fresh")), 1002)
        assertEquals(60_000, schedule.delayMillis("standard", 1003))
    }
}
