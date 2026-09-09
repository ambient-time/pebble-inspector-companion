package coredevices.pebble.signal

import coredevices.speex.SpeexCodec
import coredevices.speex.SpeexDecodeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import io.rebble.libpebblecommon.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

/** Error messages are deliberately fixed: upstream bodies and URLs may contain private input. */
class SignalProviderException(message: String) : Exception(message)

class SignalProviders(http: HttpClient, private val secrets: SignalSecrets) {
    // Redirects must never forward credentials or private prompts to a different destination.
    // Reuse only transport, not upstream logging, authentication, retries or default headers.
    private val client = HttpClient(http.engine) { followRedirects = false; expectSuccess = false }

    fun close() = client.close()

    suspend fun transcriptionConfigured(): Boolean = try { !secrets.get("transcription").isNullOrBlank() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }

    suspend fun answer(settings: SignalSettings, messages: List<Pair<String, String>>): SignalReply =
        guarded("Answer request", 30_000) {
            if (messages.isEmpty() || messages.any { it.first !in setOf("system", "user", "assistant") })
                fail("Conversation is invalid.")
            if (messages.sumOf { it.second.encodeToByteArray().size.toLong() } > 256 * 1024)
                fail("Conversation is too large. Start a new thread.")
            if (settings.model.isBlank()) fail("Choose a model in Settings.")
            val key = secrets.get(settings.provider)?.takeIf { it.isNotBlank() }
                ?: fail("Add a key for the selected provider in Settings.")
            val request = request(settings, messages)
            val json = post(request.first, key, settings.provider, request.second.toString())
            val text = parseAnswer(settings.provider, json)
            if (text.isBlank()) fail("The provider returned no text. Check the selected model.")
            val bounded = truncateUtf8(text.trim(), 16 * 1024)
            SignalReply(bounded, truncateUtf8(bounded, 900))
        }

    suspend fun transcribe(wav: ByteArray): String = guarded("Transcription", 14_000) {
        if (wav.size <= 44 || wav.size > 4 * 1024 * 1024) fail("Recording is empty or too long.")
        val key = secrets.get("transcription")?.takeIf { it.isNotBlank() }
            ?: fail("Add an OpenAI transcription key in Settings.")
        val form = MultiPartFormDataContent(formData {
            append("model", "gpt-transcribe")
            append("file", wav, Headers.build {
                append(HttpHeaders.ContentType, "audio/wav")
                append(HttpHeaders.ContentDisposition, "filename=recording.wav")
            })
        })
        val result = client.preparePost("https://api.openai.com/v1/audio/transcriptions") {
            bearerAuth(key)
            setBody(form)
        }.execute { response ->
            checkStatus(response.status.value)
            parseJson(readBounded(response.bodyAsChannel()))
        }
        val text = result["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) fail("No speech was recognized.")
        truncateUtf8(text, 8 * 1024)
    }

    private suspend fun post(url: String, key: String, provider: String, body: String): JsonObject =
        client.preparePost(url) {
            contentType(ContentType.Application.Json)
            if (provider == "anthropic") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
            } else if (provider == "gemini") header("x-goog-api-key", key)
            else bearerAuth(key)
            setBody(body)
        }.execute { response ->
            checkStatus(response.status.value)
            parseJson(readBounded(response.bodyAsChannel()))
        }

