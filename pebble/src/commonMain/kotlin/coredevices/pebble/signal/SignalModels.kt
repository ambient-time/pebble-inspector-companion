package coredevices.pebble.signal

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SignalSettings(
    val provider: String = "openai",
    val model: String = "gpt-4.1-mini",
    val endpoint: String = "",
    val recognition: String = "openai",
    val watchId: String = "",
    val enabled: Set<String> = emptySet(),
    val confirmTranscript: Boolean = true,
    val reducedMotion: Boolean = false,
)

@Serializable
data class SignalObservation(
    val key: String,
    val source: String,
    val value: String = "",
    val unit: String = "",
    val collectedAt: Long,
    val measuredAt: Long? = null,
    val status: String = "available",
    val date: String? = null,
    val period: String = "current",
    val windowStart: Long? = null,
    val windowEnd: Long? = null,
)

@Serializable
data class SignalRecord(
    val id: String,
    val threadId: String,
    val createdAt: Long,
    val question: String,
    val answer: String = "",
    val summary: String = "",
    val provider: String,
    val model: String,
    val watchId: String = "",
    val state: String = "working",
    val observations: List<SignalObservation> = emptyList(),
    val sourceKeys: Set<String> = emptySet(),
    val references: List<String> = emptyList(),
    val delivered: Boolean = false,
    val endpoint: String = "",
)

data class SignalSource(val key: String, val name: String, val group: String, val available: Boolean = true)
data class SignalWatch(val id: String, val name: String, val connected: Boolean)
data class SignalState(
    val settings: SignalSettings = SignalSettings(),
    val records: List<SignalRecord> = emptyList(),
    val sources: List<SignalSource> = emptyList(),
    val watches: List<SignalWatch> = emptyList(),
    val busy: Boolean = false,
    val status: String = "Choose providers and collection sources in Settings.",
    val threadId: String = "",
    val selectedRecordId: String? = null,
    val configuredProviders: Set<String> = emptySet(),
)

interface SignalStation {
    val available: Boolean
    val state: StateFlow<SignalState>
    fun updateSettings(settings: SignalSettings)
    fun saveKey(provider: String, key: String)
    fun testProvider()
    fun ask(text: String, searchHistory: Boolean = false)
    fun survey()
    fun recordOnWatch()
    fun cancel()
    fun newThread()
    fun selectRecord(id: String)
    fun resumeThread(id: String)
    fun attachRecord(id: String)
    fun compareRecords(first: String, second: String)
    fun deleteRecord(id: String)
    fun deleteThread(id: String)
    fun clearHistory()
    fun shareHistory(format: String)
    fun requestPermissions()
    suspend fun exportHistory(): String
}

data class SignalReply(val text: String, val summary: String)
interface SignalSecrets {
    suspend fun get(provider: String): String?
    suspend fun put(provider: String, key: String)
}
