package com.lukesteuber.signalstation

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Emulator-only integration: real app/service/storage, synthetic phone-battery source; no watch or provider. */
class SignalObservationIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private suspend fun until(test: () -> Boolean) = withTimeout(35_000) { while (!test()) delay(100) }
    @Test fun realForegroundSessionSavesLocalSampleAndStops() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val station = (context.applicationContext as SignalApplication).station
            until { station.state.value.initialized && station.state.value.historyReady && !station.state.value.busy }
            scenario.onActivity { station.updateSettings(station.state.value.settings.copy(onboardingComplete = true, enabled = setOf("device.battery"), learningEnabled = false)) }
            until { !station.state.value.busy && station.state.value.settings.enabled == setOf("device.battery") }
            scenario.onActivity { station.startObservation(15, setOf("device.battery")) }
            until { (station.state.value.observationSession?.captures ?: 0) >= 1 }
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 6110 })
            val session = station.state.value.observationSession!!
            assertEquals(15 * 60_000L, session.endsAt - session.startedAt)
            assertEquals(setOf("device.battery"), session.sourceKeys)
            val sample = station.state.value.records.first { it.sessionId == session.id }
            assertEquals("local", sample.provider); assertEquals("observation", sample.kind)
            assertTrue(sample.memoryReferences.isEmpty())
            assertTrue(sample.observations.all { it.key == "device.battery" })
            scenario.onActivity { station.stopObservation() }
            until { station.state.value.observationSession?.state == "stopped" }
            until { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 6110 } }
            delay(500)
            assertEquals(session.captures, station.state.value.observationSession?.captures)
            scenario.onActivity { station.previewDeleteRecords(setOf(sample.id)) }
            until { station.state.value.deletionPreview != null }
            scenario.onActivity { station.applyDeletion(emptySet()) }
            until { station.state.value.deletionPreview == null }
        }
    }
}
