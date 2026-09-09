package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalWakeControls(station: SignalStation, state: SignalState, onUseDraft: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Go go gadget", style = MaterialTheme.typography.titleLarge)
        Text("Say “go go gadget,” wait for the phone to vibrate, then speak your question. Audio stays on this phone. Only the text you review is sent when you tap Send.")
        Text(state.wakeStatus, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.wakePhase in setOf("stopped", "error", "denied")) {
                OutlinedButton(onClick = station::startWakeListening) { Text("Start listening") }
            } else {
                OutlinedButton(onClick = station::stopWakeListening) { Text("Stop listening") }
            }
            TextButton(onClick = station::openPermissionSettings) { Text("Microphone permissions") }
        }
        Text("While active, the microphone processes sound locally and Android shows a notification. Stop listening whenever you like. Sessions end after one hour or when a voice draft is ready.", style = MaterialTheme.typography.bodySmall)
        SignalToggle("Review wake drafts on watch", state.settings.reviewWakeOnWatch, !state.busy,
            "Shows short drafts on the selected watch automatically. Select sends a new question-only conversation; no saved context is included. Long drafts stay on the phone.") {
            station.updateSettings(state.settings.copy(reviewWakeOnWatch = it))
        }
        if (state.wakeDraft.isNotBlank()) {
            Text("Voice draft", style = MaterialTheme.typography.titleMedium)
            Text(state.wakeDraft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = station::reviewWakeOnWatch, enabled = !state.busy && state.watches.any { it.id == state.settings.watchId && it.connected }) { Text("Review on watch") }
                Button(onClick = { onUseDraft(state.wakeDraft); station.dismissWakeDraft() }, enabled = !state.busy) { Text("Review in question field") }
                TextButton(onClick = station::dismissWakeDraft) { Text("Discard voice draft") }
            }
        }
    }
}
