package coredevices.pebble.signal

/** One-use consent for the exact question and provider shown on the watch. */
class SignalWakeReview {
    data class Pending(val request: Int, val wakeToken: Long, val text: String, val settings: SignalSettings, val expiresAt: Long)
    var pending: Pending? = null
        private set

    fun offer(request: Int, wakeToken: Long, text: String, settings: SignalSettings, now: Long): Boolean {
        if (request <= 0 || text.isBlank() || '\u0000' in text || '\u0000' in context(settings) || text.encodeToByteArray().size > 400 || context(settings).encodeToByteArray().size > 350) return false
        pending = Pending(request, wakeToken, text, settings, now + 120_000)
        return true
    }
    fun claim(request: Int, wakeToken: Long, text: String, settings: SignalSettings, now: Long): Pending? {
        val value = pending ?: return null
        if (value.request != request || value.wakeToken != wakeToken || value.text != text || value.settings != settings || now >= value.expiresAt) return null
        pending = null
        return value
    }
    fun invalidate() { pending = null }
    companion object {
        fun context(settings: SignalSettings) = "${settings.provider} / ${settings.model}${settings.endpoint.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}\nQuestion only; no saved readings or conversation."
    }
}
