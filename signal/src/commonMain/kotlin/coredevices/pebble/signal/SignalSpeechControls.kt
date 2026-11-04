package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val LocalSignalSpeech = staticCompositionLocalOf<SignalSpeech?> { null }

@Composable
fun SignalSpeechControls(record: SignalRecord, speech: SignalSpeech? = LocalSignalSpeech.current) {
    if (speech == null || !SignalSpeechPolicy.eligible(record)) return
    val playback by speech.state.collectAsState()
    val selected = playback.recordId == record.id
    val active = selected && playback.active
    DisposableEffect(speech, record.id, record.answer, record.state) {
        onDispose { if (speech.state.value.recordId == record.id && speech.state.value.active) speech.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { if (active) speech.stop() else speech.play(record) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(if (active) "Stop reading" else "Listen on phone")
        }
        Text("Reads this saved reply through your Android speech engine with a voice marked offline. No new model request.", style = MaterialTheme.typography.bodySmall)
        if (selected && playback.status.isNotBlank()) Text(playback.status,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    }
}
