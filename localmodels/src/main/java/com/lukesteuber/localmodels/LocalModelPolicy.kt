package com.lukesteuber.localmodels

/** This contract is also mirrored in Signal's platform-neutral provider adapter. */
object LocalModelPolicy {
    const val NANO = "local-nano"
    const val GEMMA = "local-gemma"
    val providers = setOf(NANO, GEMMA)
    const val MAX_INPUT_BYTES = 6000
    const val MAX_OUTPUT_BYTES = 16 * 1024
    fun validate(messages: List<Pair<String, String>>): Int {
        if (messages.isEmpty() || messages.last().first != "user" ||
            messages.any { it.first !in setOf("system", "user", "assistant") || '\u0000' in it.second })
            throw LocalModelFailure("The local conversation is invalid.")
        val bytes = messages.sumOf { it.second.encodeToByteArray().size.toLong() + it.first.length + 4 }
        if (bytes > MAX_INPUT_BYTES) throw LocalModelFailure(
            "Local context exceeds 6,000 bytes. Choose fewer readings or start a new conversation. Nothing was sent.")
        return bytes.toInt()
    }
    fun prompt(messages: List<Pair<String, String>>): String {
        validate(messages)
        return messages.joinToString("\n\n") { (role, text) -> "$role:\n$text" }
    }
}

class LocalModelFailure(message: String) : Exception(message)
