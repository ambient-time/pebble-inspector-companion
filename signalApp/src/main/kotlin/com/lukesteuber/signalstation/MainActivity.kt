package com.lukesteuber.signalstation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.signal.SignalScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SignalApplication
        setContent {
            var showWatch by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            var connectionError by remember { mutableStateOf("") }
            SignalScreen(app.station, standalone = true, onManageWatch = { showWatch = true })
            if (showWatch) AlertDialog(
                onDismissRequest = { showWatch = false },
                title = { Text("Use your Pebble app") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Keep your watch paired where it is. Choose the app that already manages it, then select the watch in Settings.")
                    Text("Watch connection is a development preview. Installation remains paused while the earlier settings-wipe report is investigated.")
                    if (connectionError.isNotBlank()) Text(connectionError)
                    val apps = app.watchLink.availableApps()
                    if (apps.isEmpty()) Text("No compatible Pebble app is installed. Phone features remain available.")
                    apps.forEach { pkg ->
                        TextButton(onClick = { scope.launch { try { app.watchLink.selectApp(pkg); showWatch = false } catch (_: Exception) { connectionError = "That Pebble app is unavailable. Choose it again when ready." } } }) {
                            Text(runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { showWatch = false }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { scope.launch { app.watchLink.selectApp(null); showWatch = false } }) { Text("Disconnect Signal Station") } },
            )
        }
    }
}
