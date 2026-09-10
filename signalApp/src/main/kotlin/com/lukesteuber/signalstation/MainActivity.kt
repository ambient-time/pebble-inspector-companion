package com.lukesteuber.signalstation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import coredevices.pebble.signal.SignalScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SignalApplication
        setContent {
            var showWatch by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            var connectionError by remember { mutableStateOf("") }
            var selecting by remember { mutableStateOf(false) }
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
            SignalScreen(app.station, standalone = true, onManageWatch = { connectionError = ""; showWatch = true })
            if (showWatch) AlertDialog(
                onDismissRequest = { if (!selecting) showWatch = false },
                title = { Text("Use your Pebble app") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Keep your watch paired where it is. Choose the app that already manages it, then select the watch in Settings.")
                    Text("Watch connection is a development preview. Installation remains paused while the earlier settings-wipe report is investigated.")
                    if (connectionError.isNotBlank()) Text(connectionError)
                    if (selecting) Text("Saving your choice…")
                    val apps = app.watchLink.availableApps()
                    if (apps.isEmpty()) Text("No compatible Pebble app is installed. Phone features remain available.")
                    apps.forEach { pkg ->
                        TextButton(enabled = !selecting, onClick = { selectWatchApp(pkg) }) {
                            Text(runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
                        }
                    }
                } },
                confirmButton = { TextButton(enabled = !selecting, onClick = { showWatch = false }) { Text("Done") } },
                dismissButton = { TextButton(enabled = !selecting, onClick = { selectWatchApp(null) }) { Text("Disconnect Signal Station") } },
            )
        }
    }
}
