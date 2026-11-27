package coredevices.pebble.signal

import kotlin.test.*

/** On-phone models take 6,000 bytes for the whole conversation; scheduled evidence must fit. */
class SignalScheduledLocalEvidenceTest {
    private val keys = setOf("wifi")
    private val session = SignalObservationSession("run", 100, null, keys, mode = "fixed", intervalMinutes = 1,
        modelAnalysis = true, analysisProvider = "local-gemma", analysisModel = "local-file")
    private val instructions = "x".repeat(900) // About the size of the real answer instructions.
    private fun dense(id: String, at: Long) = SignalRecord(id, "observation:run", at, "Observation session sample",
        provider = "local", model = "", state = "ready", kind = "observation", sourceKeys = keys, sessionId = "run",
        observations = (0 until 64).map { SignalObservation("wifi", "phone", "observation=$it; ssid=Network $it; rssi=-${40 + it}", "", at, at, "fresh", id = "$id:wifi:$it") })

    @Test fun denseCapturesAreTrimmedToFitAnOnPhoneModel() {
        val a = dense("first", 100_000); val b = dense("second", 200_000)
        val hosted = SignalScheduledAnalysis.evidence(session, a, b, keys)
        assertTrue(hosted.encodeToByteArray().size + instructions.length > 6000, "fixture must be too large untrimmed")
        val local = SignalScheduledAnalysis.localEvidence(session, a, b, keys, instructions)
        SignalLocalModels.validate(listOf("system" to instructions, "user" to local)) // throws if over 6,000 bytes
        assertContains(local, "[first]"); assertContains(local, "[second]")
        assertContains(local, SignalScheduledAnalysis.question)
    }

    @Test fun smallCapturesAreSentWhole() {
        val a = dense("first", 100_000).copy(observations = dense("first", 100_000).observations.take(2))
        val b = dense("second", 200_000).copy(observations = dense("second", 200_000).observations.take(2))
        assertEquals(SignalScheduledAnalysis.evidence(session, a, b, keys), SignalScheduledAnalysis.localEvidence(session, a, b, keys, instructions))
    }
}
