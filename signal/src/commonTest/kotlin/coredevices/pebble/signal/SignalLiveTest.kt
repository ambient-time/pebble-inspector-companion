package coredevices.pebble.signal

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SignalLiveTest {
    private fun frame(at: Long, strength: Int = -55, address: String = "secret-address") = SignalAcquisition(emptyList(), listOf(
        SignalRadioCandidate("bluetooth", address, "private-name", strength, at, "fresh", metadata = "private-service")))
    @Test fun cachedSamplesDoNotInventMovementOrNewDevices() {
        val r = SignalLiveReducer()
        val first = r.accept(frame(1000), setOf("bluetooth"), 1000)
        val repeat = r.accept(frame(1000, -90), setOf("bluetooth"), 2000)
        assertEquals(1, first.second); assertEquals(0, repeat.second)
        assertEquals(first.first.single(), repeat.first.single())
        assertFalse(r.current(16001).single().fresh(16001))
        assertTrue(r.current(61001).isEmpty())
    }
    @Test fun consentAndSnapshotStripOptionalMetadata() {
        val r = SignalLiveReducer()
        val all = setOf("bluetooth", "bluetooth.names", "bluetooth.identifiers", "bluetooth.services")
        assertEquals("private-name", r.accept(frame(1000), all, 1000).first.single().label)
        val entry = r.accept(frame(1000), setOf("bluetooth"), 1000).first.single()
        assertEquals("", entry.address); assertEquals("", entry.metadata); assertEquals("", entry.advertisedName)
        val rows = SignalLive.snapshot(SignalLiveState(entries = listOf(entry)), setOf("bluetooth"), 1000)
        assertEquals(setOf("bluetooth"), rows.map { it.key }.toSet())
        assertFalse(rows.toString().contains("private")); assertFalse(rows.toString().contains("secret"))
        assertFailsWith<SignalProviderException> { SignalLive.snapshot(SignalLiveState(entries = listOf(entry)), all, 16001) }
        assertTrue(SignalLive.sources(setOf("bluetooth.names", "location", "presence.wifi")).isEmpty())
    }
    @Test fun historyAndEntriesAreBounded() {
        val r = SignalLiveReducer()
        repeat(150) { r.accept(frame(it.toLong(), address = "a$it"), setOf("bluetooth"), it.toLong()) }
        assertEquals(128, r.current(150).size)
        repeat(20) { r.accept(frame(200L + it, address = "last"), setOf("bluetooth"), 200L + it) }
        assertEquals(12, r.current(220).first { it.lastSeenAt == 219L }.samples.size)
        assertEquals("strong", SignalLive.band(-60)); assertEquals("medium", SignalLive.band(-75)); assertEquals("faint", SignalLive.band(-76))
        assertEquals("strengthening", SignalLive.trend(listOf(-90, -80, -60)))
        assertEquals("unknown", SignalLive.trend(listOf(-90)))
    }
    @Test fun foregroundStopCancelsInflightAndDoesNotResume() = runTest {
        var foreground = true
        var calls = 0
        val session = SignalLiveSession(this, { _, _ -> calls++; delay(5000); frame(1000) }, { foreground }, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime }, {})
        session.start(SignalSettings(enabled = setOf("bluetooth")))
        runCurrent(); assertEquals(1, calls)
        foreground = false; advanceTimeBy(1001); runCurrent()
        assertFalse(session.state.running)
        foreground = true; advanceTimeBy(10000); runCurrent()
        assertEquals(1, calls); assertTrue(session.state.entries.isEmpty())
    }
    @Test fun fiveMinuteLimitAndWifiCadenceAreEnforced() = runTest {
        val active = mutableListOf<Long>()
        val session = SignalLiveSession(this, { _, wifi -> if(wifi) active.add(testScheduler.currentTime); frame(1000 + testScheduler.currentTime) }, { true }, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime }, {})
        session.start(SignalSettings(enabled = setOf("wifi", "bluetooth")))
        advanceTimeBy(300001); runCurrent()
        assertFalse(session.state.running); assertTrue(session.state.status.contains("Five-minute"))
        assertTrue(active.zipWithNext().all { (a,b) -> b-a >= 35000 })
        assertTrue(active.size in 1..9)
    }
}
