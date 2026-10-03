package coredevices.pebble.signal

import androidx.compose.runtime.Composable

@Composable internal actual fun SignalLocalModelPanel(provider: String) {
    com.lukesteuber.localmodels.LocalModelPanel(provider)
}
