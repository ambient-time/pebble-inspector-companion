package coredevices.pebble.signal

import kotlin.test.*

class SignalScheduleTest {
    @Test fun fixedIntervalIgnoresMotionAndRequestsWifiAgainOnlyWhenDue() {
        val schedule = SignalSchedule()
        assertTrue(schedule.decide(keys, "fixed", 1000, 5).activeWifi)
        schedule.noteTransition(2000)
        assertEquals(300_000L, schedule.delayMillis("fixed", 2001, 5))
        assertFalse(schedule.decide(keys, "fixed", 150_999, 5).activeWifi, "Two scans stay at least half an interval apart")
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
    @Test fun failedSampleIsReportedAndDoesNotEscape() = kotlinx.coroutines.test.runTest {
        var reported = 0
        SignalSchedule.guardSample(sample = { error("storage unavailable") }, failed = { reported++ })
        assertEquals(1, reported)
        SignalSchedule.guardSample(sample = {}, failed = { reported++ })
        assertEquals(1, reported, "A sample that succeeds reports nothing")
        SignalSchedule.guardSample(sample = { error("storage unavailable") }, failed = { error("status could not be saved either") })
    }
    @Test fun cancelledSampleStillCancels() = kotlinx.coroutines.test.runTest {
        var reported = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            SignalSchedule.guardSample(sample = { throw kotlinx.coroutines.CancellationException("stopped") }, failed = { reported++ })
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            SignalSchedule.guardSample(sample = { error("failed") }, failed = { throw kotlinx.coroutines.CancellationException("stopped") })
        }
        assertEquals(0, reported)
    }
    @Test fun wakeStepsAlwaysLandBeforeTheSampleEvenIfAndroidDeliversLate() {
        // Android may deliver an inexact allow-while-idle alarm up to 75% of its lead time late.
        for (interval in listOf(3 * 60_000L, 15 * 60_000L, 60 * 60_000L, 1440 * 60_000L)) {
            var remaining = interval; var alarms = 0
            while (true) {
                val step = SignalSchedule.wakeStep(remaining)
                if (step.holdAwake) { assertEquals(remaining, step.alarmIn); assertTrue(remaining <= SignalSchedule.HOLD_AWAKE_MILLIS); break }
                val worstLate = step.alarmIn + step.alarmIn * 3 / 4
                assertTrue(worstLate < remaining, "A late alarm still lands before the sample")
                remaining -= worstLate; alarms++
            }
            assertTrue(alarms <= 40, "A $interval ms wait uses $alarms alarms")
        }
        assertTrue(SignalSchedule.wakeStep(60_000).holdAwake, "A one-minute interval stays awake")
        assertTrue(SignalSchedule.wakeStep(SignalSchedule.HOLD_AWAKE_MILLIS).holdAwake)
        assertFalse(SignalSchedule.wakeStep(SignalSchedule.HOLD_AWAKE_MILLIS + 1).holdAwake)
    }
    @Test fun alarmsPerHourStayWithinAndroidsQuota() {
        // 72 allow-while-idle alarms an hour for apps targeting Android 12 and later; keep well under it.
        for (minutes in 3..60) {
            var remaining = minutes * 60_000L; var alarms = 0
            while (!SignalSchedule.wakeStep(remaining).holdAwake) { remaining -= SignalSchedule.wakeStep(remaining).alarmIn; alarms++ }
            assertTrue(alarms * (60.0 / minutes) <= 36, "$minutes-minute interval: ${alarms * 60 / minutes} alarms an hour")
        }
    }
    @Test fun fixedSlotsKeepToTheGridAndCountWhatWasSkipped() {
        val minute = 60_000L
        assertEquals(SignalSlot(1, 1000 + minute, 0), SignalSchedule.nextSlot(1000, minute, 1000 + 5_000, 0), "A quick sample waits for the next slot")
        assertEquals(SignalSlot(1, 1000 + minute, 0), SignalSchedule.nextSlot(1000, minute, 1000 + 70_000, 0), "A slot that just came due is taken now, not skipped")
        assertEquals(SignalSlot(4, 1000 + 4 * minute, 3), SignalSchedule.nextSlot(1000, minute, 1000 + 4 * minute + 30_000, 0), "Three whole slots passed while asleep")
        assertEquals(SignalSlot(5, 1000 + 5 * minute, 0), SignalSchedule.nextSlot(1000, minute, 1000 + 4 * minute + 30_000, 4), "No drift after a slow sample")
        assertEquals(SignalSlot(1, 1000 + minute, 0), SignalSchedule.nextSlot(1000, minute, 500, 0), "A clock before the anchor never goes backward")
    }
    @Test fun fixedModeScansEachSlotButKeepsWeatherLimit() {
        val schedule = SignalSchedule()
        schedule.decide(keys, "fixed", 60_000 + 1_500, 1) // late sample
        val next = schedule.decide(keys, "fixed", 120_000 + 100, 1) // on time, 58.6 s later
        assertTrue("bluetooth" in next.enabled && "location" in next.enabled && next.activeWifi, "An on-time slot after a late one still scans")
        assertTrue("weather.current" in next.deferred)
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
