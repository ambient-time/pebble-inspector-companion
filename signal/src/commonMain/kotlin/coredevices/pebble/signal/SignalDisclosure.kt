package coredevices.pebble.signal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun SignalDisclosureHeader(
    title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    section: Boolean = true, repeated: Boolean = false,
) {
    OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (repeated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (repeated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
            if (section) heading()
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = if (section) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
}

@Composable
internal fun SignalDisclosure(key: String, title: String, summary: String = "", content: @Composable ColumnScope.() -> Unit) {
    var expanded by signalUiState("disclosure.$key") { false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SignalDisclosureHeader(title, summary, expanded, { expanded = !expanded })
        if (expanded) content()
    }
}

@Composable
internal fun SignalWifiFilters(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "All", "no_password" to "No password", "credentials" to "Credentials", "unknown" to "Unknown access").forEach { (key, label) ->
            FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
    Text("No password means open or OWE encryption advertised. Public access and browser sign-in remain unknown until connected.", style = MaterialTheme.typography.bodySmall)
}

internal fun signalWifiMatches(security: String, filter: String): Boolean {
    val access = SignalRadioPresentation.wifiAccess(security)
    return when (filter) {
        "no_password" -> access.noPassword
        "credentials" -> access == SignalWifiAccess.CREDENTIALS
        "unknown" -> access == SignalWifiAccess.UNKNOWN
        else -> true
    }
}
