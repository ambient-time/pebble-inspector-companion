package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit release checks. Normal test runs never contact a service or read a key. */
class SignalLiveServiceTest {
    private fun enabled() = assumeTrue("Set SIGNAL_LIVE_SMOKE=1 for an authorized release check.", System.getenv("SIGNAL_LIVE_SMOKE") == "1")

    @Test fun openAiAnswer() = chat("openai", "OPENAI_API_KEY", "gpt-4.1-mini")
    @Test fun xAiAnswer() = chat("xai", "XAI_API_KEY", "grok-4.3")
    @Test fun geminiAnswer() = chat("gemini", "GEMINI_API_KEY", "gemini-3.6-flash")
    @Test fun openRouterAnswer() = chat("openrouter", "OPENROUTER_API_KEY", "openai/gpt-4.1-mini")
    @Test fun anthropicAnswer() = chat("anthropic", "ANTHROPIC_API_KEY", "claude-sonnet-4-5")

    private fun chat(provider: String, variable: String, defaultModel: String) = runBlocking {
        enabled()
        val key = System.getenv(variable)
        assumeTrue("No configured release-check credential for $provider.", !key.isNullOrBlank())
        val model = System.getenv("SIGNAL_LIVE_${provider.uppercase()}_MODEL") ?: defaultModel
        val http = HttpClient(OkHttp)
        val service = SignalProviders(http, object : SignalSecrets {
            override suspend fun get(provider: String) = key
            override suspend fun put(provider: String, key: String) = Unit
        })
        val start = System.nanoTime()
        try {
            val reply = service.answer(SignalSettings(provider = provider, model = model), listOf(
                "system" to "This is a synthetic software integration check. Reply briefly.",
                "user" to "Reply with exactly SIGNAL_OK. No explanation.",
            ))
            assertTrue(reply.text.isNotBlank(), "The actual answer path must return visible text.")
            println("LIVE_CHAT provider=$provider model=$model status=answer bytes=${reply.text.encodeToByteArray().size} elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
        } catch (error: SignalProviderException) {
            // Production exceptions contain fixed, sanitized messages; never print upstream bodies.
            println("LIVE_CHAT provider=$provider model=$model status=error message=${error.message}")
            throw error
        } finally { service.close(); http.close() }
    }

    @Test fun publicLandmarkAndSyntheticRadioLookups() = runBlocking {
        enabled()
        val http = HttpClient(OkHttp)
        val service = SignalLookupProviders(http)
        val now = System.currentTimeMillis()
        // Eiffel Tower: a public landmark, not a device reading or user's saved place.
        val location = SignalObservation("location", "public fixture", collectedAt = now, measuredAt = now, status = "fresh",
            fields = mapOf("latitude" to "48.8584", "longitude" to "2.2945", "accuracyMeters" to "10"))
        fun record(rows: List<SignalObservation>) = SignalRecord("public-service-fixture", "release-check", now, "Synthetic lookup check",
            provider = "local", model = "", state = "ready", kind = "capture", observations = rows, sourceKeys = rows.map { it.key }.toSet())
        fun settings(record: SignalRecord) = SignalSettings(enabled = record.sourceKeys + setOf("places.nearby", "location.radio"),
            lookups = SignalLookupSettings(nearbyPlaces = true, radioLocation = true, radiusMeters = 300))
        val results = mutableListOf<Result<Unit>>()
        try {
            val placeRecord = record(listOf(location))
            for (kind in listOf("nearby", "address")) {
                val outcome = runCatching {
                    val request = SignalLookupProviders.prepare(kind, placeRecord, settings(placeRecord), now)
                    val rows = service.lookup(request, now)
                    assertTrue(rows.any { it.status == "candidate" }, "Public landmark query must yield candidates.")
                    assertTrue(rows.filter { it.status == "candidate" }.all { it.fields["attribution"] == "© OpenStreetMap contributors" })
                    println("LIVE_LOOKUP kind=$kind status=candidates count=${rows.count { it.status == "candidate" }}")
                }
                results += outcome
                if (outcome.isFailure) {
                    println("LIVE_LOOKUP kind=$kind status=failed type=${outcome.exceptionOrNull()!!::class.simpleName}")
                    break // No second request to an unavailable/rate-limiting map service.
                }
            }
            results += runCatching {
                // Locally administered test identifiers cannot represent measured access points.
                val rows = (1..2).flatMap { slot -> listOf(
                    SignalObservation("wifi.identifiers", "synthetic fixture", collectedAt = now, measuredAt = now, status = "fresh",
                        fields = mapOf("slot" to "$slot", "bssid" to "02:00:00:00:00:0$slot")),
                    SignalObservation("wifi.names", "synthetic fixture", collectedAt = now, measuredAt = now, status = "fresh",
                        fields = mapOf("slot" to "$slot", "ssid" to "Signal Station synthetic test $slot")),
                ) }
                val radioRecord = record(rows)
                val request = SignalLookupProviders.prepare("radio_location", radioRecord, settings(radioRecord), now)
                val observed = service.lookup(request, now)
                assertTrue(observed.all { it.status == "not_found" }, "Unknown synthetic radios with IP fallback off must not produce a position.")
                println("LIVE_LOOKUP kind=radio_location status=not_found ipFallback=false")
            }.onFailure { println("LIVE_LOOKUP kind=radio_location status=failed type=${it::class.simpleName}") }
            results.forEach { it.getOrThrow() }
        } finally { service.close(); http.close() }
    }
}
