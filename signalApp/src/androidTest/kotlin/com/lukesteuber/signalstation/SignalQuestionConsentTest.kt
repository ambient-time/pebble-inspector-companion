package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual native request routes and encrypted store. Only HTTP, watch transport and time are fixtures. */
class SignalQuestionConsentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(check: () -> Boolean) = withTimeout(30_000) { while (!check()) delay(20) }
    private class Fixture {
        val name = "question-consent-${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SignalStore(context, name)
        var time = System.currentTimeMillis()
        var request = 100
        var failTransport = false
        val bodies = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<String>()
        val session = object : SignalWatchSession {
            override val watchId = "test-watch"
            override val connectionId = "test-connection"
            override val ready = true
            override suspend fun sendConfigMessage(message: String) { commands += message }
        }
        val link = object : SignalWatchLink {
            override val watches = MutableStateFlow(listOf(SignalWatch(session.watchId, "Watch", true, session.connectionId, true)))
            override val capabilities = SignalWatchCapabilities(messages = true)
            override fun initialize(scope: CoroutineScope) = Unit
            override suspend fun isTrusted(session: SignalWatchSession) = session.watchId == "test-watch" && session.connectionId == "test-connection"
            override suspend fun launch(watchId: String) = Unit
            override suspend fun install(watchId: String) = Unit
        }
        private fun makeClient() = HttpClient(MockEngine { request ->
            bodies += (request.body as TextContent).text
            if (failTransport) throw java.io.IOException("Fixture ambiguous network interruption")
            respond("""{"output":[{"content":[{"type":"output_text","text":"Bounded fixture answer"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var client = makeClient()
        var station = AndroidSignalStation(context, link, client, name, clock = { time })
        suspend fun main(block: AndroidSignalStation.() -> Unit) = withContext(Dispatchers.Main) { station.block() }
        suspend fun start() {
            store.settings(SignalSettings(onboardingComplete = true, enabled = setOf("device.battery"), watchId = session.watchId))
            store.put("openai", "fixture-key")
            val connection = HomeConnection("house", "Test house", HomeConnectorKind.HOME_ASSISTANT, "https://house.invalid")
            store.document("home:v1", "home", Json.encodeToString(HomeState(connections = listOf(connection))))
            main { initialize() }
            withTimeout(30_000) { while (!station.state.value.historyReady || !station.state.value.homeReady) delay(20) }
            assertEquals(200, station.handleWatchRequest(AndroidSignalStation.PREFIX + "capabilities", "GET", null, session).status)
        }
        suspend fun route(kind: String, fields: JsonObjectBuilder.() -> Unit = {}): SignalWatchResponse = station.handleWatchRequest(
            AndroidSignalStation.PREFIX + "question", "POST", buildJsonObject { put("request_id", ++request); put("kind", kind); fields() }.toString(), session)
        suspend fun open(prompt: String, contextKind: String = "none", contextId: String = ""): JsonObject {
            val response = route("question-open") { put("prompt", prompt); put("context_kind", contextKind); if (contextId.isNotEmpty()) put("context_id", contextId) }
            assertEquals(200, response.status, response.result)
            return Json.parseToJsonElement(response.result).jsonObject
        }
        suspend fun bound(kind: String, draft: JsonObject, more: JsonObjectBuilder.() -> Unit = {}): SignalWatchResponse = route(kind) {
            put("draft_id", draft["draft_id"]!!); put("revision", draft["revision"]!!); more()
        }
        suspend fun review(draft: JsonObject): JsonObject {
            val result = bound("question-review", draft)
            assertEquals(200, result.status, result.result)
            return Json.parseToJsonElement(result.result).jsonObject
        }
        suspend fun send(review: JsonObject): SignalWatchResponse = bound("question-send", review) { put("review_id", review["review_id"]!!) }
        suspend fun restart() {
            main { close() }
            client = makeClient(); station = AndroidSignalStation(context, link, client, name, clock = { time })
            main { initialize() }
            withTimeout(30_000) { while (!station.state.value.historyReady || !station.state.value.homeReady) delay(20) }
            assertEquals(200, station.handleWatchRequest(AndroidSignalStation.PREFIX + "capabilities", "GET", null, session).status)
        }
        suspend fun close() {
            main { close() }; store.close(); client.close()
            context.deleteDatabase("$name-history.db"); context.getSharedPreferences("${name}_private", 0).edit().clear().commit()
        }
        fun capture(id: String, watch: String, text: String) = SignalRecord(id, "capture-$id", time, "Capture", provider = "local", model = "", watchId = watch,
            state = "ready", kind = "capture", observations = listOf(SignalObservation("device.battery", "phone", text, collectedAt = time, measuredAt = time)), sourceKeys = setOf("device.battery"))
    }

    @Test fun watchNeverInheritsOrClearsPhoneDraftAndEveryProviderCallNeedsOneUseReview() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            f.store.save(f.capture("phone-private", "", "PRIVATE_ATTACHMENT_SENTINEL"))
            f.main { analyzeRecord("phone-private") }
            until { !f.station.state.value.busy }
            f.main { saveMemoryNote("PRIVATE_MEMORY_SENTINEL") }
            until { f.station.state.value.memories.isNotEmpty() }
            f.main { setHomeAccess(setOf("house")); setHomeActionsAllowed(true) }
            f.main { ask("PRIVATE_PHONE_QUESTION") }
            until { !f.station.state.value.busy && f.station.state.value.questionReview != null }
            val before = f.station.state.value
            val draft = f.open("An unrelated wrist question")
            val review = f.review(draft)
            assertEquals("none", review["home_mode"]!!.jsonPrimitive.content)
            assertTrue(f.bodies.isEmpty())
            assertEquals(200, f.send(review).status)
            until { !f.station.state.value.busy && f.bodies.size == 1 }
            assertEquals(409, f.send(review).status)
            val payload = f.bodies.single()
            for (sentinel in listOf("PRIVATE_ATTACHMENT_SENTINEL", "PRIVATE_MEMORY_SENTINEL", "PRIVATE_PHONE_QUESTION", "home_request_action", "Test house")) assertFalse(payload.contains(sentinel), sentinel)
            val after = f.station.state.value
            assertEquals(before.threadId, after.threadId)
            assertEquals(before.attachedRecords, after.attachedRecords)
            assertEquals(before.homeAccess, after.homeAccess)
            assertEquals(before.homeActionsAllowed, after.homeActionsAllowed)
            assertEquals(before.questionReview, after.questionReview)
            val saved = after.records.first { it.question == "An unrelated wrist question" }
            assertEquals(review["review_id"]!!.jsonPrimitive.content, saved.questionConsent?.reviewId)
            assertTrue(saved.references.isEmpty())
            assertEquals("watch", f.store.record(saved.id)?.questionConsent?.origin)
            assertEquals(426, f.station.handleWatchRequest(AndroidSignalStation.PREFIX + "start", "POST", """{"request_id":999,"kind":"ask","prompt":"legacy"}""", f.session).status)
            assertEquals(1, f.bodies.size)
        } finally { f.close() }
    }

    @Test fun exactCaptureAndFollowUpUseOnlySelectedLineageAndExpireWithoutTransmission() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            val chosen = f.capture("chosen-capture", f.session.watchId, "CHOSEN_SENTINEL")
            f.store.save(chosen)
            val draft = f.open("Explain this", "capture", chosen.id)
            f.store.save(f.capture("newer-capture", f.session.watchId, "NEWER_UNSELECTED_SENTINEL"))
            val review = f.review(draft)
            assertEquals(200, f.send(review).status)
            until { !f.station.state.value.busy && f.bodies.size == 1 }
            assertContains(f.bodies.single(), "CHOSEN_SENTINEL")
            assertFalse(f.bodies.single().contains("NEWER_UNSELECTED_SENTINEL"))
            val answer = f.station.state.value.records.first { it.question == "Explain this" }
            val follow = f.review(f.open("Why?", "answer", answer.id))
            assertEquals("none", follow["home_mode"]!!.jsonPrimitive.content)
            assertEquals(200, f.send(follow).status)
            until { !f.station.state.value.busy && f.bodies.size == 2 }
            assertContains(f.bodies.last(), "Bounded fixture answer"); assertContains(f.bodies.last(), "CHOSEN_SENTINEL")
            val expired = f.review(f.open("Do not send after expiry"))
            f.time += 120_001
            assertEquals(409, f.send(expired).status)
            assertEquals(2, f.bodies.size)
            val changed = f.review(f.open("Do not use changed evidence", "capture", chosen.id))
            f.store.save(chosen.copy(observations = chosen.observations.map { it.copy(value = "ALTERED") }))
            assertEquals(200, f.send(changed).status)
            until { !f.station.state.value.busy }
            assertEquals(2, f.bodies.size)
            assertContains(f.station.state.value.status, "Context changed")
        } finally { f.close() }
    }

    @Test fun homeSelectionIsDraftBoundAndHandoffPreservesUnsentPhoneQuestion() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            f.main { ask("phone draft") }
            until { !f.station.state.value.busy && f.station.state.value.questionReview != null }
            val before = f.station.state.value.questionReview
            val draft = f.open("Read this system")
            val selectedResponse = f.bound("question-home", draft) { put("mode", "read"); put("connection_id", "house"); put("selected", true) }
            assertEquals(200, selectedResponse.status)
            val selected = Json.parseToJsonElement(selectedResponse.result).jsonObject
            assertEquals(409, f.bound("question-review", draft).status, "Old revision cannot be reviewed")
            val review = f.review(selected)
            assertContains(review["text"]!!.jsonPrimitive.content, "Test house")
            assertEquals(200, f.bound("question-phone", selected).status)
            assertNotNull(f.station.state.value.watchQuestionReview)
            assertEquals(before, f.station.state.value.questionReview)
            assertFalse(f.station.state.value.watchQuestionReview!!.homeActionsAllowed)
            val handedOffToken = JsonObject(review + ("review_id" to JsonPrimitive(f.station.state.value.watchQuestionReview!!.reviewId)))
            f.main { invalidateWatchQuestionReview() }
            assertEquals(409, f.send(handedOffToken).status, "Phone editing must immediately invalidate the wrist's older revision")
            assertTrue(f.station.state.value.watchQuestionReview!!.reviewId.isBlank())
            f.main { reviewWatchQuestion("Read the selected system after my edit") }
            until { !f.station.state.value.busy }
            assertTrue(f.station.state.value.watchQuestionReview!!.reviewId.isNotBlank())
            f.main { sendWatchQuestionReview() }
            until { !f.station.state.value.busy && f.bodies.size == 1 }
            assertContains(f.bodies.single(), "read-only")
            assertEquals(before, f.station.state.value.questionReview)
            assertEquals("none", f.open("Next unrelated question")["home_mode"]!!.jsonPrimitive.content)
            assertTrue(f.station.state.value.homeAccess.isEmpty())
        } finally { f.close() }
    }

    @Test fun ambiguousProviderFailureRemainsClaimedAcrossNativeRestart() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.start()
            val review = f.review(f.open("Do not silently retry"))
            f.failTransport = true
            assertEquals(200, f.send(review).status)
            until { !f.station.state.value.busy && f.bodies.isNotEmpty() }
            val calls = f.bodies.size
            assertEquals(1, calls)
            val receipt = f.station.state.value.records.first { it.question == "Do not silently retry" }
            assertNotNull(f.store.record(receipt.id)?.questionConsent)
            assertEquals(409, f.send(review).status)
            f.restart()
            assertEquals(409, f.send(review).status)
            assertEquals(calls, f.bodies.size)
            assertNotNull(f.store.record(receipt.id)?.questionConsent)
        } finally { f.close() }
    }

    @Test fun nativeWakeReviewDoesNotUsePhoneDraftAndOldWakeConfirmationCannotSend() = runBlocking<Unit> {
        val f = Fixture()
        // The internal runtime is reached reflectively so this separate Android app test does not
        // widen the module's production API or start microphone collection.
        val runtimeClass = Class.forName("coredevices.pebble.signal.SignalWakeRuntime")
        val runtime = runtimeClass.getField("INSTANCE").get(null)
        try {
            f.start()
            f.store.save(f.capture("private-wake-attachment", "", "PRIVATE_WAKE_SENTINEL"))
            f.main { analyzeRecord("private-wake-attachment") }
            until { !f.station.state.value.busy }
            f.main { setHomeAccess(setOf("house")); setHomeActionsAllowed(true) }
            val before = f.station.state.value
            withContext(Dispatchers.Main) {
                val wakeToken = runtimeClass.getMethod("begin").invoke(runtime) as Long
                assertEquals(true, runtimeClass.getMethod("update", java.lang.Long.TYPE, String::class.java, String::class.java)
                    .invoke(runtime, wakeToken, "recording", "Synthetic recording fixture; no microphone opened"))
                assertEquals(true, runtimeClass.getMethod("draft", java.lang.Long.TYPE, String::class.java).invoke(runtime, wakeToken, "Isolated wake question"))
            }
            until { f.station.state.value.wakeDraft.isNotBlank() }
            f.main { reviewWakeOnWatch() }
            until { !f.station.state.value.busy && f.commands.any { it.contains("question-review") } }
            val review = f.commands.map { Json.parseToJsonElement(it).jsonObject }.first { it["kind"]?.jsonPrimitive?.content == "question-review" }
            assertEquals("none", review["home_mode"]!!.jsonPrimitive.content)
            assertTrue(f.bodies.isEmpty())
            assertEquals(426, f.station.handleWatchRequest(AndroidSignalStation.PREFIX + "confirm-wake", "POST", """{"request_id":777}""", f.session).status)
            assertEquals(200, f.send(review).status)
            until { !f.station.state.value.busy && f.bodies.size == 1 }
            assertFalse(f.bodies.single().contains("PRIVATE_WAKE_SENTINEL"))
            assertFalse(f.bodies.single().contains("home_request_action"))
            assertEquals(before.attachedRecords, f.station.state.value.attachedRecords)
            assertEquals(before.threadId, f.station.state.value.threadId)
            assertEquals(before.homeAccess, f.station.state.value.homeAccess)
            assertEquals("wake", f.station.state.value.records.first { it.question == "Isolated wake question" }.questionConsent?.origin)
        } finally {
            withContext(Dispatchers.Main) { runtimeClass.getMethod("dismiss").invoke(runtime) }
            f.close()
        }
    }
}
