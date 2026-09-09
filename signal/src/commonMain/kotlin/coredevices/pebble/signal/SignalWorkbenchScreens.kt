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
            if (step > 0) Text("Capture setup · $step of 2", style = MaterialTheme.typography.labelLarge)
        }
        when (step) {
            0 -> {
                item {
                    Text("Ask a question.", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    Text("Type a question, choose your answer provider, and read the reply here. A watch is optional.")
                }
                item {
                    Text("Start with your own provider key. You can choose phone and watch readings later when you want to give a question more context.")
                    Text("Questions, replies, and captures stay in history on this phone until you delete them. Sending a question shares its text and selected context with your provider.")
                }
                item {
                    Button(
                        onClick = { station.updateSettings(state.settings.copy(onboardingComplete = true)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Start with a question") }
                    OutlinedButton(onClick = { step = 1 }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose capture sources") }
                    Text("Starting with a question enables no new sources and requests no sensor permissions. Source choices and optional wake listening are available later.", style = MaterialTheme.typography.bodySmall)
                }
            }
            1 -> {
                item {
                    Text("What would you like to collect?", style = MaterialTheme.typography.headlineMedium)
                    Text("Every source is optional. You can change these choices in Settings. No provider key is needed to capture.")
                    Text("Weather sources contact their data services when enabled. Phone and watch measurements are collected for requested captures and presence checks.", style = MaterialTheme.typography.bodySmall)
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
                    Text("Choose an already paired watch from Watch connection after setup. Add a provider later in Settings to analyze a saved capture.")
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
    var captureToolsOpen by remember { mutableStateOf(false) }
    var watchSetupOpen by remember { mutableStateOf(false) }
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
            TextButton(onClick = onSources, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose sources") }
        }
        item { Text("Recent captures", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
        if (recent.isEmpty()) item { Text("Your first capture will appear here. No key or account is required.") }
        items(recent, key = { it.id }) { record ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text(signalDateTime(record.createdAt), style = MaterialTheme.typography.labelLarge)
                Text(if (record.kind == "capture") "Captured readings" else record.question, style = MaterialTheme.typography.titleMedium)
                Text("${signalCount(record.observations.size, "reading")} · ${record.state}")
                TextButton(onClick = { onDetail(record.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Inspect capture") }
            }
        }
        item {
            HorizontalDivider()
            OutlinedButton(
                onClick = { captureToolsOpen = !captureToolsOpen },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (captureToolsOpen) "Expanded" else "Collapsed" },
            ) { Text(if (captureToolsOpen) "Hide capture tools" else "Capture tools") }
            Text("Source presets, local comparisons, and a field trial.", style = MaterialTheme.typography.bodySmall)
        }
        if (captureToolsOpen) {
            item { SignalContextControls(state, station) }
            item {
                Text("What changed?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text("Compare the latest capture with the previous capture using the same sources and watch. Missing or cached readings stay unknown.")
                val latest = state.records.firstOrNull { it.kind in setOf("capture", "presence") && it.state == "ready" }
                Button(onClick = { latest?.let { station.summarizeChanges(it.id) } }, enabled = !state.busy && latest?.let { SignalChanges.baseline(it, state.records, state.settings.enabled) != null } == true, modifier = Modifier.heightIn(min = 48.dp)) { Text("Summarize changes locally") }
                state.records.firstOrNull { it.kind == "changes" }?.let { record ->
                    TextButton(onClick = { onDetail(record.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read latest change summary") }
                }
                TextButton(onClick = onFieldTest, modifier = Modifier.heightIn(min = 48.dp)) { Text("20-minute field trial") }
            }
        }
        item {
            OutlinedButton(
                onClick = { watchSetupOpen = !watchSetupOpen },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (watchSetupOpen) "Expanded" else "Collapsed" },
            ) { Text(if (watchSetupOpen) "Hide watch setup" else "Watch setup (optional)") }
            Text(when {
                watch == null -> "Phone chat and phone captures work without a watch."
                watch.connected -> "${watch.name} is connected."
                else -> "${watch.name} is disconnected. Watch readings may be unavailable."
            }, style = MaterialTheme.typography.bodySmall)
            if (state.installStatus.isNotBlank()) Text(state.installStatus)
        }
        if (watchSetupOpen) item {
            Text("Watch setup", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("This test build manages its own watch connection. Keep only one companion actively connected to the watch. If your watch is already paired here, select it below; no new pairing is needed.")
            SignalChoice("Selected watch", state.settings.watchId, listOf("" to "Phone only") + state.watches.map { it.id to it.name }, !state.busy) {
                station.updateSettings(state.settings.copy(watchId = it))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onManageWatch?.let { OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Manage watch connection") } }
                OutlinedButton(onClick = station::installWatchApp, enabled = !state.busy && state.watchCapabilities.install && watch?.connected == true, modifier = Modifier.heightIn(min = 48.dp)) { Text("Install watch app") }
            }
        }
    }
}
