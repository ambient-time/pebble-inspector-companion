package coredevices.pebble.signal

data class SignalWakeState(val token: Long = 0, val phase: String = "stopped", val status: String = "Wake listening is off.", val draft: String = "")

/** Generation checks apply to permission, model loading and recognition callbacks alike. */
class SignalWakeSession {
    var state = SignalWakeState()
        private set

    fun begin(): Long {
        state = SignalWakeState(state.token + 1, "permission", "Preparing microphone access…")
        return state.token
    }
    fun update(token: Long, phase: String, status: String): Boolean {
        if (token != state.token || state.phase in setOf("stopped", "error", "draft")) return false
        state = state.copy(phase = phase, status = status)
        return true
    }
    fun draft(token: Long, text: String): Boolean {
        if (token != state.token || state.phase != "recording" || text.isBlank()) return false
        state = state.copy(phase = "draft", status = "Voice draft ready. Review before sending.", draft = text.trim().take(8000))
        return true
    }
    fun stop(status: String = "Wake listening is off.") {
        state = state.copy(token = state.token + 1, phase = "stopped", status = status)
    }
    fun dismiss() { stop(); state = state.copy(draft = "") }

    companion object {
        fun isWakePhrase(text: String): Boolean = text.trim().lowercase().split(Regex("\\s+")).joinToString(" ") == "go go gadget"
    }
}
