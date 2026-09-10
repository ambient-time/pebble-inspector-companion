package coredevices.pebble.signal

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SignalSettings(
    val onboardingComplete: Boolean = false,
    val learningEnabled: Boolean = false,
    val healthHistoryDays: Int = 7,
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
    val id: String = "",
    val number: Double? = null,
    val boolean: Boolean? = null,
    val metric: String = "",
    val sampleCount: Int? = null,
    val accuracy: Int? = null,
    val fields: Map<String, String> = emptyMap(),
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
    val sessionId: String = "",
    val memoryReferences: Map<String, Long> = emptyMap(),
    val coverage: List<SignalSourceCoverage> = emptyList(),
)

@Serializable
data class SignalSourceCoverage(
    val key: String, val observed: Int, val retained: Int, val omitted: Int,
    val attempted: Boolean = true, val status: String = "available",
)

data class SignalSource(val key: String, val name: String, val group: String, val available: Boolean = true)
data class SignalWatch(val id: String, val name: String, val connected: Boolean, val connectionId: String = id, val appOpen: Boolean = false, val connectionStatus: String = "")
data class SignalState(
    val diagnostics: SignalDiagnosticReport? = null,
    val savedQuestions: List<SavedQuestion> = emptyList(),
    val savedQuestionDraft: SavedQuestion? = null,
    val savedQuestionOpenToken: String = "",
    val questionReview: SignalQuestionReview? = null,
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
    val memories: List<SignalMemory> = emptyList(),
    val memorySuggestions: List<SignalMemory> = emptyList(),
    val useMemory: Boolean = true,
    val learningStatus: String = "Learning is off. Enable it when you are ready.",
    val observationSession: SignalObservationSession? = null,
    val sessions: List<SignalObservationSession> = emptyList(),
    val sourceStatus: List<SignalSourceStatus> = emptyList(),
    val deletionPreview: SignalDeletionPreview? = null,
    val historyHasMore: Boolean = false,
    val historyCount: Long = 0,
    val historyLoading: Boolean = false,
    val historyReady: Boolean = false,
    val storageBytes: Long = 0,
    val healthStatus: String = "Health Connect is optional.",
    val selectedRecord: SignalRecord? = null,
    val selectedRecordLoading: Boolean = false,

)

interface SignalStation {
    val available: Boolean
    val state: StateFlow<SignalState>
    fun updateSettings(settings: SignalSettings)
    fun saveProvider(model: String, endpoint: String, key: String)
    fun saveKey(provider: String, key: String)
    fun saveQuestion(title: String, question: String, history: Boolean, asNew: Boolean = false) {}
    fun openSavedQuestion(id: String) {}
    fun deleteSavedQuestion(id: String) {}
    fun dismissSavedQuestion() {}
    fun dismissQuestionReview() {}
    fun sendReviewedQuestion() {}
    fun checkAnswerSetup() {}
    fun shareDiagnostics() {}
    fun testProvider()
    fun ask(text: String, searchHistory: Boolean = false)
    fun capture()
    fun analyzeRecord(id: String)
    fun installWatchApp()
    fun openPermissionSettings()
    fun survey()
    fun checkWatchConnection() {}
    fun recordOnWatch()
    fun cancel()
    fun newThread()
    fun selectRecord(id: String)
    fun loadConversation() = Unit
    fun resumeThread(id: String)
    fun attachRecord(id: String)
    fun compareRecords(first: String, second: String)
    fun deleteRecord(id: String)
    fun deleteThread(id: String)
    fun clearHistory()
    fun shareHistory(format: String)
    fun requestPermissions()
    fun recoverSource(key: String) = openPermissionSettings()
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
    fun enableLearning(enabled: Boolean) {}
    fun reviewMemory(id: String, action: String, text: String = "") {}
    fun saveMemoryNote(text: String) {}
    fun suggestMemory(query: String) {}
    fun setUseMemory(enabled: Boolean) {}
    fun startObservation(minutes: Int, sources: Set<String>) {}
    fun stopObservation() {}
    fun loadMoreHistory() {}
    fun searchSavedHistory(query: String) {}
    fun previewDeleteRecords(ids: Set<String>?) {}
    fun previewForgetMemory(id: String) {}
    fun applyDeletion(keepAsNotes: Set<String>) {}
    fun dismissDeletion() {}
    fun requestHealthPermissions(days: Int = 7, background: Boolean = false) {}
    fun importHealth() {}
    suspend fun exportHistory(): String
}

data class SignalReply(val text: String, val summary: String)
interface SignalSecrets {
    suspend fun get(provider: String): String?
    suspend fun put(provider: String, key: String)
}

/** Exact messages reviewed on the phone; never persisted automatically. */
data class SignalQuestionReview(
    val question: String,
    val messages: List<Pair<String, String>>,
    val recordCount: Int,
    val memoryCount: Int,
    val omittedRecords: Int,
) {
    val bytes: Int get() = messages.sumOf { it.second.encodeToByteArray().size }
}

@Serializable
data class SavedQuestion(
    val id: String, val title: String, val question: String,
    val sourceKeys: Set<String> = emptySet(), val attachmentIds: Set<String> = emptySet(),
    val searchHistory: Boolean = false, val useMemory: Boolean = true,
)

@Serializable
data class SignalDiagnosticReport(
    val build: String, val stage: String, val result: String,
    val elapsedMs: Long = 0, val payloadBytes: Int = 0,
)
