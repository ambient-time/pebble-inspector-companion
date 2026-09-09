package coredevices.pebble.signal

import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalBeaconTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val ibeacon = bytes("0215e2c56db5dffb48d2b060d0f5a71096e000010002c5")
    private val altbeacon = bytes("beac000102030405060708090a0b0c0d0e0f10111213c500")

    @Test fun parsesIBeaconWithUnsignedBigEndianIdentifiers() {
        val beacon = assertNotNull(SignalBeacon.parse(0x004c, ibeacon))
        assertEquals("ibeacon:e2c56db5dffb48d2b060d0f5a71096e0:1:2", beacon.identity)
        assertTrue(SignalBeacon.validIdentity(beacon.identity))
        val maximum = ibeacon.copyOf().also { for (i in 18..21) it[i] = 0xff.toByte() }
        assertTrue(assertNotNull(SignalBeacon.parse(0x004c, maximum)).identity.endsWith(":65535:65535"))
    }
    @Test fun parsesAltBeaconAndSeparatesManufacturers() {
        val first = assertNotNull(SignalBeacon.parse(0x0118, altbeacon))
        assertEquals("altbeacon:0118:000102030405060708090a0b0c0d0e0f10111213", first.identity)
        assertTrue(SignalBeacon.validIdentity(first.identity))
        assertNotEquals(first.identity, SignalBeacon.parse(0x004c, altbeacon)?.identity)
    }
    @Test fun malformedAndUnrecognizedPayloadsNeverBecomeIdentities() {
        for (size in 0 until ibeacon.size) assertNull(SignalBeacon.parse(0x004c, ibeacon.copyOf(size)))
        for (size in 0 until altbeacon.size) assertNull(SignalBeacon.parse(0x0118, altbeacon.copyOf(size)))
        assertNull(SignalBeacon.parse(1, ibeacon))
        assertNull(SignalBeacon.parse(0x004c, ibeacon + 0.toByte()))
        assertNull(SignalBeacon.parse(-1, altbeacon))
        assertFalse(SignalBeacon.validIdentity("ibeacon:${"0".repeat(32)}:65536:1"))
        assertFalse(SignalBeacon.validIdentity("ibeacon:${"0".repeat(32)}:1:2; forged"))
    }
    @Test fun metadataUsesAdvertisedLabelsAndPreservesUnknownService() {
        val value = SignalBeacon.describe(mapOf(0x004c to ibeacon), listOf("0000180d-0000-1000-8000-00805f9b34fb", "unknown-uuid"))
        assertTrue(value.description.contains("iBeacon advertisement"))
        assertTrue(value.description.contains("Advertised company IDs: 0x004c"))
        assertTrue(value.description.contains("Heart Rate (180d)"))
        assertTrue(value.description.contains("unknown-uuid"))
        val ambiguous = SignalBeacon.describe(mapOf(0x004c to ibeacon, 0x0118 to altbeacon), emptyList())
        assertEquals("", ambiguous.identity)
        assertTrue(ambiguous.description.contains("Multiple beacon formats"))
    }
    @Test fun legacyAddressTargetsDecodeAndBeaconTargetsSurviveAddressChanges() {
        val target = Json.decodeFromString<SignalPresenceTarget>("""{"id":"desk","radio":"bluetooth","address":"AA","label":"Desk"}""")
        assertEquals("", target.beaconId)
        val identity = assertNotNull(SignalBeacon.parse(0x004c, ibeacon)).identity
        val sample = SignalRadioCandidate("bluetooth", "BB", "", -50, 100, "fresh", beaconId = identity)
        assertFalse(SignalPresence.matches(target, sample))
        assertTrue(SignalPresence.matches(target.copy(beaconId = identity), sample))
        assertFalse(SignalPresence.matches(target.copy(beaconId = identity), sample.copy(address = "AA", beaconId = "")))
        assertFalse(SignalPresence.matches(target.copy(beaconId = identity), sample.copy(radio = "wifi")))
    }
    @Test fun duplicateBeaconIdsAreAmbiguousAndNeverExposeIdentityInHistory() {
        val identity = assertNotNull(SignalBeacon.parse(0x004c, ibeacon)).identity
        val target = SignalPresenceTarget("desk", "bluetooth", "AA", "Desk", beaconId = identity)
        val first = SignalRadioCandidate("bluetooth", "AA", "", -50, 100, "fresh", beaconId = identity)
        val rows = SignalPresence.observations(setOf("presence.bluetooth"), listOf(target), emptyList(), listOf(first, first.copy(address = "BB")), mapOf("bluetooth" to "fresh"), null, 100)
        assertEquals("ambiguous", rows.last().status)
        assertFalse(rows.any { identity in it.value || "AA" in it.value || "BB" in it.value })
    }
    @Test fun medianBoundsSamplesDeduplicatesTimestampsAndExpiresReadings() {
        val window = SignalRadioWindow()
        window.add(100, -50); window.add(101, -51); window.add(102, -100); window.add(102, -1)
        window.add(103, 127)
        assertEquals(3 to -51, window.summary(103))
        assertEquals(0 to null, window.summary(20_000))
        assertEquals(0 to null, window.summary(99))
        (200L..240L).forEach { window.add(it, -60) }
        assertEquals(32 to -60, window.summary(240))
    }
}
