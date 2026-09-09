package coredevices.pebble.signal

import kotlin.test.*

class SignalContextPresetsTest {
    @Test fun previewContainsOnlyAvailableRegisteredSources() {
        val preset = SignalContextPresets.all.first { it.id == "walking" }
        assertEquals(setOf("location"), preset.availableKeys(listOf(SignalSource("location", "Location", "Phone"), SignalSource("watch.motion", "Motion", "Watch", false), SignalSource("health.heart_rate", "Heart", "Health"))))
    }
    @Test fun presetsExcludeMicrophoneHealthAndRawIdentifiers() {
        SignalContextPresets.all.forEach { preset ->
            assertFalse(preset.keys.any { it.contains("microphone") || it.startsWith("health.") || it.endsWith("identifiers") })
        }
    }
}
