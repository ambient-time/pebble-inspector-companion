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
    val saved = remember(state.records) { state.records.filter { it.state == "ready" }.sortedByDescending { it.createdAt } }
    val ageBucket = currentTime / 30_000
    val fixRecord = remember(saved, settings, ageBucket) { saved.firstOrNull { "location" in settings.enabled && SignalHistory.allowed(it, settings.enabled) &&
        SignalContext.fix(it, currentTime)?.let { fix -> fix.accuracyMeters <= 1000 } == true } }
    // This only validates retained input using the provider's rules. It cannot save settings or send a request.
    val radioRecord = remember(saved, settings, ageBucket) {
        val inputSettings = settings.copy(lookups = settings.lookups.copy(radioLocation = true), enabled = settings.enabled + "location.radio")
        saved.asSequence().filter { record ->
            SignalHistory.allowed(record, inputSettings.enabled) && record.observations.any { row ->
                row.key in settings.enabled && row.key in setOf("wifi.identifiers", "cellular.identifiers") &&
                    row.status in setOf("fresh", "available", "cached") && row.measuredAt?.let { currentTime - it in 0..300_000 } == true &&
                    (row.fields["bssid"]?.isNotBlank() == true || row.fields["cellId"]?.isNotBlank() == true)
            }
        }.firstOrNull { record -> runCatching { SignalLookupProviders.prepare("radio_location", record, inputSettings, currentTime) }.isSuccess }
    }
    val lookupRecords = remember(saved) { saved.filter { it.kind == "enrichment" } }
    val placesAttempt = remember(lookupRecords) { lookupRecords.firstOrNull { record -> record.observations.any { it.key == "places.nearby" } } }
    val placesRecord = remember(lookupRecords) { lookupRecords.firstOrNull { record -> record.observations.any { it.key == "places.nearby" && it.status == "candidate" } } }
    val wirelessRecord = remember(saved) { saved.firstOrNull { record -> record.observations.any { it.key == "wifi" || it.key == "bluetooth" } } }
    val networkRecord = remember(saved) { saved.firstOrNull { record -> record.observations.any { it.key == "device.network" } } }
    val cellularRecord = remember(saved) { saved.firstOrNull { record -> record.observations.any { it.key == "cellular" } } }
    val locationRecord = remember(saved) { saved.firstOrNull { record -> record.observations.any { it.key == "location" } } }
    val estimateAttempt = remember(lookupRecords) { lookupRecords.firstOrNull { record -> record.observations.any { it.key == "location.radio" } } }
    val estimateRecord = remember(lookupRecords) { lookupRecords.firstOrNull { record -> record.observations.any { it.key == "location.radio" && it.status in setOf("estimate", "coarse_estimate") } } }
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
            Text("Nearby places", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text("Map candidates around a saved position. Review a result before naming a familiar place.", style = MaterialTheme.typography.bodySmall)
            SignalToggle("Allow nearby place lookups", settings.lookups.nearbyPlaces, !state.busy,
                "Review the saved location and destination before sending a map or address request.") { enabled ->
                station.updateSettings(settings.copy(lookups = settings.lookups.copy(nearbyPlaces = enabled),
                    enabled = if (enabled) settings.enabled + "places.nearby" else settings.enabled - "places.nearby"))
            }
            if (settings.lookups.nearbyPlaces && "places.nearby" !in settings.enabled) {
                Text("Map-place lookups are allowed, but the map-place source is disabled.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled + "places.nearby")) }, enabled = !state.busy) { Text("Enable map-place source") }
            }
            OutlinedButton(onClick = { fixRecord?.let { station.prepareLookup("nearby", it.id) } },
                enabled = !state.busy && settings.lookups.nearbyPlaces && "places.nearby" in settings.enabled && fixRecord != null) { Text("Look up nearby places…") }
            if (fixRecord == null) Text("Enable Location in Settings, then capture a position with accuracy within 1 km. A position older than 15 minutes needs a new capture. Sources in that capture must remain enabled for lookup.", style = MaterialTheme.typography.bodySmall)
            else Text("Lookup position saved ${signalDateTime(fixRecord.createdAt)}", style = MaterialTheme.typography.bodySmall)
            if (placesRecord == null) Text("No saved map results yet.", style = MaterialTheme.typography.bodySmall)
            else {
                val rows = placesRecord.observations.filter { it.key == "places.nearby" && it.status == "candidate" }
                rows.take(3).forEach { row ->
                    SignalMapCandidate(row, currentTime, !state.busy) {
                        editingPlace = null; placeLabel = it.fields["name"].orEmpty()
                        latitude = it.fields["latitude"].orEmpty(); longitude = it.fields["longitude"].orEmpty()
                        radius = "150"; wifiSsid = ""; showPlaceEditor = true
                    }
                }
                if (rows.size > 3) Text("${rows.size - 3} more results in saved evidence.", style = MaterialTheme.typography.bodySmall)
                SignalContextEvidence(placesRecord, onDetail)
            }
            if (placesAttempt != null && placesAttempt.id != placesRecord?.id) {
                Text("Latest lookup returned no map candidates${if (placesRecord != null) "; earlier results remain above" else ""}.", style = MaterialTheme.typography.bodySmall)
                placesAttempt.observations.firstOrNull { it.key == "places.nearby" }?.let { SignalContextObservation(it) }
                SignalContextEvidence(placesAttempt, onDetail)
            }
            Text("A nearby business or matching Wi-Fi name does not confirm your location.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("Wireless environment", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (wirelessRecord == null) Text("No saved wireless readings. Choose Wi-Fi or Bluetooth sources in Settings, then capture.", style = MaterialTheme.typography.bodySmall)
            else {
                SignalWireless.summary(wirelessRecord.observations).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                val radios = wirelessRecord.observations.filter { it.key == "wifi" || it.key == "bluetooth" }
                Text("Recorded states: ${radios.map { it.status.replace('_', ' ') }.distinct().joinToString()}", style = MaterialTheme.typography.bodySmall)
                val security = radios.mapNotNull { it.fields["security"] }.distinct()
                if (security.isNotEmpty()) Text("Advertised security: ${security.joinToString()}", style = MaterialTheme.typography.bodySmall)
                val bluetooth = radios.filter { it.key == "bluetooth" }
                if (bluetooth.isNotEmpty()) Text("${bluetooth.count { it.metric == "rssi" }} retained Bluetooth signal readings", style = MaterialTheme.typography.bodySmall)
                SignalContextEvidence(wirelessRecord, onDetail)
            }
            networkRecord?.let { record ->
                record.observations.lastOrNull { it.key == "device.network" }?.let { SignalContextObservation(it, "Connected network at capture") }
                if (record.id != wirelessRecord?.id) SignalContextEvidence(record, onDetail)
            }
            Text("An open advertisement does not establish working internet access. Saved readings describe their capture time.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("Cellular", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (cellularRecord == null) Text("No saved cellular readings. Enable Cellular network and signal in Settings, then capture.", style = MaterialTheme.typography.bodySmall)
            else {
                val rows = cellularRecord.observations.filter { it.key == "cellular" }
                rows.take(2).forEach { SignalContextObservation(it) }
                if (rows.size > 2) Text("${rows.size - 2} more cellular readings in saved evidence.", style = MaterialTheme.typography.bodySmall)
                SignalContextEvidence(cellularRecord, onDetail)
            }
        }
        item {
            HorizontalDivider()
            Text("Location", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            locationRecord?.let { record ->
                record.observations.lastOrNull { it.key == "location" }?.let { SignalContextObservation(it, "Phone position") }
                SignalContextEvidence(record, onDetail)
            } ?: Text("No saved phone position.", style = MaterialTheme.typography.bodySmall)
            estimateRecord?.let { record ->
                record.observations.lastOrNull { it.key == "location.radio" && it.status in setOf("estimate", "coarse_estimate") }?.let { SignalContextObservation(it, "External radio estimate") }
                SignalContextEvidence(record, onDetail)
            } ?: Text("No saved radio location estimate.", style = MaterialTheme.typography.bodySmall)
            if (estimateAttempt != null && estimateAttempt.id != estimateRecord?.id) {
                Text("Latest lookup returned no location estimate${if (estimateRecord != null) "; the earlier estimate remains above" else ""}.", style = MaterialTheme.typography.bodySmall)
                estimateAttempt.observations.firstOrNull { it.key == "location.radio" }?.let { SignalContextObservation(it) }
                SignalContextEvidence(estimateAttempt, onDetail)
            }
            SignalToggle("Allow radio location lookups", settings.lookups.radioLocation, !state.busy,
                "Review retained Wi-Fi and cell identifiers before sending them to the location service. This does not contribute a public scan.") { enabled ->
                station.updateSettings(settings.copy(lookups = settings.lookups.copy(radioLocation = enabled),
                    enabled = if (enabled) settings.enabled + "location.radio" else settings.enabled - "location.radio"))
            }
            if (settings.lookups.radioLocation && "location.radio" !in settings.enabled) {
                Text("Radio lookups are allowed, but the radio-location source is disabled.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { station.updateSettings(settings.copy(enabled = settings.enabled + "location.radio")) }, enabled = !state.busy) { Text("Enable radio-location source") }
            }
            OutlinedButton(onClick = { radioRecord?.let { station.prepareLookup("radio_location", it.id) } },
                enabled = !state.busy && settings.lookups.radioLocation && "location.radio" in settings.enabled && radioRecord != null) { Text("Estimate from radio signals…") }
            if (radioRecord == null) Text("Capture at least two visible Wi-Fi networks with names and identifiers, or a supported GSM, WCDMA or LTE cell identity. Hidden and _nomap networks are excluded. Readings must be less than 5 minutes old, with their sources still enabled.", style = MaterialTheme.typography.bodySmall)
            else Text("Identifier evidence saved ${signalDateTime(radioRecord.createdAt)}", style = MaterialTheme.typography.bodySmall)
            Text("An external estimate remains separate from the phone position. Its accuracy may be insufficient to identify a venue.", style = MaterialTheme.typography.bodySmall)
            if (fixRecord == null || radioRecord == null) OutlinedButton(onClick = station::capture,
                enabled = !state.busy && settings.enabled.isNotEmpty()) { Text("Capture enabled sources") }
        }
        item {
            SignalLookupServiceControls(state, station)
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
                OutlinedButton(onClick = { fixRecord?.let { station.prepareLookup("address", it.id) } },
                    enabled = !state.busy && settings.lookups.nearbyPlaces && "places.nearby" in settings.enabled && fixRecord != null) { Text("Look up nearby address…") }
            }
            Text("Address lookup uses the separate nearby-place permission above. Review its saved position and destination before sending.", style = MaterialTheme.typography.bodySmall)
            state.records.firstOrNull { it.kind == "enrichment" && it.question == "Nearby address lookup" && SignalHistory.allowed(it, settings.enabled) }?.let { result ->
                Text(result.summary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDetail(result.id) }) { Text("Open saved address result") }
            }
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
    SignalLookupReviewDialog(state, station, onDetail)
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
