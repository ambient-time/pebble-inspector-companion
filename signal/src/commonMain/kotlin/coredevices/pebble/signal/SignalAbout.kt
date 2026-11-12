package coredevices.pebble.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the host app knows about its own build. Texts are read from files packaged with the app. */
data class SignalAppInfo(
    val playBuild: Boolean = false,
    val guideUrl: String = "",
    val sourceUrl: String = "",
    val privacyUrl: String = "",
    val contactEmail: String = "",
    val readText: (String) -> String = { "" },
) {
    companion object {
        const val LICENSE = "ABOUT_LICENSE.txt"
        const val MIT = "LICENSE-MIT.txt"
        const val NOTICES = "THIRD_PARTY_NOTICES.txt"
        const val PRIVACY = "PRIVACY.txt"
    }
}

val LocalSignalAppInfo = staticCompositionLocalOf { SignalAppInfo() }

internal fun signalTextBlocks(text: String): List<String> =
    text.replace("\r\n", "\n").split(Regex("\n{2,}")).map { it.trimEnd() }.filter { it.isNotBlank() }

@Composable
private fun signalPackagedText(name: String): List<String>? {
    val info = LocalSignalAppInfo.current
    val blocks by produceState<List<String>?>(null, name, info) {
        value = withContext(Dispatchers.Default) { signalTextBlocks(runCatching { info.readText(name) }.getOrDefault("")) }
    }
    return blocks
}

@Composable
internal fun SignalAboutPage(section: String, buildVersion: String, onSection: (String) -> Unit) {
    val info = LocalSignalAppInfo.current
    val uri = LocalUriHandler.current
    fun open(url: String) { runCatching { uri.openUri(url) } }
    val document = when (section) {
        "about.notices" -> "Third-party notices" to SignalAppInfo.NOTICES
        "about.privacy" -> "Privacy" to SignalAppInfo.PRIVACY
        "about.license" -> "MIT License" to SignalAppInfo.MIT
        else -> null
    }
    if (document != null) {
        val blocks = signalPackagedText(document.second)
        LazyColumn(Modifier.fillMaxSize(), state = signalListState("settings.$section"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick = { onSection("about") }) { Text("Back to About") } }
            item { Text(document.first, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
            if (section == "about.privacy" && info.privacyUrl.isNotBlank()) item {
                OutlinedButton(onClick = { open(info.privacyUrl) }) { Text("Open this policy on the web") }
            }
            when {
                blocks == null -> item { Text("Loading…") }
                blocks.isEmpty() -> item { Text("This text is missing from the app package.") }
                else -> items(blocks) { block ->
                    val underlined = block.lines().takeIf { it.size == 2 && it[1].isNotEmpty() && it[1].all { mark -> mark == '=' || mark == '-' } }
                    if (underlined != null) Text(underlined[0], Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    else SelectionContainer { Text(block, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        return
    }
    val license = signalPackagedText(SignalAppInfo.LICENSE)
    LazyColumn(Modifier.fillMaxSize(), state = signalListState("settings.about"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { onSection("menu") }) { Text("Settings categories") } }
        item {
            Text("About Signal Station", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(buildVersion) }
        }
        item {
            Text("License", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            license.orEmpty().forEach { Text(it) }
        }
        item {
            listOfNotNull(
                "MIT License text" to { onSection("about.license") },
                "Third-party notices" to { onSection("about.notices") },
                "Privacy" to { onSection("about.privacy") },
                ("Source code" to { open(info.sourceUrl) }).takeIf { info.sourceUrl.isNotBlank() },
                ("Download page and guide" to { open(info.guideUrl) }).takeIf { !info.playBuild && info.guideUrl.isNotBlank() },
            ).forEach { (label, action) ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(label) }
            }
        }
        if (info.contactEmail.isNotBlank()) item { SelectionContainer { Text("Contact: ${info.contactEmail}", style = MaterialTheme.typography.bodySmall) } }
    }
}
