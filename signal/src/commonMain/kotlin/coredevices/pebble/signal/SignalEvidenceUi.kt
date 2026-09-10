package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun signalSupportsLocalChanges(record: SignalRecord): Boolean =
    record.kind in setOf("capture", "presence", "observation", "health_import")

@Composable
internal fun SignalLocalChangesAction(record: SignalRecord, state: SignalState, onChanges: () -> Unit) {
    if (!signalSupportsLocalChanges(record)) return
    val baseline = SignalChanges.baseline(record, state.records, state.settings.enabled)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onChanges, enabled = !state.busy && baseline != null,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("What changed? · local") }
        Text(when {
            record.state != "ready" -> "This record must finish saving before it can be compared."
            record.observations.isEmpty() -> "This record has no readings to compare."
            (record.sourceKeys + record.observations.map { it.key }).none { it in state.settings.enabled } ->
                "Enable a source from this record in Settings to compare its readings."
            baseline == null -> "No compatible earlier record is loaded. Collect another observation or load older activity."
            else -> "Compares with ${signalDateTime(baseline.createdAt)} on this phone. Missing or cached readings stay unknown."
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SignalRecordCoverage(record: SignalRecord, sources: List<SignalSource>) {
    if (record.coverage.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Source coverage", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        record.coverage.forEach { coverage ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sources.firstOrNull { it.key == coverage.key }?.name ?: coverage.key,
                    style = MaterialTheme.typography.titleMedium)
                Text("${coverage.status.replace('_', ' ')} · ${if (coverage.attempted) "collection attempted" else "collection not attempted"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("${coverage.observed} observed · ${coverage.retained} retained · ${coverage.omitted} omitted",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Coverage describes this saved record. An empty or incomplete scan does not establish absence.",
            style = MaterialTheme.typography.bodySmall)
    }
}

internal fun signalSourceNeedsAttention(source: SignalSourceStatus): Boolean =
    source.omitted > 0 || source.status !in setOf("fresh", "available", "ready", "observed", "inside", "outside")

@Composable
internal fun SignalSourceStatusRow(source: SignalSourceStatus, state: SignalState, station: SignalStation,
    onSources: (() -> Unit)? = null) {
    val name = state.sources.firstOrNull { it.key == source.key }?.name ?: source.key
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$name · ${source.status.replace('_', ' ')}", style = MaterialTheme.typography.bodyMedium)
        if (source.reason.isNotBlank()) Text(source.reason.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
        source.measuredAt?.let { Text("Measured ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        source.lastSuccessAt?.let { Text("Last successful reading ${signalDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
        if (source.attempted || source.accepted > 0 || source.omitted > 0) {
            Text("${if (source.attempted) "Collection attempted" else "No new collection attempted"} · ${source.accepted} accepted · ${source.omitted} omitted",
                style = MaterialTheme.typography.bodySmall)
        }
        when {
            source.remedy == "open_settings" -> TextButton(onClick = { station.recoverSource(source.key) }, enabled = !state.busy) {
                Text(if (source.status == "location_services_disabled") "Open location settings" else "Open radio settings")
            }
            source.remedy == "grant_permission" || source.status == "permission_denied" -> {
                if (source.key.startsWith("healthconnect.")) {
                    TextButton(onClick = { station.requestHealthPermissions(state.settings.healthHistoryDays) }, enabled = !state.busy) {
                        Text("Review health access")
                    }
                } else {
                    TextButton(onClick = { station.recoverSource(source.key) }, enabled = !state.busy) { Text("Grant source access") }
                }
            }
            source.remedy == "enable_radio" -> Text("Turn on this radio in Android settings, then capture again.", style = MaterialTheme.typography.bodySmall)
            source.remedy == "retry" -> Text("Try another capture when ready. Other saved readings remain available.", style = MaterialTheme.typography.bodySmall)
        }
        if (onSources != null && signalSourceNeedsAttention(source) && source.remedy !in setOf("grant_permission", "open_settings") && source.status != "permission_denied") {
            TextButton(onClick = onSources) { Text("Review source choices") }
        }
    }
}