    private fun request(settings: SignalSettings, messages: List<Pair<String, String>>): Pair<String, JsonObject> {
        val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val dialogue = messages.filter { it.first != "system" }
        return when (settings.provider) {
            "openai", "xai" -> {
                val host = if (settings.provider == "openai") "api.openai.com" else "api.x.ai"
                "https://$host/v1/responses" to buildJsonObject {
                    put("model", settings.model); put("store", false); put("max_output_tokens", 4096)
                    putJsonArray("input") { messages.forEach { (role, content) -> add(buildJsonObject {
                        put("role", role); put("content", content)
                    }) } }
                }
            }
            "anthropic" -> "https://api.anthropic.com/v1/messages" to buildJsonObject {
                put("model", settings.model); put("max_tokens", 4096)
                if (system.isNotEmpty()) put("system", system)
                putJsonArray("messages") { dialogue.forEach { (role, content) -> add(buildJsonObject {
                    put("role", role); put("content", content)
                }) } }
            }
            "gemini" -> {
                if (!settings.model.matches(Regex("[A-Za-z0-9._-]+"))) fail("Gemini model name is invalid.")
                "https://generativelanguage.googleapis.com/v1beta/models/${settings.model}:generateContent" to buildJsonObject {
                    if (system.isNotEmpty()) putJsonObject("systemInstruction") {
                        put("parts", parts(system))
                    }
                    putJsonArray("contents") { dialogue.forEach { (role, content) -> add(buildJsonObject {
                        put("role", if (role == "assistant") "model" else "user")
                        put("parts", parts(content))
                    }) } }
                    putJsonObject("generationConfig") { put("maxOutputTokens", 4096) }
                }
            }
            "openrouter", "custom" -> {
                val endpoint = if (settings.provider == "openrouter") "https://openrouter.ai/api/v1/chat/completions"
                    else customEndpoint(settings.endpoint)
                endpoint to buildJsonObject {
                    put("model", settings.model); put("max_tokens", 4096); put("stream", false)
                    putJsonArray("messages") { messages.forEach { (role, content) -> add(buildJsonObject {
                        put("role", role); put("content", content)
                    }) } }
                }
            }
            else -> fail("The selected provider is not supported.")
        }
    }

    private fun parts(text: String) = buildJsonArray { add(buildJsonObject { put("text", text) }) }

