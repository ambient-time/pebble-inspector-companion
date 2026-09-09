package coredevices.pebble.signal

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SignalSettings(
    val onboardingComplete: Boolean = false,
    val provider: String = "openai",
    val model: String = "gpt-4.1-mini",
    val endpoint: String = "",
    val recognition: String = "openai",
    val watchId: String = "",
    val enabled: Set<String> = emptySet(),
    val confirmTranscript: Boolean = true,
    val reviewWakeOnWatch: Boolean = false,
    val reducedMotion: Boolean = false,
    val weatherLocation: String = "place",
    val weatherPlace: SignalPlace? = null,
    val presenceTargets: List<SignalPresenceTarget> = emptyList(),
    val placeFences: List<SignalPlaceFence> = emptyList(),
)

@Serializable
data class SignalPlace(val name: String, val latitude: Double, val longitude: Double)

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
    val identity: String = "",
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
    val kind: String = "analysis",
    val endpoint: String = "",
    val fieldTest: SignalFieldTest? = null,
)

data class SignalSource(val key: String, val name: String, val group: String, val available: Boolean = true)
data class SignalWatch(val id: String, val name: String, val connected: Boolean, val connectionId: String = id, val appOpen: Boolean = false)
data class SignalState(
    val initialized: Boolean = false,
    val buildVersion: String = "",
    val installStatus: String = "",
    val settings: SignalSettings = SignalSettings(),
    val records: List<SignalRecord> = emptyList(),
    val sources: List<SignalSource> = emptyList(),
    val watches: List<SignalWatch> = emptyList(),
    val watchCapabilities: SignalWatchCapabilities = SignalWatchCapabilities(),
    val busy: Boolean = false,
    val status: String = "Ask a question, or capture readings to explore later.",
    val threadId: String = "",
    val selectedRecordId: String? = null,
    val configuredProviders: Set<String> = emptySet(),
    val weatherPlaces: List<SignalPlace> = emptyList(),
    val weatherSearchStatus: String = "",
    val weatherSearching: Boolean = false,
    val wakePhase: String = "stopped",
    val wakeStatus: String = "Wake listening is off.",
    val wakeDraft: String = "",
    val presenceCandidates: List<SignalRadioCandidate> = emptyList(),
    val presenceStatus: String = "Check nearby signals when ready.",
    val placeLookup: SignalPlaceLookup? = null,
)

interface SignalStation {
    val available: Boolean
    val state: StateFlow<SignalState>
    fun updateSettings(settings: SignalSettings)
    fun saveProvider(model: String, endpoint: String, key: String)
    fun saveKey(provider: String, key: String)
    fun testProvider()
    fun ask(text: String, searchHistory: Boolean = false)
    fun capture()
    fun analyzeRecord(id: String)
    fun installWatchApp()
    fun openPermissionSettings()
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
    fun searchWeatherPlaces(query: String)
    fun startWakeListening()
    fun stopWakeListening()
    fun reviewWakeOnWatch()
    fun saveFieldTest(trial: SignalFieldTest)
    fun summarizeChanges(id: String)
    fun dismissWakeDraft()
    fun scanPresence()
    fun enrollPresenceTarget(candidate: SignalRadioCandidate, label: String)
    fun removePresenceTarget(id: String)
    fun savePlaceFence(fence: SignalPlaceFence)
    fun removePlaceFence(id: String)
    fun lookupNearbyPlace()
    suspend fun exportHistory(): String
}

data class SignalReply(val text: String, val summary: String)
interface SignalSecrets {
    suspend fun get(provider: String): String?
    suspend fun put(provider: String, key: String)
}
