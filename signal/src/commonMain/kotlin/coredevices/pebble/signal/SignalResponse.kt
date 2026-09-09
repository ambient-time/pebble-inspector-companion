package coredevices.pebble.signal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import io.ktor.http.Url

internal fun signalResponseLink(uri: String): Boolean =
    uri.none { it.isISOControl() } && uri.startsWith("http", ignoreCase = true) && runCatching {
        val url = Url(uri)
        url.protocol.name in setOf("http", "https") && url.host.isNotBlank() && url.user == null && url.password == null
    }.getOrDefault(false)

@Composable
fun SignalResponse(answer: String, fallback: String = "No answer saved.") {
    val uriHandler = LocalUriHandler.current
    val webLinks = remember(uriHandler) { object : UriHandler {
        override fun openUri(uri: String) {
            if (signalResponseLink(uri)) runCatching { uriHandler.openUri(uri) }
        }
    } }
    SelectionContainer {
        if (answer.isBlank()) Text(fallback)
        else CompositionLocalProvider(LocalUriHandler provides webLinks) {
            Markdown(
                markdownState = rememberMarkdownState(answer),
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = remember { NoOpImageTransformerImpl() },
                typography = markdownTypography(
                    h1 = MaterialTheme.typography.headlineSmall,
                    h2 = MaterialTheme.typography.titleLarge,
                    h3 = MaterialTheme.typography.titleMedium,
                    h4 = MaterialTheme.typography.titleSmall,
                    h5 = MaterialTheme.typography.titleSmall,
                    h6 = MaterialTheme.typography.titleSmall,
                ),
                loading = { Text("Formatting response…") },
                error = { Text(answer) },
            )
        }
    }
}
