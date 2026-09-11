package com.lukesteuber.signalstation

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import kotlin.test.*

/** Real encrypted store, consent lifecycle and deletion graph; lookup transport is synthetic. */
class SignalLookupIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(predicate: () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(25) }
    private val noWatch = object : SignalWatchLink {
        override val watches = MutableStateFlow(emptyList<SignalWatch>())
        override val capabilities = SignalWatchCapabilities()
        override fun initialize(scope: CoroutineScope) {}
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun launch(watchId: String) { error("No watch operation") }
        override suspend fun install(watchId: String) { error("No watch installation") }
    }
    private val settings = SignalSettings(onboardingComplete = true, learningEnabled = true, enabled = setOf("location", "places.nearby"), lookups = SignalLookupSettings(nearbyPlaces = true))
    private fun capture(): SignalRecord {
        val now = System.currentTimeMillis()
        return SignalRecord("original", "thread", now, "Capture", provider = "local", model = "", state = "ready", kind = "capture", sourceKeys = setOf("location"),
            observations = listOf(SignalObservation("location", "phone", collectedAt = now, measuredAt = now, status = "fresh",
                fields = mapOf("latitude" to "45.5", "longitude" to "-122.6", "accuracyMeters" to "10"))))
    }
    private suspend fun fixture(client: HttpClient, block: suspend (AndroidSignalStation, SignalStore, String) -> Unit) {
        val name = "lookup-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val station = AndroidSignalStation(context, noWatch, storeNamespace = name, lookupClient = client)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                store.settings(settings); store.save(capture())
                withContext(Dispatchers.Main) { station.initialize() }
                until { station.state.value.historyReady && station.state.value.learningStatus.startsWith("Learning is on") }
                block(station, store, name)
            } finally {
                withContext(Dispatchers.Main) { station.close() }; store.close(); client.close()
                context.deleteDatabase("$name-history.db"); context.deleteSharedPreferences("${name}_private")
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$name-station-v1") }
            }
        }
    }
    private suspend fun review(station: AndroidSignalStation) {
        withContext(Dispatchers.Main) { station.prepareLookup("nearby", "original") }
        until { !station.state.value.busy }
        assertNotNull(station.state.value.lookupReview, station.state.value.status)
    }
    @Test fun reviewedPayloadIsExactAndCacheDeletionFollowsOriginal() = runBlocking {
        val requests = mutableListOf<String>()
        fixture(HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            respond("""{"elements":[{"type":"node","id":1,"lat":45.5,"lon":-122.6,"tags":{"name":"Fixture Cafe","amenity":"cafe"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })) { station, store, _ ->
            review(station); val payload = station.state.value.lookupReview!!.payload
            assertTrue(requests.isEmpty())
            withContext(Dispatchers.Main) { station.sendReviewedLookup() }
            until { !station.state.value.busy }
            assertEquals(listOf(payload), requests)
            assertEquals(1, store.documents("lookup_cache").size)
            review(station)
            withContext(Dispatchers.Main) { station.sendReviewedLookup() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size, "Identical reviewed request should reuse encrypted cache")
            val enriched = store.page().records.filter { it.kind == "enrichment" }
            assertEquals(2, enriched.size)
            assertTrue(enriched.all { "original" in it.references })
            val closure = store.deletionClosure(setOf("original"))
            assertTrue(enriched.all { it.id in closure })
            val firstLookup = enriched.single { row -> row.observations.none { it.fields["cache"] == "reused" } }
            val intermediate = store.deletionClosure(setOf(firstLookup.id))
            assertTrue(enriched.all { it.id in intermediate })
            assertFalse("original" in intermediate)
            store.delete(intermediate)
            assertTrue(store.documents("lookup_cache").isEmpty()); assertEquals(1, store.count())
            store.delete(store.deletionClosure(setOf("original")))
            assertEquals(0, store.count())
        }
    }
    @Test fun sourceDeletedAfterReviewPreventsAnyTransmission() = runBlocking {
        var calls = 0
        fixture(HttpClient(MockEngine { calls++; error("No request permitted") })) { station, store, _ ->
            review(station); store.delete(setOf("original"))
            withContext(Dispatchers.Main) { station.sendReviewedLookup() }
            until { !station.state.value.busy }
            assertEquals(0, calls); assertTrue(store.documents("lookup_cache").isEmpty())
        }
    }
    @Test fun disablingLookupCancelsTransportAndLatePersistence() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        fixture(HttpClient(MockEngine {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })) { station, store, _ ->
            review(station)
            withContext(Dispatchers.Main) { station.sendReviewedLookup() }
            withTimeout(10_000) { started.await() }
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(lookups = SignalLookupSettings())) }
            withTimeout(10_000) { cancelled.await() }
            until { !station.state.value.busy }
            assertNull(station.state.value.lookupReview)
            assertEquals(listOf("original"), store.page().records.map { it.id })
            assertTrue(store.documents("lookup_cache").isEmpty())
        }
    }
    @Test fun derivedIndexUpgradePreservesOriginalsQuestionsAndCorrectedMemory() = runBlocking {
        fixture(HttpClient(MockEngine { error("Migration remains local") })) { first, store, name ->
            val memory = SignalMemory("memory", "legacy-fingerprint", "baseline", "My corrected wording", "confirmed", createdAt = 1, evaluatedAt = 1,
                evidence = listOf(SignalEvidence("original", emptyList(), 1, "Original capture")))
            store.saveMemory(memory)
            val question = SavedQuestion("q", "Saved", "What changed here?", setOf("location"))
            store.saveQuestion(question)
            assertEquals("3", store.document("learning-frame-version"))
            val original = store.record("original")!!
            store.document("learning-frame-version", "checkpoint", "2")
            store.document("learning-cursor", "checkpoint", "0")
            store.document("obsolete-frame", "frame", Json.encodeToString(original))
            // Reopening uses the same application package, encrypted rows and keystore alias.
            withContext(Dispatchers.Main) { first.close() }
            val second = AndroidSignalStation(context, noWatch, storeNamespace = name)
            try {
                withContext(Dispatchers.Main) { second.initialize() }
                until { second.state.value.historyReady && second.state.value.learningStatus.startsWith("Learning is on") }
                assertEquals("3", store.document("learning-frame-version"))
                assertNull(store.document("obsolete-frame"))
                assertEquals(original, store.record("original"))
                assertEquals(memory, store.memory().single())
                assertEquals(question, store.savedQuestions().single())
            } finally { withContext(Dispatchers.Main) { second.close() } }
        }
    }
}
