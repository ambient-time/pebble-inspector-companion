package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalSourcePreview(sources: List<SignalSource>, enabled: Set<String>, heading: String = "Sources for the next capture") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (enabled.isEmpty()) Text("No sources selected.")
        sources.filter { it.key in enabled }.forEach { source ->
            Text("• ${source.name}${if (source.available) "" else " · currently unavailable"}", style = MaterialTheme.typography.bodySmall)
        }
        (enabled - sources.map { it.key }.toSet()).sorted().forEach { Text("• $it · currently unavailable", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SignalContextControls(state: SignalState, station: SignalStation) {
    var selected by remember { mutableStateOf("") }
    val preset = SignalContextPresets.all.firstOrNull { it.id == selected }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Context presets", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text("Preview a preset, then apply its source choices. Your individual switches remain available in Settings.")
        SignalChoice("Preview preset", selected, listOf("" to "Current choices") + SignalContextPresets.all.map { it.id to it.name }, !state.busy) { selected = it }
        val keys = preset?.availableKeys(state.sources) ?: state.settings.enabled
        SignalSourcePreview(state.sources, keys)
        if (preset != null) {
            val unavailable = preset.keys - keys
            if (unavailable.isNotEmpty()) Text("${unavailable.size} preset sources are unavailable and will stay off.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { station.updateSettings(state.settings.copy(enabled = keys)); selected = "" }, enabled = !state.busy) { Text("Apply ${preset.name}") }
            Text("Replaces current source choices. Does not capture readings or start the microphone.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
