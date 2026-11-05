package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val signalRootPages = listOf(SignalPage.Today, SignalPage.Conversation, SignalPage.Home, SignalPage.History)

@Composable
internal fun SignalCompactNavigation(page: SignalPage, canBack: Boolean, onBack: () -> Unit,
    onDestination: (SignalPage) -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canBack) TextButton(onBack, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Back") }
        Box(Modifier.weight(1f)) {
            TextButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("signal-compact-navigation").semantics { contentDescription = "Navigate Signal Station, ${page.title}" }) {
                Text(page.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                signalRootPages.forEach { destination ->
                    DropdownMenuItem(text = { Text(destination.title) }, onClick = { expanded = false; onDestination(destination) })
                }
            }
        }
        TextButton(onSettings, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Settings") }
    }
}
