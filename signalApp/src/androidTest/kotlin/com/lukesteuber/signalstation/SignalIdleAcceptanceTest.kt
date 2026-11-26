package com.lukesteuber.signalstation

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/**
 * Opt-in idle acceptance (about six minutes). A one-minute fixed recording keeps its schedule after
 * the screen goes off and Doze is forced. Emulators do not suspend the CPU the way phones do, so the
 * schedule alone passes even without the fix. The check that matters is the mechanism: in forced deep
 * idle, Android must list the recording's wake lock as held and not disabled, and its allow-while-idle
 * alarm as set. This is not a battery measurement.
 *
 * Run with: am instrument -w -e idleAcceptance true -e class com.lukesteuber.signalstation.SignalIdleAcceptanceTest …
 */
class SignalIdleAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    /** The "Wake Locks: size=N" block of dumpsys power, without the wake lock history. */
    private fun heldLocks(): List<String> = shell("dumpsys power").lines().dropWhile { !it.startsWith("Wake Locks: size=") }.drop(1).takeWhile { it.isNotBlank() }
    /** The "N pending alarms:" block of dumpsys alarm, without removal or delivery history. */
    private fun pendingAlarms(): List<String> = shell("dumpsys alarm").lines().dropWhile { !Regex("^\\s+\\d+ pending alarms:").containsMatchIn(it) }.drop(1).takeWhile { !Regex("^  \\S").containsMatchIn(it) }
    private suspend fun until(timeout: Long = 35_000, test: () -> Boolean) = withTimeout(timeout) { while (!test()) delay(250) }

    @Test fun oneMinuteRecordingKeepsItsScheduleInForcedIdle() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("idleAcceptance") == "true")
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val station = (context.applicationContext as SignalApplication).station
            until { station.state.value.initialized && station.state.value.historyReady && !station.state.value.busy }
            scenario.onActivity { station.updateSettings(station.state.value.settings.copy(onboardingComplete = true, enabled = setOf("device.battery"), learningEnabled = false,
                observationMode = "fixed", observationIntervalMinutes = 1, observationLocalAnalysis = false, observationModelAnalysis = false)) }
            until { !station.state.value.busy && station.state.value.settings.observationIntervalMinutes == 1 }
            val previous = station.state.value.observationSession?.id
            scenario.onActivity { station.startObservation(15, setOf("device.battery")) }
            until { station.state.value.observationSession?.let { it.id != previous && it.captures >= 1 && it.state == "running" } == true }
            val session = station.state.value.observationSession!!
            try {
                shell("dumpsys battery unplug")
                shell("input keyevent KEYCODE_SLEEP")
                delay(2_000)
                val idle = shell("dumpsys deviceidle force-idle")
                assertTrue(idle.contains("Now forced in to deep idle mode", ignoreCase = true) || idle.contains("idle", ignoreCase = true), idle)
                assertTrue(shell("dumpsys deviceidle get deep").trim().equals("IDLE", ignoreCase = true), "Doze must be on for this check")
                // Android's own state: the lock survives Doze for a foreground service, and the backup alarm is set.
                // Only the live sections count: both dumps also keep history from earlier runs.
                until(20_000) { heldLocks().any { it.contains("SignalStation:recording") } }
                val locks = heldLocks().filter { it.contains("SignalStation:recording") }
                assertTrue(locks.none { it.contains("DISABLED") }, "wake lock in deep idle: $locks")
                assertTrue(pendingAlarms().any { it.contains("${context.packageName}.OBSERVATION_WAKE") }, "allow-while-idle alarm pending")
                // Five more one-minute slots, plus a margin.
                until(6 * 60_000L) { (station.state.value.observationSession?.captures ?: 0) >= 6 }
                assertEquals("IDLE", shell("dumpsys deviceidle get deep").trim().uppercase(), "Doze stayed on through the check")
            } finally {
                shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent KEYCODE_WAKEUP")
                scenario.onActivity { station.stopObservation() }
            }
            val times = station.state.value.records.filter { it.sessionId == session.id && it.kind == "observation" }.map { it.createdAt }.sorted()
            assertTrue(times.size >= 6, "captures: ${times.size}")
            val gaps = times.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it in 50_000L..75_000L }, "gaps between samples in ms: $gaps")
            assertEquals(0, station.state.value.observationSession?.missedSlots ?: 0)
        }
    }
}
