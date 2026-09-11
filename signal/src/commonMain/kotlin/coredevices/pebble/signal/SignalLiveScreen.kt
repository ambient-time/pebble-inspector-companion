package coredevices.pebble.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
internal fun SignalLivePage(state: SignalState, station: SignalStation, onSources: () -> Unit) {
    val live = state.live
    var tick by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { tick = Clock.System.now().toEpochMilliseconds(); delay(1_000) } }
    // New scan arrivals can post between ticks. Sample time again when they recompose the view.
    val now = maxOf(tick, Clock.System.now().toEpochMilliseconds())
    val visible = live.entries.filter { now - it.lastSeenAt in 0..60_000 }
    var detailId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(station) { onDispose { station.stopLiveSignals() } }
    val fresh = visible.filter { it.fresh(now) }
    LazyColumn(Modifier.fillMaxSize().testTag("live-signals-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Around me", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Text("Live radio signals, grouped by strength.")
                Text("Strength is not distance or direction. These are radio observations, not a count of phones or people.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (live.running) station.stopLiveSignals() else station.startLiveSignals() }, enabled = live.running || !state.busy) { Text(if (live.running) "Stop scanning" else "Start scanning") }
                    TextButton(onClick = onSources) { Text("Choose radios") }
                    TextButton(onClick = station::requestLivePermissions) { Text("Review radio permissions") }
                }
                Text(live.status, style = MaterialTheme.typography.bodyMedium)
                Text("Scans only while this view is open, for up to 5 minutes. Android may limit scan updates.", style = MaterialTheme.typography.bodySmall)
                if (live.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (live.updatedAt > 0) Text("${if (live.running) "Latest update" else "Stopped view"}: ${signalDateTime(live.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recently seen", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("${fresh.count { it.radio == "bluetooth" }} Bluetooth advertisers · ${fresh.count { it.radio == "wifi" }} Wi-Fi access points")
                Text("${live.newCount} new in latest scan · ${visible.size - fresh.size} older observations", style = MaterialTheme.typography.bodySmall)
                Text("Recently seen means within the last 15 seconds. Older observations fade and leave after 60 seconds.", style = MaterialTheme.typography.bodySmall)
                if (live.omittedAtLeast > 0) Text("At least ${live.omittedAtLeast} additional observations were omitted from this bounded view.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { station.saveLiveScene(false) }, enabled = fresh.isNotEmpty() && !state.busy) { Text("Save snapshot") }
                Button(onClick = { station.saveLiveScene(true) }, enabled = fresh.isNotEmpty() && !state.busy) { Text("Ask about this scene") }
            }
            Text("Saving stays on this phone. Ask opens a draft with this snapshot attached for review.", style = MaterialTheme.typography.bodySmall)
        }
        listOf("strong" to "Strong · −60 dBm or higher", "medium" to "Medium · −75 to −61 dBm", "faint" to "Faint · below −75 dBm").forEach { (band, title) ->
            item(key = band) {
                val entries = visible.filter { it.band == band }
                Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty()) Text("No observations in this band", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entries.forEach { entry ->
                            val age = ((now - entry.lastSeenAt).coerceAtLeast(0) / 1000)
                            OutlinedButton(onClick = { detailId = entry.id }, modifier = Modifier.alpha(if (entry.fresh(now)) 1f else .55f)) {
                                Icon(if (entry.radio == "wifi") Icons.Outlined.Wifi else Icons.Outlined.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(entry.label.ifBlank { if (entry.radio == "wifi") "Wi-Fi access point" else "Bluetooth advertiser" })
                                    Text("${entry.rssi} dBm · ${age}s ago · ${if (entry.status == "cached") "cached" else entry.trend}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } }
            }
        }
        if (live.outcomes.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Radio availability", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                live.outcomes.forEach { (source, outcome) -> Text("${source.replace('_', ' ')}: ${outcome.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    visible.firstOrNull { it.id == detailId }?.let { entry ->
        SignalLiveDetail(entry, now, onClose = { detailId = null }, onLabel = { station.labelLiveSignal(entry.id, it) })
    }
}

@Composable
private fun SignalLiveDetail(entry: SignalLiveEntry, now: Long, onClose: () -> Unit, onLabel: (String) -> Unit) {
    var label by remember(entry.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(entry.label.ifBlank { "Radio observation" }) }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (entry.radio == "wifi") "Wi-Fi access point" else "Bluetooth advertiser")
            Text("${entry.rssi} dBm · ${entry.band} signal · ${entry.trend}")
            Text("Last seen ${((now - entry.lastSeenAt).coerceAtLeast(0) / 1000)} seconds ago. ${entry.sampleCount} samples this session.")
            SignalLiveGraph(entry.samples)
            if (entry.metadata.isNotBlank()) Text(entry.metadata)
            if (entry.security.isNotBlank()) Text("Advertised security: ${entry.security}. This does not establish whether a network is safe or available to use.")
            entry.frequencyMHz?.let { Text("Frequency: $it MHz") }
            Text("Signals can change because of walls, pockets, orientation or transmitter power. A changing signal does not establish movement.", style = MaterialTheme.typography.bodySmall)
            if (entry.fresh(now)) {
                OutlinedTextField(value = label, onValueChange = { label = it.take(80) }, label = { Text("Label for this session") }, modifier = Modifier.fillMaxWidth())
                Text("This label resets with the scan session. Saved snapshots include it only when radio names are enabled. Manage devices you own in Nearby for recognition across sessions.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onLabel(label.trim()) }, enabled = label.isNotBlank()) { Text("Set session label") }
            } else Text("Scan again to label this older observation.", style = MaterialTheme.typography.bodySmall)
        }
    })
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
