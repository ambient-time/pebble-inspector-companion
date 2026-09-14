package com.lukesteuber.signalstation

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Synthetic originals only, in a separate encrypted database on the emulator. */
class SignalCaptureRetentionIntegrationTest {
    @Test fun broadOriginalSurvivesEncryptedRoundTripPagingAndSmallerChatExcerpt() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "capture-budget-${System.nanoTime()}"
        val store = SignalStore(context, namespace)
        try {
            val rows = (0 until 80).flatMap { index -> SignalSensorFeatures.observations("sensor.$index", "android.sensor.accelerometer", "m/s²",
                listOf(SignalSensorSample(listOf(1.0, 2.0, 3.0), 10, 3), SignalSensorSample(listOf(2.0, 3.0, 4.0), 20, 3)), 21) }
            val budget = SignalCapture.retain(rows)
            val record = SignalLearning.normalize(SignalRecord("capture", "thread", 100, "Capture", provider = "local", model = "", state = "ready", kind = "capture",
                observations = budget.observations, coverage = budget.coverage, sourceKeys = rows.map { it.key }.toSet()))
            store.save(record)
            store.save(record.copy(id = "newer", createdAt = 200))
            val first = store.page(limit = 1)
            assertEquals("newer", first.records.single().id)
            assertTrue(first.hasMore)
            val original = store.page(before = first.cursor, limit = 1).records.single()
            assertEquals(record, original)
            assertEquals(1360, original.observations.size)
            val excerpt = SignalEvidenceBudget.excerpts(listOf(original)).single()
            assertTrue(excerpt.observations.size < original.observations.size)
            assertTrue(Json { encodeDefaults = true }.encodeToString(excerpt).encodeToByteArray().size <= 64 * 1024)
            assertEquals(1360, store.record("capture")!!.observations.size)
            assertTrue(excerpt.coverage.sumOf { it.omitted } > 0)
        } finally {
            store.close()
            context.deleteDatabase("$namespace-history.db")
            context.deleteSharedPreferences("${namespace}_private")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$namespace-station-v1") }
        }
    }
}
