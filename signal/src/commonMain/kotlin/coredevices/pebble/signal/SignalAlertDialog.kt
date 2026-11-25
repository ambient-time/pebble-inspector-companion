package coredevices.pebble.signal

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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

/** A dialog does not scroll its own text, so a long message would lose its last lines at large font sizes. */
@Composable
fun SignalDialogText(text: String) { Text(text, Modifier.verticalScroll(rememberScrollState())) }
