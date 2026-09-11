package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalContextEvidence(record: SignalRecord, onDetail: (String) -> Unit) {
    Text("Saved ${signalDateTime(record.createdAt)}", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { onDetail(record.id) }) { Text("View evidence · ${record.id}") }
}

@Composable
internal fun SignalContextObservation(row: SignalObservation, label: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.titleMedium)
        val value = row.number?.takeIf { it.isFinite() }?.let { "$it ${row.unit}".trim() } ?: row.value
        if (value.isNotBlank()) SelectionContainer { Text(value) }
        Text("Status: ${row.status.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
        row.measuredAt?.let { Text("Measured ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        val labels = mapOf("technology" to "Radio technology", "radioType" to "Radio technology", "registered" to "Registered cell", "subscription" to "Subscription",
            "latitude" to "Latitude", "longitude" to "Longitude", "accuracyMeters" to "Accuracy in meters",
            "provider" to "Provider", "method" to "Method", "internet" to "Internet", "validated" to "Internet validated",
            "captivePortal" to "Captive portal", "retrievedAt" to "Retrieved", "expiresAt" to "Expires")
        row.fields.entries.filter { it.key in labels }.forEach { (key, value) ->
            Text("${labels.getValue(key)}: ${if (key in setOf("retrievedAt", "expiresAt")) signalLookupDate(value) else value}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SignalMapCandidate(row: SignalObservation, now: Long, enabled: Boolean, onSave: (SignalObservation) -> Unit) {
    val lat = row.fields["latitude"]?.toDoubleOrNull()
    val lon = row.fields["longitude"]?.toDoubleOrNull()
    val usableCoordinates = lat != null && lon != null && SignalContext.validCoordinate(lat, lon)
    var detailsOpen by remember(row.id, row.collectedAt) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(row.fields["name"].orEmpty().ifBlank { "Map result" }, style = MaterialTheme.typography.titleMedium)
        row.fields["category"]?.takeIf { it.isNotBlank() }?.let { Text(it) }
        if (!usableCoordinates && row.value.isNotBlank()) Text(row.value, style = MaterialTheme.typography.bodySmall)
        Text("Status: ${row.status.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
        row.fields["distanceMeters"]?.let { Text("Distance from saved position: $it m", style = MaterialTheme.typography.bodySmall) }
        if (row.fields["expiresAt"]?.toLongOrNull()?.let { it <= now } == true) {
            Text("Cached result expired · look up again to refresh", style = MaterialTheme.typography.bodySmall)
        }
        row.fields["attribution"]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (usableCoordinates) TextButton(onClick = { onSave(row) }, enabled = enabled) { Text("Save this place…") }
        TextButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.semantics { stateDescription = if (detailsOpen) "Expanded" else "Collapsed" }) {
            Text(if (detailsOpen) "Hide map details" else "Map details")
        }
        if (detailsOpen) {
            if (usableCoordinates && row.value.isNotBlank()) Text(row.value, style = MaterialTheme.typography.bodySmall)
            row.fields["accuracyMeters"]?.let { Text("Position accuracy: $it m", style = MaterialTheme.typography.bodySmall) }
            row.fields["provider"]?.let { Text("Map source: $it", style = MaterialTheme.typography.bodySmall) }
            row.fields["retrievedAt"]?.let { Text("Retrieved ${signalLookupDate(it)}", style = MaterialTheme.typography.bodySmall) }
            row.fields["expiresAt"]?.let { Text("Expires ${signalLookupDate(it)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun signalLookupDate(value: String): String = value.toLongOrNull()?.let {
    runCatching { signalDateTime(it) }.getOrDefault(value)
} ?: value

@Composable
internal fun SignalLookupServiceControls(state: SignalState, station: SignalStation) {
    val lookups = state.settings.lookups
    var expanded by remember { mutableStateOf(false) }
    var nearby by remember(lookups.nearbyEndpoint) { mutableStateOf(lookups.nearbyEndpoint) }
    var address by remember(lookups.addressEndpoint) { mutableStateOf(lookups.addressEndpoint) }
    var radio by remember(lookups.radioEndpoint) { mutableStateOf(lookups.radioEndpoint) }
    var radius by remember(lookups.radiusMeters) { mutableStateOf(lookups.radiusMeters.toString()) }
    val meters = radius.toIntOrNull()?.takeIf { it in 100..1000 }
    val nearbyValid = SignalLookupProviders.endpointValid(nearby.trim())
    val addressValid = SignalLookupProviders.endpointValid(address.trim())
    val radioValid = SignalLookupProviders.endpointValid(radio.trim())
    val changed = nearby.trim() != lookups.nearbyEndpoint || address.trim() != lookups.addressEndpoint ||
        radio.trim() != lookups.radioEndpoint || meters != lookups.radiusMeters
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }) { Text(if (expanded) "Hide lookup service settings" else "Lookup service settings") }
        if (expanded) {
            Text("Optional: change the compatible services used for reviewed lookups. Saving does not enable a lookup or send a request.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(nearby, { nearby = it.take(2048) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Nearby places · Overpass-compatible endpoint") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, enabled = !state.busy, isError = !nearbyValid)
            OutlinedTextField(address, { address = it.take(2048) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Address candidates · Overpass-compatible endpoint") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, enabled = !state.busy, isError = !addressValid)
            Text("Address search retrieves nearby address candidates; it does not confirm which address you occupy.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(radio, { radio = it.take(2048) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Radio location · beaconDB-compatible endpoint") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, enabled = !state.busy, isError = !radioValid)
            if (!nearbyValid || !addressValid || !radioValid) Text("Use HTTPS endpoints without credentials, query parameters or fragments.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(radius, { radius = it.take(4) }, modifier = Modifier.fillMaxWidth(), label = { Text("Map search radius · 100–1000 meters") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !state.busy, isError = meters == null)
            Button(onClick = {
                if (meters != null) station.updateSettings(state.settings.copy(lookups = lookups.copy(
                    nearbyEndpoint = nearby.trim(), addressEndpoint = address.trim(), radioEndpoint = radio.trim(), radiusMeters = meters)))
            }, enabled = !state.busy && changed && nearbyValid && addressValid && radioValid && meters != null) { Text("Save lookup services") }
            Text(if (changed) "Service changes are not saved yet." else "These are the saved lookup services.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SignalLookupReviewDialog(state: SignalState, station: SignalStation, onDetail: (String) -> Unit) {
    val review = state.lookupReview ?: return
    AlertDialog(onDismissRequest = station::dismissLookupReview,
        title = { Text("Review external lookup") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (review.kind) { "nearby" -> "Nearby map places"; "address" -> "Nearby address"; "radio_location" -> "Radio location estimate"; else -> "Context lookup" },
                    style = MaterialTheme.typography.titleMedium)
                if (state.busy) Text("Sending reviewed lookup…", Modifier)
                Text(review.disclosure)
                Text("Destination", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(review.endpoint) }
                TextButton(onClick = { station.dismissLookupReview(); onDetail(review.recordId) }) { Text("Inspect source record · ${review.recordId}") }
                Text("Exact outgoing request", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(review.payload) }
                Text("Send lookup contacts this service. It does not send a question to your answer provider.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = station::sendReviewedLookup, enabled = !state.busy) { Text("Send lookup") } },
        dismissButton = { TextButton(onClick = station::dismissLookupReview) { Text("Cancel") } })
}