    companion object {
        internal fun customEndpoint(value: String): String {
            val url = try { Url(value.trim()) } catch (_: Exception) { fail("Enter a valid HTTPS endpoint.") }
            if (url.protocol != URLProtocol.HTTPS || url.host.isBlank() || url.user != null || url.password != null ||
                url.fragment.isNotEmpty() || url.parameters.names().isNotEmpty()) fail("Enter an HTTPS endpoint without credentials or query parameters.")
            val base = value.trim().trimEnd('/')
            return if (url.encodedPath.trimEnd('/').endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        internal fun parseAnswer(provider: String, root: JsonObject): String {
            if (root["error"] != null && root["error"] != JsonNull) fail("The provider could not complete this request.")
            return when (provider) {
                "openai", "xai" -> (root["output"] as? JsonArray).orEmpty().flatMap { item ->
                    ((item as? JsonObject)?.get("content") as? JsonArray).orEmpty()
                }.mapNotNull { item ->
                    val block = item as? JsonObject
                    if (block?.get("type")?.jsonPrimitive?.contentOrNull == "output_text")
                        block["text"]?.jsonPrimitive?.contentOrNull else null
                }.joinToString("\n")
                "anthropic" -> (root["content"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val block = item as? JsonObject
                    if (block?.get("type")?.jsonPrimitive?.contentOrNull == "text")
                        block["text"]?.jsonPrimitive?.contentOrNull else null
                }.joinToString("\n")
                "gemini" -> ((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                    .filter { (it as? JsonObject)?.get("thought")?.jsonPrimitive?.booleanOrNull != true }
                    .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
                "openrouter", "custom" -> ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> fail("The selected provider is not supported.")
            }
        }

        internal fun truncateUtf8(value: String, maxBytes: Int): String {
            val bytes = value.encodeToByteArray()
            if (bytes.size <= maxBytes) return value
            val suffix = "…"
            var end = maxBytes - suffix.encodeToByteArray().size
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.decodeToString(0, end) + suffix
        }

        private fun checkStatus(status: Int) {
            when {
                status in 200..299 -> Unit
                status == 401 || status == 403 -> fail("Provider authentication failed. Check the key and model access.")
                status == 429 -> fail("Provider limit reached. Check usage before trying again.")
                status in 300..399 -> fail("Provider redirect refused. Check the endpoint.")
                status == 400 || status == 404 || status == 422 -> fail("Provider rejected the request. Check the model and endpoint.")
                else -> fail("The provider is unavailable. Try again later.")
            }
        }

        private fun parseJson(text: String): JsonObject = try { Json.parseToJsonElement(text).jsonObject }
            catch (_: Exception) { fail("The provider returned an invalid response.") }

        private suspend fun readBounded(channel: io.ktor.utils.io.ByteReadChannel): String {
            val output = ByteArray(128 * 1024 + 1)
            var size = 0
            while (size < output.size) {
                val count = channel.readAvailable(output, size, output.size - size)
                if (count < 0) break
                size += count
            }
            if (size > 128 * 1024) fail("The provider response exceeded the safe size limit.")
            return output.decodeToString(0, size)
        }

        private suspend fun <T> guarded(label: String, timeoutMs: Long, block: suspend () -> T): T = try {
            withTimeoutOrNull(timeoutMs) { Result.success(block()) }?.getOrThrow()
                ?: fail("$label timed out. No automatic retry was made.")
        } catch (e: CancellationException) { throw e
        } catch (e: SignalProviderException) { throw e
        } catch (_: Exception) { fail("$label failed. Check your connection and provider settings.") }

        private fun fail(message: String): Nothing = throw SignalProviderException(message)
    }
}

/** Only selected by the native caller-UUID override; ordinary watch dictation stays upstream. */
class SignalWatchTranscription(
    private val providers: SignalProviders,
    private val enabled: suspend () -> Boolean,
) : TranscriptionProvider {
    override suspend fun canServeSession(): Boolean = enabled() && providers.transcriptionConfigured()

    @OptIn(ExperimentalUnsignedTypes::class)
    override suspend fun transcribe(encoderInfo: VoiceEncoderInfo, audioFrames: Flow<UByteArray>, isNotificationReply: Boolean): TranscriptionResult {
        if (!canServeSession()) return TranscriptionResult.Disabled
        val info = encoderInfo as? VoiceEncoderInfo.Speex ?: return TranscriptionResult.Error("Unsupported watch audio format.")
        if (info.frameSize !in 1..8192 || info.sampleRate !in 8000..48000)
            return TranscriptionResult.Error("Unsupported watch audio format.")
        return try {
            val pcm = withContext(Dispatchers.Default) {
                val codec = SpeexCodec(info.sampleRate, info.bitRate, info.frameSize)
                val frame = ByteArray(info.frameSize * 2)
                // Bound memory and recording duration independently of provider upload timeout.
                val buffer = ByteArray(2 * 1024 * 1024)
                var size = 0
                withTimeout(60_000) {
                    audioFrames.collect { encoded ->
                        ensureActive()
                        if (size + frame.size > buffer.size) throw SignalProviderException("Recording is too long.")
                        if (codec.decodeFrame(encoded.asByteArray(), frame, hasHeaderByte = true) != SpeexDecodeResult.Success)
                            throw SignalProviderException("Watch audio could not be decoded.")
                        frame.copyInto(buffer, size); size += frame.size
                    }
                }
                wave(buffer.copyOf(size), info.sampleRate.toInt())
            }
            val text = providers.transcribe(pcm)
            TranscriptionResult.Success(text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { TranscriptionWord(it, 1f) })
        } catch (_: TimeoutCancellationException) { TranscriptionResult.Error("Recording timed out.")
        } catch (e: CancellationException) { throw e
        } catch (e: SignalProviderException) { TranscriptionResult.Error(e.message ?: "Transcription failed.")
        } catch (_: Exception) { TranscriptionResult.Error("Transcription failed.") }
    }

    companion object {
        internal fun wave(pcm: ByteArray, rate: Int): ByteArray {
            require(rate > 0 && rate <= 192000 && pcm.size % 2 == 0)
            val result = ByteArray(44 + pcm.size)
            fun word(at: Int, value: Int, bytes: Int) { repeat(bytes) { result[at + it] = (value ushr (it * 8)).toByte() } }
            "RIFF".encodeToByteArray().copyInto(result); word(4, pcm.size + 36, 4)
            "WAVEfmt ".encodeToByteArray().copyInto(result, 8)
            word(16, 16, 4); word(20, 1, 2); word(22, 1, 2); word(24, rate, 4)
            word(28, rate * 2, 4); word(32, 2, 2); word(34, 16, 2)
            "data".encodeToByteArray().copyInto(result, 36); word(40, pcm.size, 4)
            pcm.copyInto(result, 44)
            return result
        }
    }
}
