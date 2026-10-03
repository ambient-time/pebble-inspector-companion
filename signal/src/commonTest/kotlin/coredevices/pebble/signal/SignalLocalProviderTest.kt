package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlin.test.*

class SignalLocalProviderTest {
    private val messages = listOf("system" to "Use only the reviewed readings.", "user" to "Synthetic question")
    private class NoKeys : SignalSecrets {
        var reads = 0
        override suspend fun get(provider: String): String? { reads++; error("Local requests must not read cloud credentials") }
        override suspend fun put(provider: String, key: String) = Unit
    }
    @Test fun successAndTerminalFailuresNeverUseCloudOrCredentials() = runBlocking {
        for (source in SignalLocalModels.providers) for (mode in listOf("success", "unavailable", "empty", "oversized", "unexpected")) {
            var network = 0; var local = 0
            val http = HttpClient(MockEngine { network++; error("No network allowed") })
            val keys = NoKeys()
            val providers = SignalProviders(http, keys) { provider, context ->
                local++; assertEquals(source, provider); assertEquals(messages, context)
                when (mode) {
                    "success" -> "Local answer"
                    "unavailable" -> throw SignalProviderException("Not ready")
                    "unexpected" -> error("private native error")
                    "oversized" -> "x".repeat(16385)
                    else -> ""
                }
            }
            try {
                val config = SignalSettings(provider = source, model = "local")
                if (mode == "success") assertEquals("Local answer", providers.answer(config, messages).text)
                else assertFalse(assertFailsWith<SignalProviderException> { providers.answer(config, messages) }.message.orEmpty().contains("private"))
                assertEquals(1, local); assertEquals(0, network); assertEquals(0, keys.reads)
            } finally { providers.close(); http.close() }
        }
    }
    @Test fun localHomeToolsCannotBeEnabledByManualOverride() {
        for (source in SignalLocalModels.providers) {
            val settings = SignalSettings(provider = source, model = "local", homeToolsVerifiedFor = "$source|local|")
            assertFalse(signalSupportsHomeTools(settings))
        }
    }
    @Test fun preflightAndDispatchRejectSameOversizedContextWithoutTrimming() = runBlocking {
        val http = HttpClient(MockEngine { error("No network") })
        val keys = NoKeys()
        val providers = SignalProviders(http, keys) { _, _ -> error("Must reject before inference") }
        try {
            val config = SignalSettings(provider = "local-gemma", model = "local-file")
            val large = listOf("user" to "é".repeat(3000))
            assertEquals(assertFailsWith<SignalProviderException> { providers.validateRequest(config, large) }.message,
                assertFailsWith<SignalProviderException> { providers.answer(config, large) }.message)
            assertEquals(0, keys.reads)
        } finally { providers.close(); http.close() }
    }
    @Test fun cancellationReachesLocalEngineWithoutFallback() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { error("No network") })
        val keys = NoKeys()
        val providers = SignalProviders(http, keys) { _, _ -> entered.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
        try {
            val job = launch { providers.answer(SignalSettings(provider = "local-nano", model = "android-system"), messages) }
            entered.await(); job.cancelAndJoin(); stopped.await()
            assertTrue(job.isCancelled); assertEquals(0, keys.reads)
        } finally { providers.close(); http.close() }
    }
}
