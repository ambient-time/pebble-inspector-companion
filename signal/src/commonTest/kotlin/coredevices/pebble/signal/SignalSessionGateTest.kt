package coredevices.pebble.signal

import kotlin.test.*

class SignalSessionGateTest {
    @Test fun delayedOpenCannotRestoreClosedWatch() {
        val gate = SignalSessionGate()
        val pending = gate.open("watch")
        gate.close("watch")
        assertFalse(gate.accepts("watch", pending))
    }
    @Test fun reconnectRejectsPreviousSessionButPreservesOtherWatch() {
        val gate = SignalSessionGate()
        val old = gate.open("a")
        val other = gate.open("b")
        val fresh = gate.open("a")
        assertFalse(gate.accepts("a", old))
        assertTrue(gate.accepts("a", fresh))
        assertTrue(gate.accepts("b", other))
    }
    @Test fun changingHostInvalidatesEveryPendingOpen() {
        val gate = SignalSessionGate()
        val first = gate.open("a")
        val second = gate.open("b")
        gate.clear()
        assertFalse(gate.accepts("a", first))
        assertFalse(gate.accepts("b", second))
        assertNotEquals(first, gate.open("a"))
    }
}
