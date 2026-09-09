package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal class SignalFieldTestDraft {
    var encoded by mutableStateOf("")
    var startBatteryText by mutableStateOf("")
    var endBatteryText by mutableStateOf("")
}

@Composable
internal fun SignalFieldTestPage(state: SignalState, station: SignalStation, draftState: SignalFieldTestDraft, onBack: () -> Unit) {
    var encoded by draftState::encoded
    var startBatteryText by draftState::startBatteryText
    var endBatteryText by draftState::endBatteryText
    val trial = encoded.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString<SignalFieldTest>(it) }.getOrNull() }
    fun update(value: SignalFieldTest) { encoded = value.encode() }
    val captures = state.records.filter { it.state == "ready" && it.observations.isNotEmpty() }.sortedByDescending { it.createdAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to Capture") }
            Text("20-minute field trial", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("Try ten wake requests and capture readings at three stops. Record what actually happened; these results are your observations.")
            Text("Use Ask to start listening for each attempt. A voice draft ends listening. Return to Capture for each stop. Starting this trial does not start the microphone or collect readings.")
        }
        if (trial == null) {
            item {
                state.records.filter { it.fieldTest != null }.maxByOrNull { it.createdAt }?.fieldTest?.let { saved ->
                    OutlinedButton(onClick = { update(saved); startBatteryText = saved.startBattery?.toString().orEmpty(); endBatteryText = saved.endBattery?.toString().orEmpty() }) { Text("Resume latest saved trial") }
                }
                Button(onClick = { val now = Clock.System.now().toEpochMilliseconds(); update(SignalFieldTest("field-$now", now, buildVersion = state.buildVersion)) }) { Text("Start trial") }
            }
        } else {
            item {
                Text("Started ${signalDateTime(trial.startedAt)}", style = MaterialTheme.typography.labelLarge)
                Text("Aim for about 20 minutes. Finish whenever you need to; incomplete results can still be saved.")
                OutlinedTextField(startBatteryText, { startBatteryText = it.take(3) }, label = { Text("Starting phone battery % · optional") }, singleLine = true,
                    isError = startBatteryText.isNotBlank() && startBatteryText.toIntOrNull() !in 0..100, modifier = Modifier.fillMaxWidth())
                Text("Wake attempts", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            items(10) { index ->
                SignalChoice("Attempt ${index + 1}", trial.attempts[index], listOf("unrecorded" to "Not recorded", "recognized" to "Recognized", "missed" to "Missed"), !state.busy) { result ->
                    update(trial.copy(attempts = trial.attempts.toMutableList().also { it[index] = result }))
                }
            }
            item {
                Text("False triggers: ${trial.falseTriggers?.toString() ?: "not recorded"}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { update(trial.copy(falseTriggers = 0)) }) { Text("Record none") }
                    OutlinedButton(onClick = { update(trial.copy(falseTriggers = (trial.falseTriggers ?: 0) + 1)) }, enabled = (trial.falseTriggers ?: 0) < 999) { Text("Add false trigger") }
                    TextButton(onClick = { update(trial.copy(falseTriggers = (trial.falseTriggers ?: 0) - 1)) }, enabled = (trial.falseTriggers ?: 0) > 0) { Text("Remove one") }
                }
                Text("Count times the phone activated without the wake phrase.", style = MaterialTheme.typography.bodySmall)
                Text("Three stops", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text("Capture at each stop, then link that saved record here. Each stop needs a different capture.")
            }
            items(3) { index ->
                val choices = captures.filter { it.id == trial.stopRecordIds[index] || it.id !in trial.stopRecordIds }
                SignalChoice("Stop ${index + 1}", trial.stopRecordIds[index], listOf("" to "No capture linked") + choices.map { it.id to signalDateTime(it.createdAt) }, !state.busy) { id ->
                    update(trial.copy(stopRecordIds = trial.stopRecordIds.toMutableList().also { it[index] = id }))
                }
                if (trial.stopRecordIds[index].isNotBlank() && captures.none { it.id == trial.stopRecordIds[index] }) Text("Linked capture was deleted. Choose another capture or clear this stop.")
            }
            item {
                OutlinedTextField(endBatteryText, { endBatteryText = it.take(3) }, label = { Text("Ending phone battery % · optional") }, singleLine = true,
                    isError = endBatteryText.isNotBlank() && endBatteryText.toIntOrNull() !in 0..100, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(trial.note, { update(trial.copy(note = it.take(2000))) }, label = { Text("What was useful? What failed?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Note indoor/outdoor conditions, missed words, and whether capture differences helped. Battery change includes other phone activity.", style = MaterialTheme.typography.bodySmall)
                if (trial.endedAt == null) OutlinedButton(onClick = { update(trial.copy(endedAt = Clock.System.now().toEpochMilliseconds())) }) { Text("Finish trial") }
                else Text("Finished ${signalDateTime(trial.endedAt)}")
                val batteriesValid = (startBatteryText.isBlank() || startBatteryText.toIntOrNull() in 0..100) && (endBatteryText.isBlank() || endBatteryText.toIntOrNull() in 0..100)
                val referencesValid = trial.stopRecordIds.filter { it.isNotBlank() }.all { id -> captures.any { it.id == id } }
                Button(onClick = { station.saveFieldTest(trial.copy(startBattery = startBatteryText.toIntOrNull(), endBattery = endBatteryText.toIntOrNull())) }, enabled = !state.busy && batteriesValid && referencesValid) { Text("Save trial") }
                Text("Saved trials appear in History. Save again after each attempt or stop to update this trial. Unsaved entries stay only while this app screen remains open.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
