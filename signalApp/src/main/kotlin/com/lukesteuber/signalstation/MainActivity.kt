package com.lukesteuber.signalstation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import coredevices.pebble.signal.SignalScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class SignalUiViewModel : androidx.lifecycle.ViewModel() {
    val session = coredevices.pebble.signal.SignalUiSession()
}

class MainActivity : ComponentActivity() {
    override fun onStop() {
        (application as SignalApplication).station.stopLiveSignals()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SignalApplication
        val ui = androidx.lifecycle.ViewModelProvider(this)[SignalUiViewModel::class.java].session
        setContent {
            var showWatch by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            var connectionError by remember { mutableStateOf("") }
            var selecting by remember { mutableStateOf(false) }
            var selectedHost by remember { mutableStateOf<String?>(null) }
            val watches by app.watchLink.watches.collectAsState()
            LaunchedEffect(showWatch) {
                if (showWatch) selectedHost = app.watchLink.selectedApp()
            }
            val selectWatchApp: (String?) -> Unit = { pkg ->
                if (!selecting) scope.launch {
                    selecting = true
                    connectionError = ""
                    try {
                        app.watchLink.selectApp(pkg)
                        showWatch = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep diagnostics useful without logging watch identifiers or data.
                        Log.w("SignalWatch", "Host selection failed: ${error.javaClass.simpleName}")
                        connectionError = if (error is IllegalArgumentException) {
                            "That app is no longer available. Open your Pebble app, then reopen this chooser."
                        } else {
                            "Signal Station could not save the connection. Try again. Your watch pairing has not changed."
                        }
                    } finally { selecting = false }
                }
            }
            SignalScreen(app.station, standalone = true, uiSession = ui, onManageWatch = { connectionError = ""; showWatch = true })
            if (showWatch) AlertDialog(
                onDismissRequest = { if (!selecting) showWatch = false },
                title = { Text("Connect through Pebble") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Connect your watch in its existing Pebble app.\n2. Choose that app below.\n3. Select your watch and tap Check connection.")
                    selectedHost?.let { pkg ->
                        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                        Text("Selected app: $label")
                        Text(if (watches.isEmpty()) "No connected watch reported. Open $label and check the watch connection there." else "${watches.size} connected watch${if (watches.size == 1) "" else "es"} available.")
                        TextButton(enabled = !selecting, onClick = {
                            val intent = packageManager.getLaunchIntentForPackage(pkg)
                            if (intent == null || runCatching { startActivity(intent) }.isFailure)
                                connectionError = "Could not open $label. Open it from your phone’s app list."
                        }) { Text("Open $label") }
                    }
                    if (connectionError.isNotBlank()) Text(connectionError)
                    if (selecting) Text("Saving your choice…")
                    val apps = app.watchLink.availableApps()
                    if (apps.isEmpty()) Text("No compatible Pebble app is installed. Phone features remain available.")
                    apps.forEach { pkg ->
                        TextButton(enabled = !selecting, onClick = { selectWatchApp(pkg) }) {
                            Text("Use " + runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
                        }
                    }
                    Text("Watch installation is still paused while the earlier settings-wipe report is investigated. Existing pairing stays in your Pebble app.", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(enabled = !selecting, onClick = { showWatch = false }) { Text("Done") } },
                dismissButton = { TextButton(enabled = !selecting, onClick = { selectWatchApp(null) }) { Text("Disconnect Signal Station") } },
            )
        }
    }
}
