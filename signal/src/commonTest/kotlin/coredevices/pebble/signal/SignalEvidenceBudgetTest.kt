package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalEvidenceBudgetTest {
    private val json = Json { encodeDefaults = true }
    private fun capture(id: String) = SignalRecord(id, "thread", 1234, "Capture", provider = "local", model = "", state = "ready", kind = "capture",
        sourceKeys = setOf("wifi", "device.battery"), observations = (0..199).map { index ->
            SignalObservation(if (index % 2 == 0) "wifi" else "device.battery", "phone", "reading-$index", "%", 1234, 1234,
                fields = mapOf("detail" to "x".repeat(180)))
        })

    @Test fun largeCaptureRetainsIdentityBothSourcesAndHonestCoverage() {
        val original = capture("large")
        assertTrue(json.encodeToString(original).encodeToByteArray().size > 64 * 1024)
        val excerpt = SignalEvidenceBudget.excerpts(listOf(original)).single()
        assertEquals(original.id, excerpt.id)
        assertEquals(original.createdAt, excerpt.createdAt)
        assertEquals(original.sourceKeys, excerpt.observations.map { it.key }.toSet())
        assertTrue(excerpt.observations.isNotEmpty())
        assertTrue(excerpt.observations.size < original.observations.size)
        assertEquals(original.observations.size, excerpt.coverage.sumOf { it.retained + it.omitted })
        assertTrue(json.encodeToString(excerpt).encodeToByteArray().size <= 64 * 1024)
        assertEquals(200, original.observations.size)
    }

    @Test fun comparisonPreservesBothLargeCapturesWithinOneBudget() {
        val results = SignalEvidenceBudget.excerpts(listOf(capture("a"), capture("b")))
        assertEquals(listOf("a", "b"), results.map { it.id })
        assertTrue(results.all { it.observations.isNotEmpty() && it.coverage.sumOf { c -> c.omitted } > 0 })
        assertTrue(results.sumOf { json.encodeToString(it).encodeToByteArray().size } <= 64 * 1024)
    }

    @Test fun impossibleSelectionFailsInsteadOfBecomingAnEmptyAttachment() {
        val row = capture("large").copy(observations = listOf(SignalObservation("wifi", "phone", "x".repeat(80000), collectedAt = 1)))
        assertFailsWith<SignalProviderException> { SignalEvidenceBudget.excerpts(listOf(row)) }
    }

    @Test fun smallCaptureIsUnchangedAndOriginalOmissionsSurviveCompaction() {
        val small = capture("small").copy(observations = capture("small").observations.take(1))
        assertEquals(small, SignalEvidenceBudget.excerpts(listOf(small)).single())
        val original = capture("large").copy(coverage = listOf(SignalSourceCoverage("wifi", 120, 100, 20), SignalSourceCoverage("bluetooth", 30, 0, 30)))
        val excerpt = SignalEvidenceBudget.excerpts(listOf(original)).single()
        assertEquals(120, excerpt.coverage.single { it.key == "wifi" }.observed)
        assertEquals(30, excerpt.coverage.single { it.key == "bluetooth" }.omitted)
        assertEquals(0, excerpt.coverage.single { it.key == "bluetooth" }.retained)
    }
}
