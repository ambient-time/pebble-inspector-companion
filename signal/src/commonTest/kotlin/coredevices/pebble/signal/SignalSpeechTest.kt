package coredevices.pebble.signal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SignalSpeechTest {
    private fun reply(id: String = "reply", text: String = "Outside 22 C, fresh. Room 18 C, stale. Humidity unavailable.") =
        SignalRecord(id, "thread", 1, "Synthetic question", answer = text, provider = "openai", model = "fixture", state = "ready")

    @Test fun onlyCompletedReplyTextIsEligible() {
        val ready = reply()
        assertTrue(SignalSpeechPolicy.eligible(ready))
        for (state in listOf("working", "error", "cancelled", "interrupted")) assertFalse(SignalSpeechPolicy.eligible(ready.copy(state = state)))
        assertFalse(SignalSpeechPolicy.eligible(ready.copy(answer = " ", summary = "Do not read this")))
        assertFalse(SignalSpeechPolicy.eligible(ready.copy(kind = "capture")))
        assertFalse(SignalSpeechPolicy.eligible(ready.copy(provider = "local")))
    }

    @Test fun chunksPreserveAllTextAndSurrogatePairs() {
        val text = "# Report\n- -2.5 °C stale\nHumidity unavailable. `a_b`\n" + "🙂".repeat(20)
        val chunks = SignalSpeechPolicy.chunks(text, 13)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 13 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertFailsWith<IllegalArgumentException> { SignalSpeechPolicy.chunks(text, 1) }
    }

    @Test fun voiceSelectionNeverUsesNetworkOrMissingData() {
        val voices = listOf(
            SignalSpeechVoice("network", "en-US", network = true),
            SignalSpeechVoice("missing", "en-US", installed = false),
            SignalSpeechVoice("french", "fr-FR"),
            SignalSpeechVoice("english", "en-US"),
        )
        assertEquals("english", SignalSpeechPolicy.voice(voices, "network", "en-US")?.id)
        assertEquals("french", SignalSpeechPolicy.voice(voices, "french", "en-US")?.id)
        assertNull(SignalSpeechPolicy.voice(voices.take(2), "network", "en-US"))
    }

    @Test fun nothingStartsUntilExplicitPlayAndOnlyAnswerReachesEngine() = runTest {
        val engine = FakeEngine()
        var created = 0
        val speech = SignalSpeechController(backgroundScope) { created++; engine }
        runCurrent()
        assertEquals(0, created)
        speech.play(reply())
        runCurrent()
        assertEquals(listOf(reply().answer), engine.spoken)
        assertTrue(speech.state.value.active)
        engine.done.complete(Unit)
        runCurrent()
        assertFalse(speech.state.value.active)
        assertEquals(1, engine.closes)
    }

    @Test fun invalidRecordDoesNotCreateEngine() = runTest {
        var created = 0
        val speech = SignalSpeechController(backgroundScope) { created++; FakeEngine() }
        speech.play(reply().copy(state = "working"))
        runCurrent()
        assertEquals(0, created)
        assertFalse(speech.state.value.active)
    }

    @Test fun stopDuringPreparationPreventsLaterSpeech() = runTest {
        val ready = CompletableDeferred<Unit>()
        val engine = FakeEngine(ready)
        val speech = SignalSpeechController(backgroundScope) { engine }
        speech.play(reply()); runCurrent()
        speech.stop(); ready.complete(Unit); runCurrent()
        assertTrue(engine.spoken.isEmpty())
        assertFalse(speech.state.value.active)
        assertEquals(1, engine.closes)
    }

    @Test fun staleStartAndCompletionCannotAffectNewReply() = runTest {
        val engines = mutableListOf<FakeEngine>()
        val speech = SignalSpeechController(backgroundScope) { FakeEngine().also(engines::add) }
        speech.play(reply("one")); runCurrent()
        speech.play(reply("two")); runCurrent()
        engines.first().onStart?.invoke(); engines.first().done.complete(Unit); runCurrent()
        assertEquals("two", speech.state.value.recordId)
        assertTrue(speech.state.value.active)
        assertEquals(1, engines.first().closes)
        speech.stop(); runCurrent()
        engines.last().onStart?.invoke()
        assertFalse(speech.state.value.active)
        assertEquals(1, engines.last().closes)
    }

    @Test fun preparationTimeoutClosesEngineAndKeepsTextAvailable() = runTest {
        val engine = FakeEngine(CompletableDeferred())
        val speech = SignalSpeechController(backgroundScope) { engine }
        speech.play(reply()); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertFalse(speech.state.value.active)
        assertTrue(speech.state.value.status.contains("timed out"))
        assertEquals(1, engine.closes)
    }

    @Test fun unexpectedErrorsNeverExposeEngineMessages() = runTest {
        val speech = SignalSpeechController(backgroundScope) { throw IllegalStateException("PRIVATE_ENGINE_DETAIL") }
        speech.play(reply()); runCurrent()
        assertFalse(speech.state.value.active)
        assertFalse(speech.state.value.status.contains("PRIVATE_ENGINE_DETAIL"))
    }

    private class FakeEngine(private val ready: CompletableDeferred<Unit>? = null) : SignalSpeechEngine {
        val spoken = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        var onStart: (() -> Unit)? = null
        var closes = 0
        override val maxInputLength = 3000
        override suspend fun prepare(): String { ready?.await(); return "English offline" }
        override suspend fun speak(text: String, onStart: () -> Unit) {
            spoken += text; this.onStart = onStart; onStart(); done.await()
        }
        override fun close() { closes++ }
    }
}
