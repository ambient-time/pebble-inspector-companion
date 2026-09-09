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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SignalSetup(station: SignalStation, state: SignalState) {
    var step by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf(state.settings.enabled) }
    var waitingForSources by remember { mutableStateOf(false) }
    BackHandler(enabled = step > 0 && !waitingForSources) { step-- }
    LaunchedEffect(state.settings.enabled, waitingForSources) {
        if (waitingForSources && state.settings.enabled == selected) {
            waitingForSources = false
            step = 2
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalAntennaGlyph()
                Text("Signal Station", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            }
            Text("Set up your field notebook", style = MaterialTheme.typography.titleMedium)
            Text("${step + 1} of 3", style = MaterialTheme.typography.labelLarge)
        }
        when (step) {
            0 -> {
                item {
                    Text("Capture a moment.", style = MaterialTheme.typography.headlineMedium)
                    Text("Keep phone and watch readings together in a local history. Review the evidence here, or ask a model to help interpret it.")
                }
                item {
                    Text("You choose each source. Captures are stored on this phone until you delete them. Model analysis uses your own provider key and only happens when requested.")
                    Text("Weather sources contact their data services when enabled. Phone and watch measurements are collected for requested captures and presence checks. Optional wake listening runs locally until stopped and is off by default.")
                }
                item {
                    Button(onClick = { step = 1 }) { Text("Choose sources") }
                    TextButton(onClick = { station.updateSettings(state.settings.copy(onboardingComplete = true)) }, enabled = !state.busy) { Text("Set up later") }
                }
            }
            1 -> {
                item {
                    Text("What would you like to collect?", style = MaterialTheme.typography.headlineMedium)
                    Text("Every source is optional. You can change these choices in Settings. No provider key is needed to capture.")
                    Button(onClick = {
                        waitingForSources = true
                        station.updateSettings(state.settings.copy(enabled = selected))
                    }, enabled = !waitingForSources && !state.busy) { Text(if (waitingForSources) "Saving…" else "Continue with these choices") }
                    Text("${signalCount(selected.size, "source")} selected", style = MaterialTheme.typography.bodySmall)
                }
                state.sources.groupBy { it.group }.forEach { (group, sources) ->
                    item(key = "group:$group") {
                        Text(group, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = selected + sources.filter { it.available }.map { it.key } }, enabled = !waitingForSources) { Text("Enable $group") }
                            TextButton(onClick = { selected = selected - sources.map { it.key }.toSet() }, enabled = !waitingForSources) { Text("Disable $group") }
                        }
                    }
                    items(sources, key = { it.key }) { source ->
                        SignalToggle(source.name, source.key in selected, !waitingForSources, if (source.available) null else "Currently unavailable") { checked ->
                            selected = if (checked) selected + source.key else selected - source.key
                        }
                    }
                }
                item {
                    Text("${signalCount(selected.size, "source")} selected")
                    Button(onClick = {
                        waitingForSources = true
                        station.updateSettings(state.settings.copy(enabled = selected))
                    }, enabled = !waitingForSources && !state.busy) { Text(if (waitingForSources) "Saving…" else "Continue") }
                    TextButton(onClick = { step = 0 }, enabled = !waitingForSources) { Text("Back") }
                    if (waitingForSources) {
                        Text(state.status)
                        TextButton(onClick = { waitingForSources = false }) { Text("Return to source choices") }
                    }
                }
            }
            else -> {
                item {
                    Text("Give selected sources access", style = MaterialTheme.typography.headlineMedium)
                    Text("Android may ask for nearby-device, location, or sensor permissions for the sources you selected. You can decline individual permissions and continue with partial captures.")
                    Text("Watch pairing and watch-health access are available from Devices after setup. Add a provider later in Settings to analyze a saved capture.")
                }
                item {
                    if (state.settings.enabled.isNotEmpty()) OutlinedButton(onClick = station::requestPermissions) { Text("Review requested permissions") }
                    TextButton(onClick = station::openPermissionSettings) { Text("Open Android permissions") }
                }
                item {
                    Button(onClick = { station.updateSettings(state.settings.copy(onboardingComplete = true)) }, enabled = !state.busy) { Text("Open Signal Station") }
                    TextButton(onClick = { step = 1 }) { Text("Back to sources") }
                    Text("You can revisit this guide from Settings.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun SignalCapturePage(
    state: SignalState,
    station: SignalStation,
    onDetail: (String) -> Unit,
    onSources: () -> Unit,
    onManageWatch: (() -> Unit)?,
    onFieldTest: () -> Unit,
) {
    val watch = state.watches.firstOrNull { it.id == state.settings.watchId }
    val recent = state.records.filter { it.observations.isNotEmpty() || it.kind == "capture" }.sortedByDescending { it.createdAt }.take(5)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("A record of right now", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Capture enabled readings, then inspect them here. Choose Analyze when you want to send a saved capture to your provider.")
        }
        item {
            Button(onClick = station::capture, enabled = !state.busy && state.settings.enabled.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Capture readings") }
            Text("${signalCount(state.settings.enabled.size, "source")} enabled · saved on this phone", style = MaterialTheme.typography.bodyMedium)
            if (state.settings.enabled.isEmpty()) Text("Choose at least one source to capture.")
            TextButton(onClick = onSources) { Text("Choose sources") }
        }
        item { SignalContextControls(state, station) }
        item {
            Text("What changed?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Compare the latest capture with the previous capture using the same sources and watch. Missing or cached readings stay unknown.")
            val latest = state.records.firstOrNull { it.kind in setOf("capture", "presence") && it.state == "ready" }
            Button(onClick = { latest?.let { station.summarizeChanges(it.id) } }, enabled = !state.busy && latest?.let { SignalChanges.baseline(it, state.records, state.settings.enabled) != null } == true) { Text("Summarize changes locally") }
            state.records.firstOrNull { it.kind == "changes" }?.let { record ->
                TextButton(onClick = { onDetail(record.id) }) { Text("Read latest change summary") }
            }
            TextButton(onClick = onFieldTest) { Text("20-minute field trial") }
        }
        item {
            HorizontalDivider()
            Text("Watch", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(when {
                watch == null -> "No watch selected. Phone-only captures are available."
                watch.connected -> "${watch.name} is connected."
                else -> "${watch.name} is disconnected. Watch readings may be unavailable."
            })
            SignalChoice("Selected watch", state.settings.watchId, listOf("" to "Phone only") + state.watches.map { it.id to it.name }, !state.busy) {
                station.updateSettings(state.settings.copy(watchId = it))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onManageWatch?.let { OutlinedButton(onClick = it) { Text("Pair or manage watch") } }
                OutlinedButton(onClick = station::installWatchApp, enabled = !state.busy && watch?.connected == true) { Text("Install watch app") }
            }
            if (state.installStatus.isNotBlank()) Text(state.installStatus)
        }
        item { Text("Recent captures", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
        if (recent.isEmpty()) item { Text("Your first capture will appear here. No key or account is required.") }
        items(recent, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelLarge)
                Text(if (record.kind == "capture") "Captured readings" else record.question, style = MaterialTheme.typography.titleMedium)
                Text("${signalCount(record.observations.size, "reading")} · ${record.state}")
                TextButton(onClick = { onDetail(record.id) }) { Text("Inspect capture") }
            }
        }
    }
}
