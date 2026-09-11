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

/** Real encrypted Android store and station; only the provider transport is replaced. */
class SignalQuestionPipelineTest {
    @Test fun largeCaptureAnalyzeFollowUpRetryAndRemovalUseActualEvidence() = runBlocking {
        val name = "capture-pipeline-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val requests = mutableListOf<String>()
        var failNext = false
        val client = HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            if (failNext) { failNext = false; respond("{}", HttpStatusCode.ServiceUnavailable) }
            else respond("""{"output":[{"content":[{"type":"output_text","text":"Fixture answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val station = AndroidSignalStation(context, noWatch, client, name)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        var original = SignalRecord("large-capture", "capture-thread", 1789080000000, "Capture current context", provider = "local", model = "", kind = "capture", state = "ready",
            sourceKeys = setOf("device.battery", "device.charging"), observations = (0..199).map { index ->
                SignalObservation(if (index % 2 == 0) "device.battery" else "device.charging", "phone", "capture-sentinel-$index", "%", 1789080000000, 1789080000000,
                    id = "reading-$index", fields = mapOf("note" to "x".repeat(100)))
            })
        while (json.encodeToString(original).encodeToByteArray().size > 79 * 1024) original = original.copy(observations = original.observations.dropLast(1))
        assertTrue(json.encodeToString(original).encodeToByteArray().size > 64 * 1024)
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = original.sourceKeys))
            store.put("openai", "fixture-only-key"); store.save(original)
            val storedOriginal = store.record(original.id)
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            withContext(Dispatchers.Main) { station.analyzeRecord(original.id) }
            until { !station.state.value.busy }
            assertTrue(requests.isEmpty(), "Analyze must prepare a draft, never send")
            assertEquals(original.id, station.state.value.attachedRecords.single().id)
            assertTrue(station.state.value.questionDraft.isNotBlank())
            suspend fun review(question: String) {
                withContext(Dispatchers.Main) {
                    // History loading is separate from request busy state. Check the
                    // same readiness guard as Ask, then dispatch without a thread hop.
                    until { !station.state.value.busy && !station.state.value.historyLoading }
                    station.ask(question, false)
                }
                until { !station.state.value.busy }
                assertNotNull(station.state.value.questionReview, station.state.value.status)
            }
            suspend fun send() {
                withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
                until { !station.state.value.busy }
            }
            review(station.state.value.questionDraft)
            val prepared = station.state.value.questionReview!!
            assertEquals(1, prepared.captureCount)
            assertTrue(prepared.observationCount > 0)
            assertTrue(prepared.omittedObservations > 0)
            val evidence = prepared.messages.last().second
            assertContains(evidence, original.id); assertContains(evidence, "capture-sentinel-")
            assertContains(evidence, "device.battery"); assertContains(evidence, "device.charging")
            assertContains(evidence, "1789080000000"); assertContains(evidence, "%")
            failNext = true
            send()
            assertEquals(original.id, station.state.value.attachedRecords.single().id, "Failure must preserve the attachment")
            assertNotNull(station.state.value.questionReview, "Retry should retain the reviewed request")
            send()
            assertEquals(requests[0], requests[1], "Retry must use the same reviewed payload")
            review("What was the battery reading in that capture?")
            assertContains(station.state.value.questionReview!!.messages.last().second, "capture-sentinel-")
            send()
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.contains("capture-sentinel-") && it.contains(original.id) })
            assertTrue(storedOriginal == store.record(original.id), "The stored original capture must remain unchanged")
            val thread = station.state.value.threadId
            withContext(Dispatchers.Main) { station.removeAttachment(original.id) }
            assertEquals(thread, station.state.value.threadId)
            assertTrue(station.state.value.attachedRecords.isEmpty())
            review("A plain question")
            assertEquals(0, station.state.value.questionReview!!.captureCount)
            assertFalse(station.state.value.questionReview!!.messages.last().second.contains("capture-sentinel-"))
            val second = original.copy(id = "second-large-capture", sessionId = "second-session")
            store.save(second)
            withContext(Dispatchers.Main) { station.compareRecords(original.id, second.id) }
            until { !station.state.value.busy }
            assertEquals(3, requests.size, "Compare must remain local until Send")
            review(station.state.value.questionDraft)
            assertEquals(2, station.state.value.questionReview!!.captureCount)
            send()
            assertContains(requests.last(), original.id); assertContains(requests.last(), second.id)
            store.put("xai", "fixture-only-key")
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(provider = "xai", model = "test-model")) }
            until { !station.state.value.busy }
            assertEquals(2, station.state.value.attachedRecords.size)
            review("Compare the attached readings again")
            send()
            assertContains(requests.last(), second.id)
            assertEquals(5, requests.size)
            store.save(original.copy(id = "empty-capture", observations = emptyList()))
            withContext(Dispatchers.Main) { station.analyzeRecord("empty-capture") }
            until { !station.state.value.busy }
            assertContains(station.state.value.status, "no readings")
            assertNull(station.state.value.questionReview)
            assertEquals(5, requests.size)
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
    }

    @Test fun captureAndSurveyStayLocalUntilReviewedAndSourceChangesDoNotLoseAttachments() = runBlocking {
        val name = "capture-flow-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            respond("""{"output":[{"content":[{"type":"output_text","text":"Fixture answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val station = AndroidSignalStation(context, noWatch, client, name)
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = setOf("device.battery")))
            store.put("openai", "fixture-only-key")
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            withContext(Dispatchers.Main) { station.capture() }
            until { !station.state.value.busy }
            val capture = station.state.value.attachedRecords.single()
            assertEquals("capture", capture.kind)
            assertEquals("device.battery", capture.observations.single().key)
            assertTrue(requests.isEmpty())
            withContext(Dispatchers.Main) { station.ask("Explain this capture", false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.captureCount)
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(enabled = emptySet())) }
            until { !station.state.value.busy }
            assertEquals(capture.id, station.state.value.attachedRecords.single().id)
            assertNull(station.state.value.questionReview)
            withContext(Dispatchers.Main) { station.ask("Explain this capture", false) }
            until { !station.state.value.busy }
            assertNull(station.state.value.questionReview)
            assertTrue(requests.isEmpty())
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(enabled = setOf("device.battery"))) }
            until { !station.state.value.busy }
            withContext(Dispatchers.Main) { station.survey() }
            until { !station.state.value.busy }
            assertTrue(requests.isEmpty(), "Capture & analyze must wait for Review and Send")
            assertTrue(station.state.value.questionDraft.contains("Summarize"))
            val latest = station.state.value.attachedRecords.single()
            assertNotEquals(capture.id, latest.id)
            withContext(Dispatchers.Main) { station.ask(station.state.value.questionDraft, false) }
            until { !station.state.value.busy }
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size)
            assertContains(requests.single(), latest.id)
            assertContains(requests.single(), "device.battery")
            val thread = station.state.value.threadId
            withContext(Dispatchers.Main) { station.newThread(); station.resumeThread(thread) }
            until { station.state.value.threadId == thread }
            assertEquals(latest.id, station.state.value.attachedRecords.single().id)
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
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
    private fun row(id: String) = SignalRecord(id, "old", 1, "broad-private-sentinel", "broad-answer-sentinel", provider = "local", model = "", state = "ready",
        observations = listOf(SignalObservation("health.steps", "watch", "123", collectedAt = 1, date = "2026-09-07"),
            SignalObservation("health.heart_rate", "watch", "excluded-sentinel", collectedAt = 1, date = "2026-09-06")),
        sourceKeys = setOf("health.steps", "health.heart_rate"))

    @Test fun reviewedProjectionIsExactProviderInputAndOriginalsSurvive() = runBlocking {
        val name = "question-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requests += (request.body as TextContent).text
            respond("""{"output":[{"content":[{"type":"output_text","text":"A bounded answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val station = AndroidSignalStation(context, noWatch, client, name)
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = row("a").sourceKeys))
            store.put("openai", "test-only-key"); store.save(row("a"))
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            withContext(Dispatchers.Main) { station.ask("steps on 2026-09-07", true) }
            until { !station.state.value.busy && station.state.value.questionReview != null }
            val review = station.state.value.questionReview!!
            assertTrue(requests.isEmpty())
            val evidence = review.messages.last().second
            assertContains(evidence, "123")
            assertFalse(evidence.contains("excluded-sentinel"))
            assertFalse(evidence.contains("broad-private-sentinel"))
            assertFalse(evidence.contains("broad-answer-sentinel"))
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(1, requests.size)
            val body = kotlinx.serialization.json.Json.parseToJsonElement(requests.single()) as kotlinx.serialization.json.JsonObject
            val input = body["input"] as kotlinx.serialization.json.JsonArray
            val actual = input.map { item ->
                val obj = item as kotlinx.serialization.json.JsonObject
                (obj["role"] as kotlinx.serialization.json.JsonPrimitive).content to (obj["content"] as kotlinx.serialization.json.JsonPrimitive).content
            }
            assertEquals(review.messages, actual)
            assertEquals("broad-answer-sentinel", store.record("a")!!.answer)
            assertTrue(station.state.value.records.any { it.answer == "A bounded answer" })
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
    }

    @Test fun savedAttachmentsSurviveSourceSettingsChange() = runBlocking {
        val name = "question-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        val client = HttpClient(MockEngine { error("Review must remain local") })
        val station = AndroidSignalStation(context, noWatch, client, name)
        try {
            store.settings(SignalSettings(onboardingComplete = true))
            store.save(row("a"))
            val recipe = SavedQuestion("recipe", "Attached", "Explain this reading", row("a").sourceKeys, setOf("a"))
            store.saveQuestion(recipe)
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            withContext(Dispatchers.Main) { station.openSavedQuestion(recipe.id) }
            until { !station.state.value.busy && station.state.value.savedQuestionDraft != null }
            withContext(Dispatchers.Main) { station.updateSettings(station.state.value.settings.copy(enabled = recipe.sourceKeys)) }
            until { !station.state.value.busy }
            withContext(Dispatchers.Main) { station.ask(recipe.question, false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.recordCount, station.state.value.status)
            withContext(Dispatchers.Main) { station.dismissQuestionReview(); station.ask(recipe.question, false) }
            until { !station.state.value.busy }
            assertEquals(1, station.state.value.questionReview?.recordCount)
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
    }

    @Test fun deletingEvidenceAfterReviewBlocksTransmissionAndRecipesContainOnlyReferences() = runBlocking {
        val name = "question-test-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        var calls = 0
        val client = HttpClient(MockEngine { calls++; error("Must not contact provider") })
        val station = AndroidSignalStation(context, noWatch, client, name)
        try {
            store.settings(SignalSettings(onboardingComplete = true, enabled = row("a").sourceKeys))
            store.put("openai", "test-only-key"); store.save(row("a"))
            val recipe = SavedQuestion("recipe", "Steps", "steps on 2026-09-07", row("a").sourceKeys, setOf("a"), true)
            store.saveQuestion(recipe)
            assertFalse(store.documents("saved_question").single().contains("excluded-sentinel"))
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            withContext(Dispatchers.Main) { station.openSavedQuestion("recipe") }
            until { !station.state.value.busy && station.state.value.savedQuestionDraft != null }
            assertEquals(0, calls); assertEquals(1, store.count())
            withContext(Dispatchers.Main) { station.ask(recipe.question, true) }
            until { !station.state.value.busy && station.state.value.questionReview != null }
            store.delete(setOf("a"))
            withContext(Dispatchers.Main) { station.sendReviewedQuestion() }
            until { !station.state.value.busy }
            assertEquals(0, calls)
            assertContains(station.state.value.status, "Context changed")
            assertEquals(recipe, store.savedQuestions().single())
            val reopened = SignalStore(context, name)
            assertEquals(recipe, reopened.savedQuestions().single()); reopened.close()
        } finally { withContext(Dispatchers.Main) { station.close() }; store.close(); client.close(); context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit() }
    }
}
