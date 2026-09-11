package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** Real encrypted store and reviewed provider path; synthetic radio acquisition. */
class SignalLiveIntegrationTest {
    @Test fun liveStaysEphemeralUntilSaveAndReviewedSend() = runBlocking {
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        val name = "live-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            respond("""{"output":[{"content":[{"type":"output_text","text":"Fixture answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var acquisitions = 0
        val station = AndroidSignalStation(context, noWatch, client, name, liveAcquisition = { _, _ ->
            acquisitions++
            SignalAcquisition(emptyList(), listOf(SignalRadioCandidate("bluetooth", "hidden-address", "hidden-name", -57, System.currentTimeMillis(), "fresh")))
        })
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = setOf("bluetooth")))
            store.put("openai", "fixture-only-key")
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady && !station.state.value.historyLoading && !station.state.value.busy }
            until { !station.state.value.busy && !station.state.value.historyLoading }
            withContext(Dispatchers.Main) { station.startLiveSignals() }
            try { until { station.state.value.live.entries.isNotEmpty() } } catch(e: Exception) { throw AssertionError("Initial live start: ${station.state.value.status}; ${station.state.value.live}", e) }
            assertTrue(station.state.value.records.isEmpty()); assertTrue(requests.isEmpty())
            withContext(Dispatchers.Main) { station.saveLiveScene(true) }
            until { !station.state.value.busy }
            val record = station.state.value.attachedRecords.single()
            assertEquals(-57.0, record.observations.first { it.metric == "rssi" }.number)
            assertFalse(record.toString().contains("hidden-"))
            assertTrue(requests.isEmpty()); assertFalse(station.state.value.live.running)
            val count = acquisitions
            delay(5200); assertEquals(count, acquisitions)
            val draft = station.state.value.questionDraft
            val draftToken = station.state.value.questionDraftToken
            withContext(Dispatchers.Main) { station.startLiveSignals() }
            until { station.state.value.live.entries.isNotEmpty() && acquisitions > count }
            withContext(Dispatchers.Main) { station.saveLiveScene(false) }
            until { !station.state.value.busy }
            val savedOnly = station.state.value.records.first { it.kind == "capture" && it.id != record.id }
            assertNotNull(store.record(savedOnly.id), "Save-only must persist its new snapshot")
            assertEquals(record.id, station.state.value.attachedRecords.single().id, "Save-only must preserve existing evidence")
            assertEquals(draft, station.state.value.questionDraft)
            assertEquals(draftToken, station.state.value.questionDraftToken)
            assertTrue(requests.isEmpty(), "Saving must not send to a provider")
            withContext(Dispatchers.Main) { station.ask(station.state.value.questionDraft, false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.captureCount)
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size); assertContains(requests.single(), record.id); assertContains(requests.single(), "-57")
            assertFalse(requests.single().contains("hidden-")); assertFalse(requests.single().contains(savedOnly.id))
            assertEquals(record.observations, store.record(record.id)?.observations)
            until { !station.state.value.busy && !station.state.value.historyLoading }
            withContext(Dispatchers.Main) { station.startLiveSignals() }
            try { until { station.state.value.live.running } } catch(e: Exception) { throw AssertionError("Restart live: ${station.state.value.status}; ${station.state.value.live}", e) }
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(enabled = emptySet())) }
            until { !station.state.value.busy }
            assertFalse(station.state.value.live.running); assertTrue(station.state.value.live.entries.isEmpty())
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); activity.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(predicate: () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(25) }
    private val noWatch = object : SignalWatchLink {
        override val watches = MutableStateFlow(emptyList<SignalWatch>())
        override val capabilities = SignalWatchCapabilities()
        override fun initialize(scope: CoroutineScope) {}
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun launch(watchId: String) { error("No watch should be launched") }
        override suspend fun install(watchId: String) { error("No watch should be installed") }
    }
}
