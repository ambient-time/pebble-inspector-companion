package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.time.Clock

@Composable
internal fun SignalPresencePage(station: SignalStation, state: SignalState, onDetail: (String) -> Unit) {
    val settings = state.settings
    var currentTime by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.records, settings) {
        while (true) {
            currentTime = Clock.System.now().toEpochMilliseconds()
            kotlinx.coroutines.delay(1000)
        }
    }
    val evidence = SignalPresenceTimeline.entries(settings, state.records, currentTime)
    val uriHandler = LocalUriHandler.current
    var candidate by remember { mutableStateOf<SignalRadioCandidate?>(null) }
    var deviceLabel by remember { mutableStateOf("") }
    var editingPlace by remember { mutableStateOf<SignalPlaceFence?>(null) }
    var placeLabel by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("150") }
    var wifiSsid by remember { mutableStateOf("") }
    var showPlaceEditor by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SignalPresenceTarget?>(null) }
    var removePlace by remember { mutableStateOf<SignalPlaceFence?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Nearby", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Keep a local record of your devices and familiar places. Radio activity can suggest changes around this phone; it cannot count people or reliably identify movement on its own.")
        }
        item {
            listOf("presence.bluetooth" to "Bluetooth presence", "presence.wifi" to "Wi-Fi presence", "presence.places" to "Saved places").forEach { (key, label) ->
                SignalToggle(label, key in settings.enabled, !state.busy) { enabled ->
                    station.updateSettings(settings.copy(enabled = if (enabled) settings.enabled + key else settings.enabled - key))
                }
            }
            Text("Choose sources, then scan when ready. Saved sightings stay on this phone; model analysis happens only when requested. Device and place switches stop future observations; delete old records separately in History.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = station::scanPresence, enabled = !state.busy && settings.enabled.any { it.startsWith("presence.") }) { Text("Check now") }
                TextButton(onClick = station::requestPermissions, enabled = !state.busy) { Text("Review permissions") }
            }
            Text(state.presenceStatus)
        }
        item {
            HorizontalDivider()
            Text("Your devices", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Save devices you own or have permission to monitor. A missed signal does not mean a device has left.")
            if (settings.presenceTargets.isEmpty()) Text("Run a scan, then name a device below to start recording its sightings.")
        }
        items(settings.presenceTargets, key = { "target:${it.id}" }) { target ->
            Column {
                SignalToggle(target.label, target.enabled, !state.busy, "${target.radio} · ${if (target.beaconId.isBlank()) "saved address" else "saved beacon identity"}") { enabled ->
                    station.updateSettings(settings.copy(presenceTargets = settings.presenceTargets.map { if (it.id == target.id) it.copy(enabled = enabled) else it }))
                }
                evidence.firstOrNull { it.targetId == target.id }?.let { entry ->
                    Text(when (entry.state) {
                        "observed_repeatedly" -> "Seen in ${entry.freshCaptures} recent checks"
                        "observed_once" -> "Seen in the latest check"
                        "not_observed" -> "Not seen in the latest check · departure unknown"
                        "ambiguous" -> "More than one matching signal · identity uncertain"
                        else -> "Current presence unknown · check again"
                    })
                    entry.lastSeenAt?.let { Text("Last seen ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = { removeTarget = target }, enabled = !state.busy) { Text("Forget device") }
            }
        }
        item { Text("Latest scan", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
        if (state.presenceCandidates.isEmpty()) item { Text("No devices listed. Devices may be asleep, out of range, or using changing addresses.") }
        items(state.presenceCandidates, key = { "candidate:${it.radio}:${it.address}" }) { found ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(found.name.ifBlank { "Unnamed ${found.radio} device" }, style = MaterialTheme.typography.titleMedium)
                Text("${found.radio} · ${found.address} · ${found.rssi} dBm")
                Text("${found.measuredAt?.let { signalDateTime(it) } ?: "Time unavailable"} · ${found.status}", style = MaterialTheme.typography.bodySmall)
                if (found.metadata.isNotBlank()) Text(found.metadata, style = MaterialTheme.typography.bodySmall)
                if (found.sampleCount > 1) Text("${found.sampleCount} fresh samples · median ${found.medianRssi} dBm", style = MaterialTheme.typography.bodySmall)
                if (found.security.isNotBlank()) Text("Network security: ${found.security}", style = MaterialTheme.typography.bodySmall)
                if (settings.presenceTargets.none { SignalPresence.matches(it, found) }) {
                    TextButton(onClick = { candidate = found; deviceLabel = found.name }, enabled = !state.busy) { Text("Name and save device") }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Familiar places", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Name a coordinate area and optionally associate a Wi-Fi name you recognize. Boundaries are checked on demand; continuous geofencing and arrival alerts are not active. Coordinates are approximate; nearby businesses and open networks do not establish where you are or permission to connect.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editingPlace = null; placeLabel = ""; latitude = ""; longitude = ""; radius = "150"; wifiSsid = ""; showPlaceEditor = true }, enabled = !state.busy) { Text("Add place manually") }
                OutlinedButton(onClick = station::lookupNearbyPlace, enabled = !state.busy) { Text("Look up nearby address") }
            }
            Text("Looking up an address sends this phone’s location to OpenStreetMap. It runs only when you tap the lookup button.", style = MaterialTheme.typography.bodySmall)
            state.placeLookup?.let { lookup ->
                Text(lookup.place.name, style = MaterialTheme.typography.titleMedium)
                Text(lookup.description)
                TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap contributors · attribution") }
                TextButton(onClick = {
                    editingPlace = null; placeLabel = lookup.place.name
                    latitude = lookup.place.latitude.toString(); longitude = lookup.place.longitude.toString()
                    radius = "150"; wifiSsid = ""; showPlaceEditor = true
                }, enabled = !state.busy) { Text("Save as a familiar place") }
            }
        }
        items(settings.placeFences, key = { "place:${it.id}" }) { place ->
            Column {
                SignalToggle(place.label, place.enabled, !state.busy, "${place.radiusMeters} m radius${if (place.wifiSsid.isBlank()) "" else " · Wi-Fi: ${place.wifiSsid}"}") { enabled ->
                    station.updateSettings(settings.copy(placeFences = settings.placeFences.map { if (it.id == place.id) it.copy(enabled = enabled) else it }))
                }
                FlowRow {
                    TextButton(onClick = { editingPlace = place; placeLabel = place.label; latitude = place.latitude.toString(); longitude = place.longitude.toString(); radius = place.radiusMeters.toString(); wifiSsid = place.wifiSsid; showPlaceEditor = true }, enabled = !state.busy) { Text("Edit place") }
                    TextButton(onClick = { removePlace = place }, enabled = !state.busy) { Text("Forget place") }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Recent presence records", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Open History to inspect, compare, or delete saved readings. Changes in signal strength are observations, not precise distances.")
        }
        val recent = state.records.filter { it.sourceKeys.any { key -> key.startsWith("presence.") } }.sortedByDescending { it.createdAt }.take(10)
        if (recent.isEmpty()) item { Text("No saved presence readings yet.") }
        items(recent, key = { "record:${it.id}" }) { record ->
            TextButton(onClick = { onDetail(record.id) }) { Text("${signalDateTime(record.createdAt)} · ${record.question}") }
            Text(record.summary, style = MaterialTheme.typography.bodySmall)
        }
    }
    candidate?.let { found ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Name this device") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${found.radio} · ${found.address}")
                OutlinedTextField(deviceLabel, { deviceLabel = it.take(80) }, label = { Text("Your device name") }, singleLine = true)
                Text(if (found.beaconId.isBlank()) "This device will be matched by its current address, which can change." else "This device will be matched by its advertised beacon identity. Duplicate beacon identities are marked uncertain.")
            }
        }, confirmButton = { TextButton(onClick = { station.enrollPresenceTarget(found, deviceLabel.trim()); candidate = null }, enabled = deviceLabel.isNotBlank()) { Text("Save device") } }, dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel") } })
    }
    if (showPlaceEditor) {
        val lat = latitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = longitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val meters = radius.toIntOrNull()?.takeIf { it in 25..10000 }
        AlertDialog(onDismissRequest = { showPlaceEditor = false }, title = { Text(if (editingPlace == null) "Save a familiar place" else "Edit familiar place") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(placeLabel, { placeLabel = it.take(80) }, label = { Text("Your place name") }, singleLine = true)
                OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true)
                OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true)
                OutlinedTextField(radius, { radius = it }, label = { Text("Radius in meters · 25–10000") }, singleLine = true)
                OutlinedTextField(wifiSsid, { wifiSsid = it.take(64) }, label = { Text("Recognized Wi-Fi name · optional") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = {
            if (lat != null && lon != null && meters != null) {
                station.savePlaceFence(SignalPlaceFence(id = editingPlace?.id ?: "place-${Clock.System.now().toEpochMilliseconds()}", label = placeLabel.trim(), latitude = lat, longitude = lon, radiusMeters = meters, wifiSsid = wifiSsid.trim(), enabled = editingPlace?.enabled ?: true))
                showPlaceEditor = false
            }
        }, enabled = placeLabel.isNotBlank() && lat != null && lon != null && meters != null) { Text("Save place") } }, dismissButton = { TextButton(onClick = { showPlaceEditor = false }) { Text("Cancel") } })
    }
    removeTarget?.let { target ->
        AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text("Forget ${target.label}?") }, text = { Text("This removes the saved device. Existing history remains available until you delete it in History.") }, confirmButton = { TextButton(onClick = { station.removePresenceTarget(target.id); removeTarget = null }) { Text("Forget device") } }, dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } })
    }
    removePlace?.let { place ->
        AlertDialog(onDismissRequest = { removePlace = null }, title = { Text("Forget ${place.label}?") }, text = { Text("This removes the saved place. Existing history remains available until you delete it in History.") }, confirmButton = { TextButton(onClick = { station.removePlaceFence(place.id); removePlace = null }) { Text("Forget place") } }, dismissButton = { TextButton(onClick = { removePlace = null }) { Text("Cancel") } })
    }
}
