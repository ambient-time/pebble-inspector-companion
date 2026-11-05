package coredevices.pebble.signal

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val LocalSignalDialogBottomInset = staticCompositionLocalOf { 0.dp }

@Composable
fun SignalAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier.padding(bottom = LocalSignalDialogBottomInset.current),
        dismissButton = dismissButton, title = title, text = text)
}
