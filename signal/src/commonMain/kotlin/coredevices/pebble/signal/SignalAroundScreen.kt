package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalAroundPage(state: SignalState, station: SignalStation, onSources: () -> Unit, onDetail: (String) -> Unit) {
    val ui = LocalSignalUiSession.current
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("signals" to "Signals", "places" to "Places", "devices" to "My devices").forEach { (id, label) ->
                FilterChip(selected = ui.aroundTab == id, onClick = { if (ui.aroundTab != id) station.stopLiveSignals(); ui.aroundTab = id }, label = { Text(label) })
            }
        }
        Box(Modifier.weight(1f)) {
            if (ui.aroundTab == "signals") {
                var saved by signalUiState("around.savedContext") { false }
                Column {
                    if (saved) TextButton(onClick = { saved = false }) { Text("Back to live signals") }
                    Box(Modifier.weight(1f)) {
                        if (saved) SignalPresencePage(station, state, onDetail, "signals")
                        else SignalLivePage(state, station, onSources, onSavedContext = { station.stopLiveSignals(); saved = true })
                    }
                }
            } else SignalPresencePage(station, state, onDetail, ui.aroundTab)
        }
    }
}
