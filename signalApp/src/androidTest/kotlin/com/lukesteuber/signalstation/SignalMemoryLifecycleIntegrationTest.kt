package com.lukesteuber.signalstation

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** Uses the real station/store on an emulator with synthetic readings only. */
class SignalMemoryLifecycleIntegrationTest {
    private suspend fun until(test: () -> Boolean) = withTimeout(35_000) { while (!test()) delay(100) }
    @Test fun correctionStaysAcceptedAndDeletionCanKeepOnlyExplicitPersonalNote() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val station = (context.applicationContext as SignalApplication).station
        val store = SignalStore(context)
        val key = "device.battery"
        val prefix = "synthetic-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val ids = (0..9).map { "$prefix-$it" }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                until { station.state.value.historyReady && !station.state.value.busy }
                for ((n, id) in ids.withIndex()) {
                    val at = now - n / 2 * 86_400_000L - n % 2 * 7_200_000L
                    store.save(SignalRecord(id, prefix, at, "Synthetic test reading", provider = "local", model = "", kind = "capture", state = "ready", sourceKeys = setOf(key),
                        observations = listOf(SignalObservation(key, "phone", "80", "%", at, at, "fresh"))))
                }
                scenario.onActivity { station.updateSettings(station.state.value.settings.copy(onboardingComplete = true, enabled = setOf(key), learningEnabled = true)) }
                until { station.state.value.memories.any { it.kind == "baseline" && it.state == "proposed" } }
                val memory = station.state.value.memories.first { it.kind == "baseline" && it.state == "proposed" }
                scenario.onActivity { station.reviewMemory(memory.id, "correct", "I usually keep this phone charged.") }
                until { station.state.value.memories.any { it.id == memory.id && it.state == "confirmed" } }
                val corrected = station.state.value.memories.first { it.id == memory.id }
                assertEquals("I usually keep this phone charged.", corrected.text)
                scenario.onActivity { station.enableLearning(true) }
                until { !station.state.value.busy }
                delay(1000)
                assertFalse(station.state.value.memories.first { it.id == memory.id }.needsReview)
                scenario.onActivity { station.suggestMemory("phone charged") }
                assertTrue(station.state.value.memorySuggestions.any { it.id == memory.id })
                val report = SignalRecord("$prefix-report", prefix, now, "Synthetic report", provider = "local", model = "", state = "ready", memoryReferences = mapOf(memory.id to corrected.revision))
                store.save(report)
                scenario.onActivity { station.previewDeleteRecords(setOf(ids.first())) }
                until { station.state.value.deletionPreview != null }
                assertTrue(station.state.value.deletionPreview!!.memories.any { it.id == memory.id })
                scenario.onActivity { station.applyDeletion(setOf(memory.id)) }
                until { station.state.value.deletionPreview == null && station.state.value.memories.none { it.id == memory.id } }
                val note = station.state.value.memories.single { it.text == corrected.text && it.state == "note" }
                assertTrue(note.sourceKeys.isEmpty()); assertTrue(note.evidence.isEmpty())
                assertNull(store.record(ids.first())); assertNull(store.record(report.id))
                assertTrue(store.documents("correction").isEmpty())
                scenario.onActivity { station.previewForgetMemory(note.id) }
                until { station.state.value.deletionPreview != null }
                scenario.onActivity { station.applyDeletion(emptySet()) }
                until { station.state.value.memories.none { it.id == note.id } }
            }
        } finally { store.delete(store.deletionClosure(ids.toSet())); store.close() }
    }
}
