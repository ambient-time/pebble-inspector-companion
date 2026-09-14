package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.UUID
import kotlin.test.*

class SignalWatchHandoffTest {
    @Test fun handoffSelectsExactSavedReplyWithoutSendingOrReplacingDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "watch-handoff-${UUID.randomUUID()}"
        val session = object : SignalWatchSession {
            override val watchId = "fixture-watch"
            override val connectionId = "fixture-session"
            override val ready = true
            override suspend fun sendConfigMessage(message: String) = Unit
        }
        val link = object : SignalWatchLink {
            override val watches = MutableStateFlow(listOf(SignalWatch(session.watchId, "Fixture watch", true, session.connectionId, true)))
            override val capabilities = SignalWatchCapabilities(messages = true)
            override fun initialize(scope: CoroutineScope) = Unit
            override suspend fun isTrusted(session: SignalWatchSession) = session.connectionId == "fixture-session"
            override suspend fun launch(watchId: String) = Unit
            override suspend fun install(watchId: String) = Unit
        }
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"output":[{"content":[{"type":"output_text","text":"Full fixture reply $calls"}]}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = SignalStore(context, name)
        store.settings(SignalSettings(onboardingComplete = true, watchId = session.watchId))
        store.put("openai", "fixture-only-key")
        val station = AndroidSignalStation(context, link, client, name)
        suspend fun until(check: () -> Boolean) = withTimeout(30000) { while (!check()) delay(30) }
        suspend fun route(path: String, body: String? = null, caller: SignalWatchSession = session) = station.handleWatchRequest(
            AndroidSignalStation.PREFIX + path, if (path == "capabilities") "GET" else "POST", body, caller)
        try {
            withContext(Dispatchers.Main) { station.initialize() }
            until { station.state.value.historyReady }
            assertEquals(200, route("capabilities").status)
            assertEquals(200, route("start", """{"request_id":301,"kind":"ask","prompt":"First"}""").status)
            until { !station.state.value.busy && calls == 1 }
            val first = station.state.value.records.first { it.question == "First" }
            assertEquals("ready", first.state)
            assertEquals(200, route("start", """{"request_id":302,"kind":"ask","prompt":"Second"}""").status)
            until { !station.state.value.busy && calls == 2 }
            withContext(Dispatchers.Main) { station.ask("Unsent phone question") }
            until { !station.state.value.busy && station.state.value.questionReview != null }
            val before = station.state.value
            assertEquals(200, route("continue-phone", """{"request_id":301}""").status)
            assertEquals(first.id, station.state.value.watchHandoffRecordId)
            assertEquals(before.threadId, station.state.value.threadId)
            assertEquals(before.questionReview, station.state.value.questionReview)
            assertEquals(before.selectedRecordId, station.state.value.selectedRecordId)
            assertEquals(2, calls)
            assertEquals(200, route("continue-phone", """{"request_id":301}""").status)
            assertEquals(404, route("continue-phone", """{"request_id":999}""").status)
            val impostor = object : SignalWatchSession by session { override val connectionId = "stale-session" }
            assertEquals(403, route("continue-phone", """{"request_id":301}""", impostor).status)
            withContext(Dispatchers.Main) { station.selectRecord(first.id); station.dismissWatchHandoff() }
            until { !station.state.value.selectedRecordLoading }
            assertEquals(first.answer, station.state.value.selectedRecord?.answer)
            assertNull(station.state.value.watchHandoffRecordId)
            store.delete(setOf(first.id))
            assertEquals(404, route("continue-phone", """{"request_id":301}""").status)
            assertEquals(2, calls)
        } finally {
            withContext(Dispatchers.Main) { station.close() }
            store.close(); client.close()
            context.deleteDatabase("$name-history.db")
            context.getSharedPreferences("${name}_private", 0).edit().clear().commit()
        }
    }
}
