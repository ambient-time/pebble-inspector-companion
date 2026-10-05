package coredevices.pebble.signal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SignalSpeechState(val recordId: String? = null, val active: Boolean = false, val status: String = "")

interface SignalSpeech {
    val state: StateFlow<SignalSpeechState>
    fun play(record: SignalRecord)
    fun stop()
}

interface SignalSpeechEngine {
    val maxInputLength: Int
    suspend fun prepare(): String
    suspend fun speak(text: String, onStart: () -> Unit)
    fun close()
}

class SignalSpeechFailure(message: String) : Exception(message)
data class SignalSpeechVoice(val id: String, val languageTag: String, val network: Boolean = false, val installed: Boolean = true)

object SignalSpeechPolicy {
    fun eligible(record: SignalRecord) = record.state == "ready" && record.answer.isNotBlank() &&
        record.kind != "capture" && record.provider != "local"

    fun voice(voices: List<SignalSpeechVoice>, defaultId: String?, languageTag: String): SignalSpeechVoice? =
        voices.filter { !it.network && it.installed }.sortedWith(compareBy(
            { it.id != defaultId },
            { !it.languageTag.equals(languageTag, ignoreCase = true) },
            { !it.languageTag.substringBefore('-').equals(languageTag.substringBefore('-'), ignoreCase = true) },
            { it.languageTag }, { it.id },
        )).firstOrNull()

    fun chunks(text: String, limit: Int): List<String> {
        require(limit >= 2)
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + limit, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf(' ', end - 1)
                if (boundary > offset + limit / 2) end = boundary + 1
                if (text[end - 1].isHighSurrogate()) end--
            }
            chunks += text.substring(offset, end)
            offset = end
        }
        return chunks
    }
}

/** Call on the UI dispatcher. Every engine belongs to one explicit playback attempt. */
class SignalSpeechController(private val scope: CoroutineScope, private val createEngine: () -> SignalSpeechEngine) : SignalSpeech {
    private val mutable = MutableStateFlow(SignalSpeechState())
    override val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var engine: SignalSpeechEngine? = null

    override fun play(record: SignalRecord) {
        stop()
        if (!SignalSpeechPolicy.eligible(record)) return
        val mine = ++generation
        mutable.value = SignalSpeechState(record.id, true, "Preparing installed offline voice…")
        val next = scope.launch(start = CoroutineStart.LAZY) {
            var current: SignalSpeechEngine? = null
            try {
                withTimeout(600_000) {
                    current = createEngine().also { engine = it }
                    val output = checkNotNull(current)
                    val voice = withTimeout(15_000) { output.prepare() }
                    ensureActive()
                    for (part in SignalSpeechPolicy.chunks(record.answer, minOf(3000, output.maxInputLength))) {
                        withTimeout(180_000) {
                            output.speak(part) {
                                if (mine == generation) mutable.value = SignalSpeechState(record.id, true, "Reading with $voice through Android audio output.")
                            }
                        }
                    }
                }
                if (mine == generation) mutable.value = SignalSpeechState(record.id, status = "Finished reading. Your reply is still saved.")
            } catch (_: TimeoutCancellationException) {
                if (mine == generation) mutable.value = SignalSpeechState(record.id, status = "Speech timed out. Check Android text-to-speech settings, then try Listen again.")
            } catch (cancelled: CancellationException) {
                if (mine == generation) mutable.value = SignalSpeechState(record.id, status = "Reading stopped.")
                throw cancelled
            } catch (failure: Exception) {
                if (mine == generation) mutable.value = SignalSpeechState(record.id,
                    status = (failure as? SignalSpeechFailure)?.message ?: "Speech could not play. Check Android text-to-speech settings, then try Listen again. Your reply is still saved.")
            } finally {
                if (mine == generation) { job = null; ++generation }
                release(current)
            }
        }
        job = next
        next.start()
    }

    override fun stop() {
        ++generation
        job?.cancel(); job = null
        release(engine)
        if (mutable.value.active) mutable.value = mutable.value.copy(active = false, status = "Reading stopped.")
    }

    private fun release(output: SignalSpeechEngine?) {
        if (output != null && engine === output) {
            engine = null
            runCatching { output.close() }
        }
    }
}
