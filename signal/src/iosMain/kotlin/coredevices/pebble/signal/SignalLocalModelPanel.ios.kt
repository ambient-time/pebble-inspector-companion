package coredevices.pebble.signal

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text

@Composable internal actual fun SignalLocalModelPanel(provider: String) {
    Text("This on-device source is available only in the Android companion.")
}
