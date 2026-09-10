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
