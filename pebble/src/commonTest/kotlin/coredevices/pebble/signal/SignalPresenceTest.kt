package coredevices.pebble.signal

import kotlin.test.*

class SignalPresenceTest {
    private val now = 100_000L
    private val target = SignalPresenceTarget("desk", "wifi", "AA", "Desk")
    private val fence = SignalPlaceFence("home", "Home", 0.0, 0.0, wifiSsid = "Cafe")
    private fun sample(address: String = "AA", status: String = "fresh", time: Long = now) = SignalRadioCandidate("wifi", address, "Cafe", -55, time, status)
    @Test fun freshCachedAndMissingRemainDistinct() {
        fun state(samples: List<SignalRadioCandidate>, coverage: String) = SignalPresence.observations(setOf("presence.wifi"), listOf(target), emptyList(), samples, mapOf("wifi" to coverage), null, now).last().status
        assertEquals("observed", state(listOf(sample()), "fresh"))
        assertEquals("cached", state(listOf(sample(status = "cached")), "cached"))
        assertEquals("cached", state(listOf(sample(time = 1)), "fresh"))
        assertEquals("not_observed", state(emptyList(), "fresh"))
        assertEquals("unknown", state(emptyList(), "permission_denied"))
    }
    @Test fun disabledSourceAndTargetProduceNoTargetHistory() {
        assertTrue(SignalPresence.observations(emptySet(), listOf(target), listOf(fence), listOf(sample()), emptyMap(), null, now).isEmpty())
        assertEquals(1, SignalPresence.observations(setOf("presence.wifi"), listOf(target.copy(enabled = false)), emptyList(), listOf(sample()), mapOf("wifi" to "fresh"), null, now).size)
    }
    @Test fun wifiNameIsAmbiguousAndCannotEstablishPlace() {
        val rows = SignalPresence.observations(setOf("presence.places", "presence.wifi"), emptyList(), listOf(fence), listOf(sample(), sample("BB")), mapOf("wifi" to "fresh"), null, now)
        assertEquals("unknown", rows.last().status)
        assertTrue(rows.last().value.contains("ambiguous"))
        assertFalse(rows.any { it.value.contains("AA") || it.value.contains("BB") })
    }
    @Test fun emptySavedPlacesStillDescribeCoverage() {
        val rows = SignalPresence.observations(setOf("presence.places"), emptyList(), emptyList(), emptyList(), emptyMap(), null, now)
        assertEquals("unknown", rows.single().status)
        assertEquals("presence.places", rows.single().key)
    }
    @Test fun securityNeverTreatsUnknownFlagsAsOpen() {
        assertTrue(SignalPresence.wifiSecurity("[ESS][WPS]").startsWith("open advertised"))
        assertEquals("security advertised", SignalPresence.wifiSecurity("[WAPI-PSK][ESS]"))
        assertEquals("security advertised", SignalPresence.wifiSecurity("[OWE_TRANSITION][ESS]"))
        assertEquals("unknown", SignalPresence.wifiSecurity("[FUTURE][ESS]"))
        assertEquals("unknown", SignalPresence.wifiSecurity(""))
    }
    @Test fun geofenceRequiresFreshAccurateFixAndAccountsForBoundary() {
        val fix = SignalPresenceFix(0.0, 0.0, 10.0, now)
        assertEquals("inside", SignalPresence.fenceState(fence, fix, now))
        assertEquals("boundary_uncertain", SignalPresence.fenceState(fence, fix.copy(accuracyMeters = 200.0), now))
        assertEquals("outside", SignalPresence.fenceState(fence, fix.copy(latitude = 0.01), now))
        assertEquals("unknown", SignalPresence.fenceState(fence, fix.copy(measuredAt = 1), now))
        assertEquals("unknown", SignalPresence.fenceState(fence, fix.copy(accuracyMeters = Double.NaN), now))
        assertEquals("unknown", SignalPresence.fenceState(fence, null, now))
    }
}
