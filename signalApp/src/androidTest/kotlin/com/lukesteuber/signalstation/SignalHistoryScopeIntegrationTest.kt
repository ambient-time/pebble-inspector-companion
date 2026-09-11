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

/** Complete encrypted queries, frozen request revisions and a real reviewed-provider path. */
class SignalHistoryScopeIntegrationTest {
    @Test fun completeQueryScopedReviewAndChangedEvidenceGuard() = runBlocking {
        val name = "scope-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            respond("""{"output":[{"content":[{"type":"output_text","text":"Scoped fixture answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val station = AndroidSignalStation(context, noWatch, client, name)
        val now = System.currentTimeMillis()
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = setOf("wifi")))
            store.put("openai", "fixture-only-key")
            repeat(125) { index ->
                store.save(SignalRecord("capture-$index", "old-thread", now - index, "private question", "private answer", provider = "local", model = "", state = "ready", kind = "capture",
                    sourceKeys = setOf("wifi", "location"), observations = listOf(
                        SignalObservation("wifi", "android", "reading-$index", collectedAt = now),
                        SignalObservation("location", "android", "secret-coordinate", collectedAt = now))))
            }
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady && !station.state.value.busy }
            val query = SignalHistoryQuery(kind = "captures", sourceKeys = setOf("wifi"))
            withContext(Dispatchers.Main) { station.queryHistory(query) }
            until { !station.state.value.scopedHistory.loading && station.state.value.scopedHistory.updatedAt > 0 }
            assertEquals(125, station.state.value.scopedHistory.matchingCount)
            assertEquals(100, station.state.value.scopedHistory.records.size)
            assertFalse(station.state.value.scopedHistory.records.toString().contains("secret-coordinate"))
            withContext(Dispatchers.Main) { station.loadMoreScopedHistory() }
            until { !station.state.value.scopedHistory.loading }
            assertEquals(125, station.state.value.scopedHistory.records.size)
            // New arrivals do not silently expand the displayed selection.
            store.save(store.record("capture-0")!!.copy(id = "new-arrival"))
            withContext(Dispatchers.Main) { station.openHistoryQuestion() }
            until { !station.state.value.busy }
            assertEquals(125, station.state.value.evidenceSelection?.matchedCount)
            withContext(Dispatchers.Main) { station.ask("Explain these results", true) }
            until { !station.state.value.busy }
            val review = assertNotNull(station.state.value.questionReview, station.state.value.status)
            assertEquals(30, review.recordCount); assertEquals(95, review.omittedRecords)
            assertEquals(0, review.priorTurnCount); assertEquals(0, review.memoryCount)
            assertFalse(review.messages.toString().contains("secret-coordinate")); assertFalse(review.messages.toString().contains("private answer"))
            assertFalse(review.messages.toString().contains("new-arrival")); assertTrue(requests.isEmpty())
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size, station.state.value.status)
            val saved = station.state.value.records.first { it.provider == "openai" && it.state == "ready" }
            assertNotNull(saved.evidenceScope)
            val thread = saved.threadId
            withContext(Dispatchers.Main) { station.newThread(); station.resumeThread(thread) }
            until { station.state.value.threadId == thread && station.state.value.evidenceSelection != null }
            assertEquals(30, station.state.value.evidenceSelection?.matchedCount)
            // Reopened conversation preserves the evidence actually supplied in its answer.
            withContext(Dispatchers.Main) { station.ask("What changed?", false) }
            until { !station.state.value.busy }
            assertEquals(0, station.state.value.questionReview?.omittedRecords)
            store.save(store.record("capture-0")!!.copy(answer = "changed after review"))
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size)
            assertNull(station.state.value.questionReview)
            assertContains(station.state.value.status, "Context changed")
            store.save(SignalRecord("text-conversation", "other-thread", now + 1, "General question", "Plain answer", provider = "openai", model = "gpt-4.1-mini", state = "ready"))
            store.save(SignalRecord("failed-conversation", "failed-thread", now + 2, "Failed question", provider = "openai", model = "gpt-4.1-mini", state = "error"))
            withContext(Dispatchers.Main) { station.queryHistory(SignalHistoryQuery()) }
            until { !station.state.value.scopedHistory.loading && station.state.value.scopedHistory.updatedAt > 0 }
            assertTrue("failed-conversation" in station.state.value.scopedHistory.recordIds)
            assertTrue("capture-124" in station.state.value.scopedHistory.recordIds)
            withContext(Dispatchers.Main) { station.openHistoryQuestion(setOf("text-conversation")) }
            until { !station.state.value.busy }
            assertEquals(setOf("text-conversation"), station.state.value.evidenceSelection?.recordIds)
            store.save(store.record("capture-44")!!.copy(answer = "unrelated record changed"))
            withContext(Dispatchers.Main) { station.ask("Explain this selected conversation", false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.recordCount, station.state.value.status)
            assertContains(station.state.value.questionReview!!.messages.toString(), "Plain answer")
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(2, requests.size, station.state.value.status)
            withContext(Dispatchers.Main) { station.clearEvidenceSelection() }
            assertNull(station.state.value.evidenceSelection)
            assertTrue(station.state.value.attachedRecords.isEmpty())
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(learningEnabled = true)) }
            until { !station.state.value.busy }
            val memory = SignalMemory("supporting-note", "note-fixture", "note", "A saved fact", "note", createdAt = now, evaluatedAt = now)
            store.saveMemory(memory)
            store.save(SignalRecord("memory-answer", "memory-thread", now + 3, "Memory-derived report", "A derived fact", provider = "openai", model = "gpt-4.1-mini", state = "ready", memoryReferences = mapOf(memory.id to memory.revision)))
            withContext(Dispatchers.Main) { station.queryHistory(SignalHistoryQuery()) }
            until { !station.state.value.scopedHistory.loading && station.state.value.scopedHistory.updatedAt > 0 }
            withContext(Dispatchers.Main) { station.openHistoryQuestion(setOf("memory-answer", "text-conversation")) }
            until { !station.state.value.busy }
            // Removal and Review in the same main-thread turn must never read the old scope.
            withContext(Dispatchers.Main) { station.removeAttachment("text-conversation"); station.ask("Explain the remaining evidence", false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.recordCount, station.state.value.status)
            assertFalse(station.state.value.questionReview!!.messages.toString().contains("Plain answer"))
            store.saveMemory(memory.copy(revision = 2, text = "Corrected saved fact"))
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(2, requests.size, "Correcting a supporting memory must invalidate the frozen review")
            assertNull(station.state.value.questionReview)
        } finally {
            withContext(Dispatchers.Main) { station.close() }; store.close(); client.close()
            context.deleteDatabase("$name-history.db")
            context.getSharedPreferences("${name}_private", 0).edit().clear().commit()
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(predicate: () -> Boolean) = withTimeout(60_000) { while (!predicate()) delay(25) }
    private val noWatch = object : SignalWatchLink {
        override val watches = MutableStateFlow(emptyList<SignalWatch>())
        override val capabilities = SignalWatchCapabilities()
        override fun initialize(scope: CoroutineScope) {}
        override suspend fun isTrusted(session: SignalWatchSession) = false
        override suspend fun launch(watchId: String) { error("No watch should be launched") }
        override suspend fun install(watchId: String) { error("No watch should be installed") }
    }
}
