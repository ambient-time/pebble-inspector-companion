package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Wraps a generated answer with Report. A reported answer is hidden on this device until shown again.
 * The dialog is owned here, outside the hidden/shown branch, so it stays open while the answer hides.
 */
@Composable
internal fun SignalReportableAnswer(record: SignalRecord, state: SignalState, station: SignalStation, answer: @Composable () -> Unit) {
    if (!SignalAnswerReport.reportable(record)) { answer(); return }
    val info = LocalSignalAppInfo.current
    val reported = record.id in state.reportedAnswers
    var shown by signalUiState("report.shown.${record.id}") { false }
    var step by signalUiState("report.step.${record.id}") { "" }
    var reason by signalUiState("report.reason.${record.id}") { "" }
    var note by signalUiState("report.note.${record.id}") { "" }
    var notice by signalUiState("report.notice.${record.id}") { "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reported && !shown) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("You reported this answer. It is hidden on this device.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { shown = true }) { Text("Show") }
            }
        } else {
            answer()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reported) {
                    TextButton(onClick = { shown = false }) { Text("Hide again") }
                    TextButton(onClick = { station.setAnswerReported(record.id, false); shown = false }) { Text("Clear report mark") }
                } else TextButton(onClick = { reason = ""; note = ""; notice = ""; step = "form" }) { Text("Report") }
            }
        }
    }
    if (step.isBlank()) return
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val text = SignalAnswerReport.text(record, reason, note, state.buildVersion)
    fun copy() { clipboard.setText(AnnotatedString(text)); notice = "Report copied." }
    SignalAlertDialog(
        onDismissRequest = { step = "" },
        title = { Text(if (step == "form") "Report this answer" else "Report ready") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == "form") {
                    Text("What is wrong with it?")
                    Column(Modifier.selectableGroup()) {
                        SignalAnswerReport.reasons.forEach { (id, label) ->
                            Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).selectable(selected = reason == id, role = Role.RadioButton, onClick = { reason = id }),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = reason == id, onClick = null)
                                Text(label, Modifier.weight(1f))
                            }
                        }
                    }
                    OutlinedTextField(note, { note = it.take(SignalAnswerReport.NOTE_LIMIT) }, Modifier.fillMaxWidth(), label = { Text("Note · optional") }, maxLines = 4)
                    Text("A report is an email you send${if (info.contactEmail.isNotBlank()) " to ${info.contactEmail}" else ""}. It contains your question, this answer, the answer source and the app version. It does not contain readings, keys or anything that identifies this device. Reporting hides the answer here.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("The answer is now hidden on this device. If no email app opened, copy the report and send it${if (info.contactEmail.isNotBlank()) " to ${info.contactEmail}" else ""}.")
                    if (notice.isNotBlank()) Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = ::copy) { Text("Copy report") }
                }
            }
        },
        confirmButton = {
            if (step == "form") TextButton(onClick = {
                station.setAnswerReported(record.id, true); shown = false
                notice = if (info.contactEmail.isNotBlank() && runCatching { uri.openUri(SignalAnswerReport.mailto(info.contactEmail, record, reason, note, state.buildVersion)) }.isSuccess) ""
                    else "No email app opened."
                step = "sent"
            }, enabled = reason.isNotBlank()) { Text("Email report") }
            else TextButton(onClick = { step = "" }) { Text("Done") }
        },
        dismissButton = { if (step == "form") TextButton(onClick = { step = "" }) { Text("Cancel") } },
    )
}
