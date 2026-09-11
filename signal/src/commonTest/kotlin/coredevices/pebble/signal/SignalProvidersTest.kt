package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SignalProvidersTest {
    @Test fun encodedRequestLimitIncludesJsonEscapingForEveryProvider() = runBlocking {
        val http = HttpClient(MockEngine { error("Oversized request must not leave the phone") })
        val providers = SignalProviders(http, Keys())
        try {
            for (provider in listOf("openai", "xai", "anthropic", "gemini", "openrouter", "custom")) {
                val settings = SignalSettings(provider = provider, model = "test-model", endpoint = "https://example.test/v1")
                val messages = listOf("user" to "\"".repeat(140000))
                assertFailsWith<SignalProviderException> { providers.validateRequest(settings, messages) }
                assertFailsWith<SignalProviderException> { providers.answer(settings, messages) }
            }
        } finally { providers.close(); http.close() }
    }
    private class Keys : SignalSecrets {
        val requested = mutableListOf<String>()
        override suspend fun get(provider: String): String { requested += provider; return "private-test-key" }
        override suspend fun put(provider: String, key: String) = Unit
    }

    @Test fun allProviderFixturesUseTheirNativeWireFormat() = runBlocking {
        for (provider in listOf("openai", "xai", "anthropic", "gemini", "openrouter", "custom")) {
            val keys = Keys()
            val engine = MockEngine { request ->
                assertEquals(URLProtocol.HTTPS, request.url.protocol)
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("test-model", payload["model"]?.jsonPrimitive?.content ?: "test-model")
                when (provider) {
                    "openai", "xai" -> {
                        assertTrue(request.url.encodedPath.endsWith("/responses"))
                        assertEquals(false, payload["store"]?.jsonPrimitive?.boolean)
                        assertNotNull(payload["input"])
                    }
                    "anthropic" -> {
                        assertEquals("private-test-key", request.headers["x-api-key"])
                        assertEquals("2023-06-01", request.headers["anthropic-version"])
                        assertEquals("system instruction", payload["system"]?.jsonPrimitive?.content)
                        assertEquals(2, payload["messages"]?.jsonArray?.size)
                    }
                    "gemini" -> {
                        assertEquals("private-test-key", request.headers["x-goog-api-key"])
                        assertTrue(request.url.parameters.isEmpty())
                        assertNotNull(payload["systemInstruction"])
                        assertEquals("model", payload["contents"]?.jsonArray?.last()?.jsonObject?.get("role")?.jsonPrimitive?.content)
                    }
                    else -> assertTrue(request.url.encodedPath.endsWith("/chat/completions"))
                }
                respond(fixture(provider), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = HttpClient(engine)
            val providers = SignalProviders(http, keys)
            try {
                val reply = providers.answer(SignalSettings(provider = provider, model = "test-model", endpoint = "https://example.test/v1"),
                    listOf("system" to "system instruction", "user" to "question", "assistant" to "earlier answer"))
                assertEquals("Visible answer", reply.text)
                assertEquals(listOf(provider), keys.requested)
            } finally { providers.close(); http.close() }
        }
    }

    @Test fun errorsDoNotEchoProviderBodyOrRetry() = runBlocking {
        for (status in listOf(302, 401, 402, 403, 429, 500)) {
            var calls = 0
            val http = HttpClient(MockEngine {
                calls++
                respond("private-test-key private medical prompt", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.Location, "https://other.test"))
            })
            val providers = SignalProviders(http, Keys())
            try {
                val error = assertFailsWith<SignalProviderException> {
                    providers.answer(SignalSettings(), listOf("user" to "question"))
                }
                assertFalse(error.message.orEmpty().contains("private"))
                assertEquals(1, calls)
            } finally { providers.close(); http.close() }
        }
    }

    @Test fun cancellationPropagatesToTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        })
        val providers = SignalProviders(http, Keys())
        try {
            val job = launch { providers.answer(SignalSettings(), listOf("user" to "question")) }
            entered.await(); job.cancelAndJoin(); stopped.await()
            assertTrue(job.isCancelled)
        } finally { providers.close(); http.close() }
    }

    @Test fun paymentAndStringAuthenticationErrorsGiveTheRightRemedyWithoutEchoingTheBody() = runBlocking {
        val cases = listOf(
            Triple(402, """{"error":{"code":402,"message":"private billing details"}}""", "Check billing and usage"),
            Triple(400, """{"code":"invalid-argument","error":"Incorrect API key provided. private key details"}""", "Check the key and model access"),
            Triple(400, """{"code":"invalid-argument","error":"private unrelated request problem"}""", "Check the model and endpoint"),
        )
        for ((status, body, remedy) in cases) {
            var requests = 0
            val http = HttpClient(MockEngine { requests++; respond(body, HttpStatusCode.fromValue(status)) })
            val providers = SignalProviders(http, Keys())
            try {
                val error = assertFailsWith<SignalProviderException> { providers.answer(SignalSettings(provider = "xai"), listOf("user" to "Synthetic check")) }
                assertTrue(error.message.orEmpty().contains(remedy))
                assertFalse(error.message.orEmpty().contains("private"))
                assertEquals(1, requests)
            } finally { providers.close(); http.close() }
        }
    }

    @Test fun incompleteAnswersAreNotSavedAsCompleteTextOrMisreportedAsModelErrors() {
        val fixtures = listOf(
            "xai" to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning","summary":[]}]}""",
            "openai" to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"content":[{"type":"output_text","text":"Partial answer"}]}]}""",
            "anthropic" to """{"stop_reason":"max_tokens","content":[{"type":"text","text":"Partial answer"}]}""",
            "gemini" to """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"Partial answer"}]}}]}""",
            "openrouter" to """{"choices":[{"finish_reason":"length","message":{"content":"Partial answer"}}]}""",
            "custom" to """{"choices":[{"finish_reason":"length","message":{"content":"Partial answer"}}]}""",
        )
        fixtures.forEach { (provider, body) ->
            val error = assertFailsWith<SignalProviderException> { SignalProviders.parseAnswer(provider, Json.parseToJsonElement(body).jsonObject) }
            assertTrue(error.message.orEmpty().contains("output limit"), provider)
            assertFalse(error.message.orEmpty().contains("Partial answer"), provider)
        }
    }

    @Test fun blockedAnswersUseFixedMessagesWithoutProviderText() {
        val fixtures = listOf(
            "openai" to """{"status":"completed","output":[{"content":[{"type":"refusal","refusal":"private prompt"}]}]}""",
            "xai" to """{"status":"incomplete","incomplete_details":{"reason":"content_filter","message":"private prompt"}}""",
            "anthropic" to """{"stop_reason":"refusal","content":[{"type":"text","text":"private prompt"}]}""",
            "gemini" to """{"promptFeedback":{"blockReason":"SAFETY","blockReasonMessage":"private prompt"}}""",
            "gemini" to """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[{"text":"private prompt"}]}}]}""",
            "openrouter" to """{"choices":[{"finish_reason":"content_filter","message":{"content":"private prompt"}}]}""",
        )
        fixtures.forEach { (provider, body) ->
            val error = assertFailsWith<SignalProviderException> { SignalProviders.parseAnswer(provider, Json.parseToJsonElement(body).jsonObject) }
            assertTrue(error.message.orEmpty().contains("declined"), provider)
            assertFalse(error.message.orEmpty().contains("private"), provider)
        }
    }

    @Test fun unfinishedResponsesAndEmbeddedErrorsRemainErrors() {
        for (status in listOf("incomplete", "failed", "cancelled", "in_progress", "queued")) {
            val body = """{"status":"$status","output":[{"content":[{"type":"output_text","text":"Partial answer"}]}]}"""
            assertFailsWith<SignalProviderException> { SignalProviders.parseAnswer("xai", Json.parseToJsonElement(body).jsonObject) }
        }
        val body = """{"error":{"code":"private-key-and-prompt","message":"private-key-and-prompt"}}"""
        val error = assertFailsWith<SignalProviderException> { SignalProviders.parseAnswer("xai", Json.parseToJsonElement(body).jsonObject) }
        assertFalse(error.message.orEmpty().contains("private"))
    }

    @Test fun httpDiagnosticsIncludeOnlyStatusAndAllowlistedCodesWithoutRetry() = runBlocking {
        for ((status, code) in listOf(400 to "context_length_exceeded", 429 to "insufficient_quota", 404 to "model_not_found", 422 to "private-key-and-prompt")) {
            var calls = 0
            val http = HttpClient(MockEngine {
                calls++
                respond("""{"error":{"code":"$code","message":"private-key-and-prompt","param":"private-key-and-prompt"}}""", HttpStatusCode.fromValue(status))
            })
            val providers = SignalProviders(http, Keys())
            try {
                val error = assertFailsWith<SignalProviderException> { providers.answer(SignalSettings(), listOf("system" to "Return clear text.", "user" to "A question")) }
                assertTrue(error.message.orEmpty().contains("HTTP $status"))
                if (code != "private-key-and-prompt") assertTrue(error.message.orEmpty().contains(code))
                assertFalse(error.message.orEmpty().contains("private"))
                assertEquals(1, calls)
            } finally { providers.close(); http.close() }
        }
    }

    @Test fun transcriptionUsesOnlyItsOwnKeyAndMultipartWav() = runBlocking {
        val keys = Keys()
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://api.openai.com/v1/audio/transcriptions", request.url.toString())
            assertEquals("Bearer private-test-key", request.headers[HttpHeaders.Authorization])
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("gpt-transcribe"))
            assertTrue(body.contains("audio/wav"))
            assertTrue(body.contains("RIFF"))
            respond("""{"text":"A short question"}""", HttpStatusCode.OK)
        })
        val providers = SignalProviders(http, keys)
        try {
            assertEquals("A short question", providers.transcribe(SignalAudio.wave(byteArrayOf(0, 0), 16000)))
            assertEquals(listOf("transcription"), keys.requested)
        } finally { providers.close(); http.close() }
    }

    @Test fun oversizedAndMalformedResponsesFailClosed() = runBlocking {
        for (body in listOf("x".repeat(128 * 1024 + 2), "{", "{}")) {
            val http = HttpClient(MockEngine { respond(body) })
            val providers = SignalProviders(http, Keys())
            try { assertFailsWith<SignalProviderException> {
                providers.answer(SignalSettings(), listOf("user" to "question"))
            } } finally { providers.close(); http.close() }
        }
    }

    @Test fun customEndpointRejectsCredentialsAndCleartext() {
        for (url in listOf("http://example.test/v1", "https://user:secret@example.test/v1", "https://example.test/v1?key=secret", "https://example.test/v1#fragment"))
            assertFailsWith<SignalProviderException> { SignalProviders.customEndpoint(url) }
        assertEquals("https://example.test/v1/chat/completions", SignalProviders.customEndpoint("https://example.test/v1/"))
        assertEquals("https://example.test/v1/chat/completions", SignalProviders.customEndpoint("https://example.test/v1/chat/completions"))
    }

    @Test fun unicodeBoundsAndWavHeaderAreValid() {
        val original = "🛰️ café 漢字 ".repeat(3000)
        for (limit in listOf(900, 16384)) {
            val value = SignalProviders.truncateUtf8(original, limit)
            assertTrue(value.encodeToByteArray().size <= limit)
            assertFalse(value.contains('\uFFFD'))
            assertTrue(value.endsWith("…"))
        }
        val wav = SignalAudio.wave(byteArrayOf(1, 2, 3, 4), 16000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVEfmt ", wav.decodeToString(8, 16))
        assertEquals("data", wav.decodeToString(36, 40))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), wav.copyOfRange(44, 48))
    }

    private fun fixture(provider: String): String = when (provider) {
        "openai", "xai" -> """{"output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"Visible answer"}]}]}"""
        "anthropic" -> """{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"Visible answer"}]}"""
        "gemini" -> """{"candidates":[{"content":{"parts":[{"thought":true,"text":"hidden"},{"text":"Visible answer"}]}}]}"""
        else -> """{"choices":[{"message":{"content":"Visible answer"}}]}"""
    }
}
