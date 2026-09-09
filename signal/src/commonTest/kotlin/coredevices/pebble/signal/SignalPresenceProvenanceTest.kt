package coredevices.pebble.signal

import kotlin.test.*

class SignalPresenceProvenanceTest {
    private fun record(id: String, refs: List<String> = emptyList(), value: String? = null) = SignalRecord(
        id, "thread", 10, "Saved record", answer = "Preserve this answer", provider = "local", model = "",
        references = refs, sourceKeys = setOf("presence.places"), state = "ready",
        observations = value?.let { listOf(SignalObservation("presence.places", "phone", it, collectedAt = 10)) }.orEmpty())

    @Test fun repairsLegacyClueAndEveryDerivedDescendantRegardlessOfOrder() {
        val leaf = record("leaf", listOf("middle"))
        val seed = record("seed", value = "Home: inside; chosen Wi-Fi name observed (name alone does not verify a place)")
        val middle = record("middle", listOf("seed"))
        val repaired = SignalPresenceProvenance.repair(listOf(leaf, seed, middle))
        assertEquals(listOf("leaf", "seed", "middle"), repaired.map { it.id })
        assertTrue(repaired.all { "presence.wifi" in it.sourceKeys })
        assertTrue(repaired.all { !SignalHistory.allowed(it, setOf("presence.places")) })
        assertEquals(seed.observations, repaired[1].observations)
        assertEquals(seed.answer, repaired[1].answer)
        assertEquals(leaf.references, repaired[0].references)
    }
    @Test fun ambiguousLegacyWifiIsRepairedButLocationOnlyRecordsStayUntouched() {
        val ambiguous = record("ambiguous", value = "Cafe: unknown; Wi-Fi name ambiguous")
        val plain = record("plain", value = "Home: inside")
        val unrelated = record("unrelated")
        val repaired = SignalPresenceProvenance.repair(listOf(ambiguous, plain, unrelated))
        assertTrue("presence.wifi" in repaired[0].sourceKeys)
        assertSame(plain, repaired[1])
        assertSame(unrelated, repaired[2])
    }
    @Test fun cyclesTerminateAndMissingReferencesAreNeitherInventedNorDropped() {
        val a = record("a", listOf("b", "deleted"), "Home: outside; chosen Wi-Fi name not observed")
        val b = record("b", listOf("a"))
        val missingOnly = record("orphan", listOf("missing"))
        val repaired = SignalPresenceProvenance.repair(listOf(a, b, missingOnly))
        assertEquals(3, repaired.size)
        assertEquals(listOf("b", "deleted"), repaired[0].references)
        assertTrue(repaired.take(2).all { "presence.wifi" in it.sourceKeys })
        assertSame(missingOnly, repaired[2])
        assertTrue(SignalHistory.retrieve(repaired, "", setOf("presence.places", "presence.wifi")).isEmpty())
    }
    @Test fun repairIsIdempotentAndCarriesExistingDependenciesToDescendants() {
        val seed = record("seed").copy(sourceKeys = setOf("presence.wifi"))
        val child = record("child", listOf("seed"))
        val once = SignalPresenceProvenance.repair(listOf(seed, child))
        val twice = SignalPresenceProvenance.repair(once)
        assertEquals(once, twice)
        assertSame(once[0], twice[0])
        assertSame(once[1], twice[1])
        assertTrue("presence.wifi" in once[1].sourceKeys)
    }
    @Test fun currentPlaceLabelMentioningWifiDoesNotGainDependency() {
        val current = record("current", value = "Wi-Fi workshop: inside").let {
            it.copy(observations = it.observations.map { row -> row.copy(identity = "fence:workshop") })
        }
        assertSame(current, SignalPresenceProvenance.repair(listOf(current)).single())
    }
    @Test fun longReverseOrderedChainPropagatesWithoutRecursiveStack() {
        val seed = record("0", value = "Home: inside; chosen Wi-Fi name observed")
        val chain = (1..3000).map { record(it.toString(), listOf((it - 1).toString())) }.reversed() + seed
        val repaired = SignalPresenceProvenance.repair(chain)
        assertEquals(chain.size, repaired.size)
        assertTrue(repaired.all { "presence.wifi" in it.sourceKeys })
    }
}
