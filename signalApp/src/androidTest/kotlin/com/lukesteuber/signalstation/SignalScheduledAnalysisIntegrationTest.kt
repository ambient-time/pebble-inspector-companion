package com.lukesteuber.signalstation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** Real station/storage/collector with a mocked provider and intercepted service permission handoff. */
class SignalScheduledAnalysisIntegrationTest {
    private suspend fun until(test: () -> Boolean) = withTimeout(35_000) { while (!test()) delay(50) }
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

    @Test fun scheduledModelUsesExactPairAndStopCancelsWithoutInheritingReadings() = runBlocking {
        assumeTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = HostContext(target)
            val name = "scheduled-model-${UUID.randomUUID()}"
            val store = SignalStore(context, name)
            val requests = mutableListOf<String>()
            val blocked = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val client = HttpClient(MockEngine { request ->
                requests += (request.body as TextContent).text
                if (requests.size == 2) { blocked.complete(Unit); never.await() }
                respond("""{"output":[{"content":[{"type":"output_text","text":"Fixture session comparison."}]}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val station = AndroidSignalStation(context, noWatch, client, name)
            context.station = station
            try {
                val keys = setOf("device.battery")
                store.settings(SignalSettings(onboardingComplete = true, enabled = keys, observationMode = "fixed", observationIntervalMinutes = 7,
                    observationLocalAnalysis = true, observationModelAnalysis = true, observationAnalysisEveryCaptures = 1))
                store.put("openai", "fixture-only-key")
                withContext(Dispatchers.Main) { station.initialize() }
                until { station.state.value.historyReady && !station.state.value.busy }
                withContext(Dispatchers.Main) { station.startObservation(0, keys) }
                val sessionId = assertNotNull(context.pendingId)
                withContext(Dispatchers.Main) {
                    station.activateObservation(sessionId)
                    station.collectObservation(sessionId)
                    station.analyzeObservation(sessionId)
                }
                assertTrue(requests.isEmpty(), "One capture cannot trigger a model comparison")
                withContext(Dispatchers.Main) { station.collectObservation(sessionId); station.analyzeObservation(sessionId) }
                val samples = station.state.value.records.filter { row -> row.sessionId == sessionId && row.kind == "observation" }
                val report = station.state.value.records.single { row -> row.sessionId == sessionId && row.kind == "analysis" }
                assertEquals("ready", report.state)
                assertEquals(samples.map { row -> row.id }.toSet(), report.references.toSet())
                assertEquals("openai", report.provider)
                assertTrue(report.observations.isEmpty() && report.memoryReferences.isEmpty())
                assertEquals(1, requests.size)
                report.references.forEach { reference -> assertContains(requests.single(), reference) }
                withContext(Dispatchers.Main) { station.collectObservation(sessionId) }
                val inFlight = launch(Dispatchers.Main) { station.analyzeObservation(sessionId) }
                withTimeout(15_000) { blocked.await() }
                withContext(Dispatchers.Main) { station.stopObservation() }
                inFlight.join()
                until { station.state.value.observationSession?.state == "stopped" && !station.state.value.busy &&
                    station.state.value.records.any { row -> row.sessionId == sessionId && row.kind == "analysis" && row.state == "cancelled" } }
                val cancelled = station.state.value.records.single { row -> row.sessionId == sessionId && row.kind == "analysis" && row.state == "cancelled" }
                assertTrue(cancelled.observations.isEmpty(), "Cancellation must never copy another watch operation's readings")
                assertEquals(2, cancelled.references.size)
                assertEquals(2, requests.size)
                assertFalse(station.state.value.settings.learningEnabled)
            } finally {
                withContext(Dispatchers.Main) { station.stopObservation(); station.close() }
                store.close(); client.close()
                target.deleteDatabase("$name-history.db")
                target.getSharedPreferences("${name}_private", 0).edit().clear().commit()
            }
        }
    }
}
