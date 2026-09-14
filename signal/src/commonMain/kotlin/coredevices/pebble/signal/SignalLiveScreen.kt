package coredevices.pebble.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Signal strength bands deliberately communicate neither bearings nor estimated distance. */
@Composable
internal fun SignalLivePage(state: SignalState, station: SignalStation, onSources: () -> Unit, onSavedContext: (() -> Unit)? = null) {
    val live = state.live
    var tick by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { tick = Clock.System.now().toEpochMilliseconds(); delay(1_000) } }
    // New scan arrivals can post between ticks. Sample time again when they recompose the view.
    val now = maxOf(tick, Clock.System.now().toEpochMilliseconds())
    val visible = live.entries.filter { now - it.lastSeenAt in 0..60_000 }
    var detail by signalUiState<SignalLiveEntry?>("live.detail.${live.startedAt}.${state.settings.enabled.sorted().joinToString()}") { null }
    DisposableEffect(station) { onDispose { station.stopLiveSignals() } }
    val fresh = visible.filter { it.fresh(now) }
    var expandedRadios by signalUiState("live.expandedRadios") { setOf("wifi", "bluetooth") }
    var wifiFilter by signalUiState("live.wifiFilter") { "all" }
    detail?.let { entry ->
        SignalLiveDetail(entry, now, onClose = { detail = null }, onLabel = { station.labelLiveSignal(entry.id, it); detail = entry.copy(label = it) })
        return
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (live.running) station.stopLiveSignals() else station.startLiveSignals() }, enabled = live.running || !state.busy) { Text(if (live.running) "Stop scanning" else "Start scanning") }
            TextButton(onClick = onSources) { Text("Choose radios") }
        }
    LazyColumn(Modifier.weight(1f).testTag("live-signals-list"), state = signalListState("live.${live.startedAt}"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Live signals", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Strength is not distance, direction or a count of people.", style = MaterialTheme.typography.bodySmall)
            Text(live.status, style = MaterialTheme.typography.bodyMedium)
            if (live.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Recently seen", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text("${fresh.count { it.radio == "bluetooth" }} Bluetooth · ${fresh.count { it.radio == "wifi" }} Wi-Fi · ${visible.size - fresh.size} older", style = MaterialTheme.typography.bodyMedium)
            Text("${live.newCount} new in latest scan", style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { station.saveLiveScene(false) }, enabled = fresh.isNotEmpty() && !state.busy) { Text("Save snapshot") }
                Button(onClick = { station.saveLiveScene(true) }, enabled = fresh.isNotEmpty() && !state.busy) { Text("Ask about this scene") }
            }
        }
        listOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth").forEach { (radio, title) ->
            val radioEntries = visible.filter { it.radio == radio }
            val rows = radioEntries.filter { radio != "wifi" || signalWifiMatches(it.security, wifiFilter) }
                .sortedWith(compareBy({ it.firstSeenAt }, { it.id }))
            val expanded = radio in expandedRadios
            item(key = "radio:$radio") {
                SignalDisclosureHeader(title,
                    "${radioEntries.count { it.fresh(now) }} recent · ${radioEntries.count { !it.fresh(now) }} older" +
                        if (radio == "wifi") " · ${radioEntries.count { SignalRadioPresentation.wifiAccess(it.security).noPassword }} no password" else "",
                    expanded, { expandedRadios = if (expanded) expandedRadios - radio else expandedRadios + radio })
                if (expanded && radio == "wifi") {
                    SignalWifiFilters(wifiFilter) { wifiFilter = it }
                    if ("device.network" in state.settings.enabled) state.records.filter { it.state == "ready" }
                        .flatMap { it.observations }.filter { it.key == "device.network" }.maxByOrNull { it.collectedAt }?.let {
                            Text("${SignalRadioPresentation.connectedNetwork(it.value)} · ${signalDateTime(it.collectedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                }
                if (expanded && rows.isEmpty()) Text(if (radioEntries.isEmpty()) "No recent observations" else "No networks match this filter", style = MaterialTheme.typography.bodySmall)
                if (expanded && radio == "wifi") Text("${rows.size} of ${radioEntries.size} shown. Filters change this view; snapshots include all fresh selected radios.", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) items(rows, key = { it.id }) { entry ->
                val age = ((now - entry.lastSeenAt).coerceAtLeast(0) / 1000)
                val repeated = entry.scanCount >= 3
                OutlinedButton(onClick = { detail = entry }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (repeated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (repeated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {
                    Icon(if (entry.radio == "wifi") Icons.Outlined.Wifi else Icons.Outlined.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.label)
                        Text(SignalRadioPresentation.deviceType(entry.radio, entry.metadata).label, style = MaterialTheme.typography.bodySmall)
                        Text(if (entry.scanCount > 1) "Seen in ${entry.scanCount} retained scans" else if (entry.scanCount == 1) "First retained scan" else "Cached observation", style = MaterialTheme.typography.labelSmall)
                        Text("${entry.rssi} dBm · ${entry.band} · ${age}s ago · ${if (!entry.fresh(now)) "older observation" else entry.trend}", style = MaterialTheme.typography.labelSmall)
                        if (radio == "wifi") Text(SignalRadioPresentation.wifiAccess(entry.security).label + " · Sign-in unknown", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            var about by signalUiState("live.about") { false }
            TextButton(onClick = { about = !about }) { Text(if (about) "Hide signal information" else "About these signals") }
            if (about) {
                Text("Wi-Fi counts are access points and Bluetooth counts are advertisers. They are not counts of phones or people.", style = MaterialTheme.typography.bodySmall)
                Text("Scans only while this view is open, for up to 5 minutes. Android may limit scan updates.", style = MaterialTheme.typography.bodySmall)
                Text("Tinted rows were seen in at least three fresh scans. Counts reset with a new session or after an observation leaves the view. Repetition does not establish trust or safety.", style = MaterialTheme.typography.bodySmall)
                Text("Recently seen means within 15 seconds. Older observations are labeled and leave after 60 seconds.", style = MaterialTheme.typography.bodySmall)
                Text("Save snapshot stores the visible fresh readings on this phone. Ask saves a snapshot and opens a draft for review.", style = MaterialTheme.typography.bodySmall)
                if (live.updatedAt > 0) Text("Latest update: ${signalDateTime(live.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                if (live.omittedAtLeast > 0) Text("At least ${live.omittedAtLeast} additional observations omitted from this bounded view.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = station::requestLivePermissions) { Text("Review radio permissions") }
            onSavedContext?.let { TextButton(onClick = it) { Text("Saved wireless and cellular context") } }
        }
        if (live.outcomes.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Radio availability", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                live.outcomes.forEach { (source, outcome) -> Text("${source.replace('_', ' ')}: ${outcome.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    }

}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SignalLiveDetail(entry: SignalLiveEntry, now: Long, onClose: () -> Unit, onLabel: (String) -> Unit) {
    var label by signalUiState("live.label.${entry.id}.${entry.firstSeenAt}") { "" }
    androidx.compose.ui.backhandler.BackHandler { onClose() }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Done") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.label.ifBlank { "Radio observation" }, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Frozen view for inspection. These readings are not saved unless you use Save snapshot.", style = MaterialTheme.typography.bodySmall)
            val type = SignalRadioPresentation.deviceType(entry.radio, entry.metadata)
            Text(type.label)
            Text(type.evidence, style = MaterialTheme.typography.bodySmall)
            if (entry.radio == "wifi") Text("${SignalRadioPresentation.wifiAccess(entry.security).label}. Browser sign-in and public access unknown.")
            Text("${entry.rssi} dBm · ${entry.band} signal · ${entry.trend}")
            Text("Last seen ${((now - entry.lastSeenAt).coerceAtLeast(0) / 1000)} seconds ago. ${entry.sampleCount} samples this session.")
            SignalLiveGraph(entry.samples)
            if (entry.metadata.isNotBlank()) Text(entry.metadata)
            if (entry.security.isNotBlank()) Text("Advertised security: ${entry.security}. This does not establish whether a network is safe or available to use.")
            entry.frequencyMHz?.let { Text("Frequency: $it MHz") }
            Text("Signals can change because of walls, pockets, orientation or transmitter power. A changing signal does not establish movement.", style = MaterialTheme.typography.bodySmall)
            if (entry.fresh(now)) {
                OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, label = { Text("Label for this session") }, modifier = Modifier.fillMaxWidth())
                Text("This label resets with the scan session. Saved snapshots include it only when radio names are enabled. Manage devices you own in My devices for recognition across sessions.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onLabel(label.trim()) }, enabled = label.isNotBlank()) { Text("Set session label") }
            } else Text("Scan again to label this older observation.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SignalLiveGraph(samples: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    val values = samples.takeLast(12)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal history", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Signal strength graph, oldest to newest: ${values.joinToString()} dBm" }) {
            fun point(index: Int): Offset = Offset(size.width * index / (values.size - 1).coerceAtLeast(1), size.height * (1f - (values[index].coerceIn(-100, -20) + 100) / 80f))
            values.indices.forEach { index ->
                if (index > 0) drawLine(color, point(index - 1), point(index), strokeWidth = 3.dp.toPx())
                drawCircle(color, radius = 3.dp.toPx(), center = point(index))
            }
        }
        Text(if (values.isEmpty()) "No signal samples" else "Oldest → newest: ${values.joinToString()} dBm", style = MaterialTheme.typography.bodySmall)
    }
}
