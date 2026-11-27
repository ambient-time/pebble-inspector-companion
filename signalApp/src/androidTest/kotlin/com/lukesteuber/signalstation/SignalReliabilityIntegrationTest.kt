package com.lukesteuber.signalstation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** Deletion previews, local scheduled analysis, session counts and history paging on real storage. */
class SignalReliabilityIntegrationTest {
    private suspend fun until(test: () -> Boolean) = withTimeout(60_000) { while (!test()) delay(50) }
    private class HostContext(base: Context) : ContextWrapper(base), SignalObservationHost {
        override lateinit var station: AndroidSignalStation
        var pendingId: String? = null
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent) { pendingId = intent.getStringExtra("observation") }
        override fun stopService(name: Intent): Boolean = true
    }
    private val noWatch = object : SignalWatchLink {
        override val watches = MutableStateFlow<List<SignalWatch>>(emptyList())
        override val capabilities = SignalWatchCapabilities()
        override fun initialize(scope: CoroutineScope) = Unit
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun refresh(watchId: String) = Unit
        override suspend fun launch(watchId: String) = Unit
        override suspend fun install(watchId: String) = Unit
    }
    private fun emulator() = assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))

    private fun <T> withStation(
        settings: SignalSettings,
        localAnswer: (suspend (String, List<Pair<String, String>>) -> String)? = null,
        localReady: Set<String>? = null,
        seed: suspend (SignalStore) -> Unit = {},
        body: suspend (AndroidSignalStation, SignalStore, HostContext) -> T,
    ): T = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = HostContext(target)
            val name = "reliability-${UUID.randomUUID()}"
            val store = SignalStore(context, name)
            val station = AndroidSignalStation(context, noWatch, storeNamespace = name, localAnswer = localAnswer,
                localReadiness = localReady?.let { MutableStateFlow(it) })
            context.station = station
            try {
                store.settings(settings)
                seed(store)
                withContext(Dispatchers.Main) { station.initialize() }
                until { station.state.value.historyReady && !station.state.value.busy }
                body(station, store, context)
            } finally {
                withContext(Dispatchers.Main) { station.stopObservation(); station.close() }
                store.close()
                target.deleteDatabase("$name-history.db")
                target.getSharedPreferences("${name}_private", 0).edit().clear().commit()
            }
        }
    }

    @Test fun aDismissedDeletionPreviewLeavesTheRecordingRunningAndDeleteAllIncludesLaterCaptures() {
        emulator()
        val keys = setOf("device.battery")
        withStation(SignalSettings(onboardingComplete = true, enabled = keys, observationMode = "fixed", observationIntervalMinutes = 1)) { station, store, context ->
            withContext(Dispatchers.Main) { station.startObservation(0, keys) }
            val id = assertNotNull(context.pendingId)
            withContext(Dispatchers.Main) { station.activateObservation(id); station.collectObservation(id) }
            withContext(Dispatchers.Main) { station.previewDeleteRecords(null) }
            until { station.state.value.deletionPreview != null }
            assertTrue(station.state.value.deletionPreview!!.all)
            withContext(Dispatchers.Main) { station.dismissDeletion() }
            assertEquals("running", station.state.value.observationSession?.state, "Opening and dismissing a preview must not stop recording")
            withContext(Dispatchers.Main) { station.collectObservation(id) }
            val samples = station.state.value.records.count { it.sessionId == id && it.kind == "observation" }
            assertEquals(2, samples)
            assertEquals(2, store.sessions().single { it.id == id }.captures, "Session counts commit with each sample")

            // A capture saved while the delete-everything preview is open is deleted too.
            withContext(Dispatchers.Main) { station.previewDeleteRecords(null) }
            until { station.state.value.deletionPreview != null }
            withContext(Dispatchers.Main) { station.collectObservation(id) }
            assertEquals(3, store.sessions().single { it.id == id }.captures)
            withContext(Dispatchers.Main) { station.applyDeletion(emptySet()) }
            until { station.state.value.deletionPreview == null && station.state.value.status.startsWith("Deleted") }
            assertEquals(0L, store.count(), "Delete-everything must re-read history at confirmation")
        }
    }

    @Test fun scheduledAnalysisWithAnOnPhoneModelNeedsNoKeyAndFitsItsLimit() {
        emulator()
        val keys = setOf("device.battery")
        val sent = mutableListOf<List<Pair<String, String>>>()
        withStation(
            SignalSettings(onboardingComplete = true, enabled = keys, observationMode = "fixed", observationIntervalMinutes = 1,
                provider = "local-gemma", model = "local-file", observationModelAnalysis = true, observationAnalysisEveryCaptures = 1),
            localAnswer = { _, messages -> sent += messages; "Local fixture comparison." },
            localReady = setOf("local-gemma"),
        ) { station, store, context ->
            assertNull(store.get("local-gemma"))
            withContext(Dispatchers.Main) { station.startObservation(0, keys) }
            val id = assertNotNull(context.pendingId, station.state.value.status)
            withContext(Dispatchers.Main) {
                station.activateObservation(id)
                station.collectObservation(id); station.analyzeObservation(id)
                station.collectObservation(id); station.analyzeObservation(id)
            }
            val report = station.state.value.records.single { it.sessionId == id && it.kind == "analysis" }
            assertEquals("ready", report.state, station.state.value.status)
            assertEquals("local-gemma", report.provider)
            assertEquals(1, sent.size)
            // Same byte contract as the on-phone limit: content plus role and separators, at most 6,000.
            assertTrue(sent.single().sumOf { it.second.encodeToByteArray().size + it.first.length + 4 } <= 6000)
        }
    }

    @Test fun historyPagesAndSearchesTwoThousandDenseRecords() {
        emulator()
        val keys = setOf("wifi", "bluetooth.names")
        fun dense(index: Int) = SignalRecord("seed-$index", "observation:seed", 1_700_000_000_000 + index * 60_000L,
            if (index == 50) "Observation needle-zebra" else "Observation session sample", provider = "local", model = "", state = "ready",
            kind = "observation", sourceKeys = keys, sessionId = "seed",
            observations = (0 until 64).map { SignalObservation("wifi", "phone", "observation=$it; rssi=-${40 + it}", "", 0, 0, "fresh", id = "seed-$index:w$it") } +
                (0 until 64).map { SignalObservation("bluetooth.names", "phone", "observation=$it; name=Device $it", "", 0, 0, "fresh", id = "seed-$index:b$it") })
        withStation(SignalSettings(onboardingComplete = true, enabled = keys), seed = { store -> repeat(2000) { store.save(dense(it)) } }) { station, _, _ ->
            val started = System.nanoTime()
            withContext(Dispatchers.Main) { station.searchSavedHistory("") }
            until { !station.state.value.historyLoading && station.state.value.records.isNotEmpty() }
            Log.i("SignalReliability", "first history page in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(100, station.state.value.records.size)
            assertTrue(station.state.value.historyHasMore)
            val firstPage = station.state.value.records.map { it.id }
            withContext(Dispatchers.Main) { station.loadMoreHistory() }
            until { !station.state.value.historyLoading && station.state.value.records.size == 200 }
            assertEquals(firstPage, station.state.value.records.take(100).map { it.id }, "Older pages are added, not swapped in")

            // The match is old: the first bounded scan reaches the newest 1,000 rows only, then search continues.
            withContext(Dispatchers.Main) { station.searchSavedHistory("needle-zebra") }
            until { !station.state.value.historyLoading }
            assertTrue(station.state.value.records.isEmpty())
            assertTrue(station.state.value.historyHasMore)
            withContext(Dispatchers.Main) { station.loadMoreHistory() }
            until { !station.state.value.historyLoading && station.state.value.records.isNotEmpty() }
            assertEquals(listOf("seed-50"), station.state.value.records.map { it.id })
        }
    }
}
