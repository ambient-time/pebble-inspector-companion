package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalScheduledAnalysisTest {
    private val keys = setOf("device.battery")
    private val session = SignalObservationSession("run", 100, null, keys, mode = "fixed", intervalMinutes = 7,
        modelAnalysis = true, analysisProvider = "openai", analysisModel = "fixture-model")
    private fun sample(id: String, at: Long) = SignalRecord(id, "observation:run", at, "Observation session sample",
        provider = "local", model = "", state = "ready", kind = "observation", sourceKeys = keys, sessionId = "run",
        observations = listOf(SignalObservation("device.battery", "phone", "70", "%", at, at, "fresh", id = "$id:battery")))

    @Test fun oldSessionsRemainFiniteAndNewOngoingHasNoFakeEndDate() {
        val old = Json.decodeFromString<SignalObservationSession>("""{"id":"old","startedAt":100,"endsAt":1000,"sourceKeys":["device.battery"]}""")
        assertEquals(1000L, old.endsAt)
        assertFalse(old.modelAnalysis)
        assertFalse(old.localAnalysis)
        assertFalse(SignalSettings().observationModelAnalysis)
        val encoded = Json { encodeDefaults = true }.encodeToString(session)
        assertContains(encoded, "\"endsAt\":null")
        assertNull(Json.decodeFromString<SignalObservationSession>(encoded).endsAt)
        assertEquals(7, Json.decodeFromString<SignalObservationSession>(encoded).intervalMinutes)
    }

    @Test fun cadenceWaitsForTwoCapturesAndConsumesFailuresUntilNextSlot() {
        assertFalse(SignalScheduledAnalysis.due(1, 0, 1))
        assertFalse(SignalScheduledAnalysis.due(2, 0, 3))
        assertTrue(SignalScheduledAnalysis.due(3, 0, 3))
        assertFalse(SignalScheduledAnalysis.due(3, 3, 3))
        assertFalse(SignalScheduledAnalysis.due(5, 3, 3))
        assertTrue(SignalScheduledAnalysis.due(6, 3, 3))
        assertEquals(1, SignalScheduledAnalysis.cadence(0))
        assertEquals(60, SignalScheduledAnalysis.cadence(Int.MAX_VALUE))
    }

    @Test fun disabledOptInDeletedEvidenceChangedSourceAndOtherSessionCannotSend() {
        val a = sample("first", 100_000); val b = sample("second", 200_000)
        assertTrue(SignalScheduledAnalysis.valid(session, a, b, keys))
        assertFalse(SignalScheduledAnalysis.valid(session.copy(modelAnalysis = false), a, b, keys))
        assertFalse(SignalScheduledAnalysis.valid(session, null, b, keys))
        assertFalse(SignalScheduledAnalysis.valid(session, a, b, emptySet()))
        assertFalse(SignalScheduledAnalysis.valid(session, a.copy(sessionId = "other"), b, keys))
        assertFalse(SignalScheduledAnalysis.valid(session, a, b.copy(references = listOf("unrelated")), keys))
        assertFalse(SignalScheduledAnalysis.valid(session, a, b.copy(sourceKeys = keys + "location"), keys))
    }

    @Test fun explicitSessionRequestUsesOnlyBoundedTwoCapturePayloadWithMockTransport() = runBlocking {
        val a = sample("first", 100_000); val b = sample("second", 200_000)
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            val body = (request.body as TextContent).text
            assertContains(body, "first:battery"); assertContains(body, "second:battery")
            assertContains(body, "fixture-model"); assertContains(body, "sampled coverage")
            assertFalse(body.contains("memoryReferences\\\":{\\\""))
            assertTrue(body.encodeToByteArray().size < 80 * 1024)
            respond("""{"output":[{"content":[{"type":"output_text","text":"Compared first and second."}]}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val provider = SignalProviders(http, object : SignalSecrets {
            override suspend fun get(provider: String) = "fixture-only-key"
            override suspend fun put(provider: String, key: String) = Unit
        })
        try {
            val evidence = SignalScheduledAnalysis.evidence(session, a, b, keys)
            val reply = provider.answer(SignalSettings(provider = session.analysisProvider, model = session.analysisModel), listOf("user" to evidence))
            assertEquals(1, requests)
            assertEquals("Compared first and second.", reply.text)
        } finally { provider.close(); http.close() }
    }
}
