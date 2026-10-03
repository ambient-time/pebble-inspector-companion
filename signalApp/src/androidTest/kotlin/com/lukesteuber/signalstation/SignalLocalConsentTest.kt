package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.UUID
import kotlin.test.*

/** Isolated encrypted namespace; production keys, pairing and history are never read or changed. */
class SignalLocalConsentTest {
    @Test fun localAnswersRequireOneUseReviewAndKeepExactContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "local-consent-${UUID.randomUUID()}"
        val store = SignalStore(context, namespace)
        val calls = mutableListOf<List<Pair<String, String>>>()
        var fail = false
        val http = HttpClient(MockEngine { error("Local consent test must never send HTTP") })
        val link = object : SignalWatchLink {
            override val capabilities = SignalWatchCapabilities()
            override suspend fun isTrusted(session: SignalWatchSession) = false
            override val watches = MutableStateFlow<List<SignalWatch>>(emptyList())
            override fun initialize(scope: CoroutineScope) = Unit
            override suspend fun launch(watchId: String) = Unit
            override suspend fun install(watchId: String) = Unit
        }
        val station = AndroidSignalStation(context, link, http, namespace,
            localReadiness = MutableStateFlow(setOf("local-gemma")), localAnswer = { provider, messages ->
                assertEquals("local-gemma", provider); calls += messages
                if (fail) throw SignalProviderException("Synthetic local failure")
                "Synthetic on-phone answer"
            })
        suspend fun idle() = withTimeout(20_000) { while (station.state.value.busy || station.state.value.historyLoading) delay(20) }
        suspend fun review(question: String): SignalQuestionReview {
            idle(); withContext(Dispatchers.Main) { station.ask(question, false) }; idle()
            return assertNotNull(station.state.value.questionReview, station.state.value.status)
        }
        suspend fun send() { withContext(Dispatchers.Main) { station.sendReviewedQuestion() }; idle() }
        try {
            store.settings(SignalSettings(onboardingComplete = true, provider = "local-gemma", model = "local-file", enabled = emptySet()))
            withContext(Dispatchers.Main) { station.initialize() }
            withTimeout(20_000) { while (!station.state.value.historyReady) delay(20) }
            val first = review("Synthetic local question")
            assertEquals(0, calls.size)
            send(); assertEquals(first.messages, calls.single()); send(); assertEquals(1, calls.size)
            fail = true
            val second = review("Synthetic failing question")
            send(); assertEquals(second.messages, calls.last()); assertEquals(2, calls.size)
            assertNull(station.state.value.questionReview)
            send(); assertEquals(2, calls.size, "Failed local requests cannot reuse consumed consent")
            withContext(Dispatchers.Main) { station.newThread() }; idle()
            withContext(Dispatchers.Main) { station.ask("é".repeat(4000), false) }; idle()
            assertNull(station.state.value.questionReview)
            assertContains(station.state.value.status, "6,000")
            assertEquals(2, calls.size, "Oversized context must be rejected before inference")
        } finally {
            withContext(Dispatchers.Main) { station.close() }; store.close(); http.close()
            context.deleteDatabase("$namespace-history.db")
            context.getSharedPreferences("${namespace}_private", 0).edit().clear().commit()
        }
    }
}
