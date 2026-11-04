package coredevices.pebble.signal

import androidx.compose.runtime.Composable

/** Must match localmodels.LocalModelPolicy; review and dispatch use this same byte contract. */
internal object SignalLocalModels {
    val providers = setOf("local-nano", "local-gemma")
    fun defaultModel(provider: String) = when (provider) {
        "local-nano" -> "android-system"
        "local-gemma" -> "local-file"
        "openai" -> "gpt-4.1-mini"
        else -> ""
    }
    fun validate(messages: List<Pair<String, String>>): Int {
        if (messages.isEmpty() || messages.last().first != "user" ||
            messages.any { it.first !in setOf("system", "user", "assistant") || '\u0000' in it.second })
            throw SignalProviderException("The local conversation is invalid.")
        val bytes = messages.sumOf { it.second.encodeToByteArray().size.toLong() + it.first.length + 4 }
        if (bytes > 6000) throw SignalProviderException("Local context exceeds 6,000 bytes. Choose fewer readings or start a new conversation. Nothing was sent.")
        return bytes.toInt()
    }
}

@Composable internal expect fun SignalLocalModelPanel(provider: String)
