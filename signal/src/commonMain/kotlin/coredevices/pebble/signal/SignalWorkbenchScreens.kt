package coredevices.pebble.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SignalCapturePage(
    state: SignalState,
    station: SignalStation,
    onDetail: (String) -> Unit,
    onSources: () -> Unit,
    onManageWatch: (() -> Unit)?,
    onFieldTest: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("presets"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Collection presets", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            SignalContextControls(state, station)
            TextButton(onClick = onSources) { Text("Choose individual sources") }
        }
    }

}


@Composable
internal fun SignalSourcesPage(state: SignalState, station: SignalStation, onSettings: () -> Unit) {
    var expanded by signalUiState("sources.expanded") { emptySet<String>() }
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("sources"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Sources", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Choose what goes into an observation. Every source is optional.")
            Text("${signalCount(state.settings.enabled.size, "source")} selected", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = station::requestPermissions, enabled = !state.busy) { Text("Review permissions") }
                TextButton(onClick = station::openPermissionSettings, enabled = !state.busy) { Text("Android permissions") }
            }
            Text("Weather may contact its data service during collection. Nearby-place and radio-location lookups require a separate review before sending.", style = MaterialTheme.typography.bodySmall)
        }
        state.sources.groupBy { it.group }.forEach { (group, sources) ->
            item(key = "group:$group") {
                val open = group in expanded
                OutlinedButton(onClick = { expanded = if (open) expanded - group else expanded + group },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }) {
                    Text("$group · ${sources.count { it.key in state.settings.enabled }}/${sources.size} selected")
                }
                if (open) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { station.updateSettings(state.settings.copy(enabled = state.settings.enabled + sources.filter { it.available && it.key != SignalWatchHistory.KEY }.map { it.key })) }, enabled = !state.busy) {
                        Text(if (sources.any { it.key == SignalWatchHistory.KEY }) "Enable other sources" else "Enable $group")
                    }
                    TextButton(onClick = { station.updateSettings(state.settings.copy(enabled = state.settings.enabled - sources.map { it.key }.toSet())) }, enabled = !state.busy) { Text("Disable $group") }
                }
            }
            if (group in expanded) items(sources, key = { "source:${it.key}" }) { source ->
                SignalToggle(source.name, source.key in state.settings.enabled, !state.busy,
                    when {
                        source.key == SignalWatchHistory.KEY -> SignalWatchHistory.description
                        source.available -> null
                        else -> "Currently unavailable on this device"
                    }) { enabled ->
                    station.updateSettings(state.settings.copy(enabled = if (enabled) state.settings.enabled + source.key else state.settings.enabled - source.key))
                }
                state.sourceStatus.firstOrNull { it.key == source.key && source.key in state.settings.enabled }?.let {
                    SignalSourceStatusRow(it, state, station, onSettings)
                }
            }
        }
        item {
            HorizontalDivider()
            TextButton(onClick = onSettings) { Text("Health access and weather location") }
            Text("Turning a source off stops future collection and model use. Its saved readings remain in History until you delete them.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
