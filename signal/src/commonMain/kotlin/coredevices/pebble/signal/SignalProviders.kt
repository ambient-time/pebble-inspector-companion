package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Error messages are deliberately fixed: upstream bodies and URLs may contain private input. */
class SignalProviderException(message: String) : Exception(message)

class SignalProviders(http: HttpClient, private val secrets: SignalSecrets) {
    // Redirects must never forward credentials or private prompts to a different destination.
    // Reuse only transport, not upstream logging, authentication, retries or default headers.
    private val client = HttpClient(http.engine) {
        followRedirects = false
        expectSuccess = false
        // Non-streaming model replies may remain silent while reasoning. Override the
        // transport's short default read timeout; the operation still has a hard deadline.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    fun close() = client.close()

    /** Local preflight uses the same encoding that will be transmitted. */
    fun validateRequest(settings: SignalSettings, messages: List<Pair<String, String>>): Int =
        validateEncodedRequest(request(settings, messages).second)

    private fun validateEncodedRequest(body: JsonObject): Int {
        val bytes = body.toString().encodeToByteArray().size
        if (bytes > 256 * 1024) fail("The selected evidence is too large for one request. Choose fewer captures or start a new conversation. Nothing was sent.")
        return bytes
    }

    suspend fun transcriptionConfigured(): Boolean = try { !secrets.get("transcription").isNullOrBlank() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }

    suspend fun answer(settings: SignalSettings, messages: List<Pair<String, String>>): SignalReply =
        guarded("Answer request", 60_000) {
            if (messages.isEmpty() || messages.any { it.first !in setOf("system", "user", "assistant") })
                fail("Conversation is invalid.")
            if (messages.sumOf { it.second.encodeToByteArray().size.toLong() } > 256 * 1024)
                fail("Conversation is too large. Start a new thread.")
            if (settings.model.isBlank()) fail("Choose a model in Settings.")
            val key = secrets.get(settings.provider)?.takeIf { it.isNotBlank() }
                ?: fail("Add a key for the selected provider in Settings.")
            val request = request(settings, messages)
            validateEncodedRequest(request.second)
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
            val body = readBounded(response.bodyAsChannel())
            checkStatus(response.status.value, body)
            parseJson(body)
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
            val responseBody = readBounded(response.bodyAsChannel())
            checkStatus(response.status.value, responseBody)
            parseJson(responseBody)
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
            if (root["error"] != null && root["error"] != JsonNull)
                fail("The provider could not complete this request.${errorCode(root)?.let { " ($it)" }.orEmpty()}")
            checkCompletion(provider, root)
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

        private fun checkCompletion(provider: String, root: JsonObject) {
            fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
            fun incomplete(): Nothing = fail("The model reached its output limit before finishing the answer. No automatic retry was made.")
            fun declined(): Nothing = fail("The provider declined this question. Try rephrasing it.")
            when (provider) {
                "openai", "xai" -> {
                    if (root.text("status") == "incomplete") {
                        when ((root["incomplete_details"] as? JsonObject)?.text("reason")) {
                            "max_output_tokens" -> incomplete()
                            "content_filter" -> declined()
                            else -> fail("The provider returned an unfinished answer. No automatic retry was made.")
                        }
                    }
                    if (root.text("status") in setOf("failed", "cancelled", "in_progress", "queued"))
                        fail("The provider did not complete the answer. No automatic retry was made.")
                    if ((root["output"] as? JsonArray).orEmpty().any { item ->
                        (((item as? JsonObject)?.get("content")) as? JsonArray).orEmpty().any { block ->
                            (block as? JsonObject)?.text("type") == "refusal"
                        }
                    }) declined()
                }
                "anthropic" -> when (root.text("stop_reason")) {
                    "max_tokens" -> incomplete()
                    "refusal" -> declined()
                    else -> Unit
                }
                "gemini" -> {
                    val blocked = (root["promptFeedback"] as? JsonObject)?.text("blockReason")
                    if (blocked != null && blocked != "BLOCK_REASON_UNSPECIFIED") declined()
                    when (((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("finishReason")) {
                        "MAX_TOKENS" -> incomplete()
                        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> declined()
                    }
                }
                "openrouter", "custom" -> when (((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("finish_reason")) {
                    "length" -> incomplete()
                    "content_filter" -> declined()
                    else -> Unit
                }
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

        private fun errorCode(root: JsonObject): String? {
            // xAI also returns a string error for invalid keys. Classify this known
            // envelope without including any upstream text in the displayed error.
            if ((root["code"] as? JsonPrimitive)?.contentOrNull == "invalid-argument" &&
                (root["error"] as? JsonPrimitive)?.contentOrNull?.startsWith("Incorrect API key provided.") == true)
                return "invalid_api_key"
            val error = root["error"] as? JsonObject ?: return null
            val allowed = setOf("invalid_api_key", "authentication_error", "permission_error", "model_not_found",
                "insufficient_quota", "rate_limit_exceeded", "rate_limit_error", "context_length_exceeded",
                "invalid_request_error", "unsupported_parameter", "content_policy_violation", "server_error",
                "overloaded_error", "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
                "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL")
            return listOf("code", "type", "status").mapNotNull { (error[it] as? JsonPrimitive)?.contentOrNull }
                .firstOrNull { it in allowed }
        }

        private fun checkStatus(status: Int, body: String) {
            if (status in 200..299) return
            val code = runCatching { errorCode(Json.parseToJsonElement(body).jsonObject) }.getOrNull()
            val message = when {
                code == "context_length_exceeded" -> "The conversation exceeds this model's context limit. Start a new conversation."
                status == 402 || code == "insufficient_quota" -> "Provider credit or quota is exhausted. Check billing and usage."
                status == 401 || status == 403 || code in setOf("invalid_api_key", "authentication_error", "UNAUTHENTICATED") -> "Provider authentication failed. Check the key and model access."
                status == 429 -> "Provider limit reached. Check usage before trying again."
                status in 300..399 -> "Provider redirect refused. Check the endpoint."
                status == 400 || status == 404 || status == 422 -> "Provider rejected the request. Check the model and endpoint."
                else -> "The provider is unavailable. Try again later."
            }
            fail("$message (HTTP $status${code?.let { "; $it" }.orEmpty()})")
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
