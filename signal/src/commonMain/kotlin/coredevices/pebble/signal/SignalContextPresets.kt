package coredevices.pebble.signal

/** Presets select sources only. They never collect, grant permissions, or start listening. */
data class SignalContextPreset(val id: String, val name: String, val keys: Set<String>) {
    fun availableKeys(sources: List<SignalSource>): Set<String> =
        sources.filter { it.available && it.key in keys }.map { it.key }.toSet()
}

object SignalContextPresets {
    val all = listOf(
        SignalContextPreset("phone_basics", "Phone basics", setOf("device.battery", "device.time")),
        SignalContextPreset("radio_signals", "Radio signals", setOf("wifi", "bluetooth")),
        SignalContextPreset("walking", "Walking", setOf("location", "device.battery", "device.time", "watch.motion", "weather.current", "weather.daylight")),
        SignalContextPreset("home", "At home", setOf("device.battery", "device.time", "presence.bluetooth", "presence.wifi", "presence.places")),
        SignalContextPreset("travel", "Travel", setOf("location", "device.battery", "device.time", "device.network", "wifi", "wifi.names", "presence.places", "weather.current", "weather.forecast")),
    )
}
