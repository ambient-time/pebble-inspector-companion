package coredevices.pebble.signal

/** Invalidates delayed app-open callbacks across close, reconnect and provider changes. */
class SignalSessionGate {
    private var next = 0L
    private val tickets = mutableMapOf<String, Long>()
    fun open(watchId: String): Long = (++next).also { tickets[watchId] = it }
    fun accepts(watchId: String, ticket: Long) = tickets[watchId] == ticket
    fun close(watchId: String) { tickets.remove(watchId) }
    fun clear() { tickets.clear() }
}
