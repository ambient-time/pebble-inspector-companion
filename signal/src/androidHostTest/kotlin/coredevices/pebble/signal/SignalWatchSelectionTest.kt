package coredevices.pebble.signal

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class SignalWatchSelectionTest {
    @Test fun largePhoneSelectionCannotTruncateTheLastWatchKey() {
        val enabled = (1..200).map { "sensor.synthetic.$it" }.toSet() +
            (AndroidSignalStation.watchKeys - SignalWatchHistory.KEY) + SignalWatchHistory.KEY
        val packet = AndroidSignalStation.watchSourceSelection(enabled)
        assertEquals(AndroidSignalStation.watchKeys, packet.map { it.jsonPrimitive.content }.toSet())
        assertTrue(packet.toString().encodeToByteArray().size < 900)
        assertEquals(201 + AndroidSignalStation.watchKeys.size - 1, enabled.size)
        assertTrue(AndroidSignalStation.watchSourceSelection(setOf("device.battery")).isEmpty())
        assertFalse(AndroidSignalStation.watchSourceSelection(enabled - SignalWatchHistory.KEY)
            .any { it.jsonPrimitive.content == SignalWatchHistory.KEY })
    }
}
