package com.lukesteuber.signalstation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Interaction tests use the public app screen and a local fake; no collector or provider is invoked. */
class SignalLearningUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun onboardingCanReachAndCaptureWithoutWatchOrProvider() {
        val station = LearningUiStation(baseState().copy(settings = SignalSettings()))
        show(station)

        compose.onNodeWithText("Capture once").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Phone battery").performScrollTo().performClick()
        compose.onNodeWithText("Continue with these sources").performScrollTo().performClick()
        compose.onNodeWithText("Review collection permissions").performScrollTo().performClick()
        compose.onNodeWithText("Open Capture").performScrollTo().performClick()
        compose.onNodeWithText("A record of right now").assertExists()
        compose.onNodeWithText("Capture readings").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(station.state.value.settings.onboardingComplete)
            assertEquals(setOf(BATTERY), station.state.value.settings.enabled)
            assertFalse(station.state.value.settings.learningEnabled)
            assertTrue(station.state.value.watches.isEmpty())
            assertTrue(station.state.value.configuredProviders.isEmpty())
            assertEquals(1, station.permissionRequests)
            assertEquals(1, station.captures)
            assertEquals(0, station.providerRequests)
            assertEquals(emptyList(), station.observationStarts)
        }
    }

    @Test fun disabledSourceMemoryLeavesComposerButRemainsInspectable() {
        val memory = memory("battery-pattern", "Battery readings are usually steady")
        val station = LearningUiStation(baseState().copy(
            settings = readySettings().copy(learningEnabled = true),
            memories = listOf(memory), memorySuggestions = listOf(memory),
        ))
        show(station)

        compose.onNodeWithText("Ask", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your question").performScrollTo().performTextInput("What do my battery readings show?")
        compose.onNodeWithText("1 relevant memory selected for this question.").performScrollTo().assertExists()
        compose.onNodeWithText(memory.text).assertExists()

        compose.runOnIdle {
            station.state.value = station.state.value.copy(settings = station.state.value.settings.copy(enabled = emptySet()))
        }
        compose.onNodeWithText("0 relevant memories selected for this question.").performScrollTo().assertExists()
        compose.onNodeWithText(memory.text).assertDoesNotExist()
        compose.onNodeWithText("Review suggested context").assertDoesNotExist()

        compose.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Remembered").performScrollTo().performClick()
        compose.onNodeWithText(memory.text).performScrollTo().assertExists()
        compose.onNodeWithText("Not available for model context while its source is disabled or its evidence needs review.").performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(listOf(memory), station.state.value.memories)
            assertEquals(0, station.providerRequests)
        }
    }

    @Test fun deletionKeepsNoNotesUnlessIndividuallySelected() {
        val first = memory("first", "The desk is a familiar workspace")
        val second = memory("second", "Afternoon readings vary")
        val preview = SignalDeletionPreview(setOf("capture-1", "report-1"), 2, listOf(first, second))
        val station = LearningUiStation(baseState().copy(deletionPreview = preview))
        show(station)

        compose.onNodeWithContentDescription(first.text).performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription(second.text).performScrollTo().assertIsOff()
        compose.onNodeWithText("Delete selected data").performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet()), station.retainedNotes) }

        compose.runOnIdle { station.state.value = station.state.value.copy(deletionPreview = preview) }
        compose.onNodeWithContentDescription(first.text).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithContentDescription(second.text).performScrollTo().assertIsOff()
        compose.onNodeWithText("Delete selected data").performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet(), setOf(first.id)), station.retainedNotes) }
    }

    @Test fun observationDefaultsToOneHourAndCanBeStopped() {
        val station = LearningUiStation(baseState())
        show(station)

        compose.onNodeWithText("Observe for a while").performScrollTo().performClick()
        compose.onNodeWithText("Session length: 1 hour").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Phone battery").performScrollTo().assertIsOn()
        compose.onNodeWithText("Start observation session").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(60 to setOf(BATTERY)), station.observationStarts) }

        compose.onNodeWithText("Stop observing").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, station.observationStops)
            assertEquals("stopped", station.state.value.observationSession?.state)
            assertEquals(0, station.providerRequests)
            assertFalse(station.state.value.settings.learningEnabled)
        }
    }

    @Test fun destinationsAndMemoryEvidenceRemainReachableAtTwoHundredPercentFont() {
        val example = memory("fixture-baseline", "Phone battery usually stays between 68% and 84%")
        val capture = SignalRecord(
            id = "fixture-capture", threadId = "fixture-thread", createdAt = NOW,
            question = "Captured readings", summary = "Phone battery 78% · saved on this phone",
            provider = "local", model = "", state = "ready", kind = "capture",
            observations = listOf(SignalObservation(BATTERY, "phone", "78", "%", NOW, measuredAt = NOW)),
            sourceKeys = setOf(BATTERY),
        )
        val station = LearningUiStation(baseState().copy(
            settings = readySettings().copy(learningEnabled = true),
            learningStatus = "Learning is on. New patterns need your review before use.",
            records = listOf(capture), memories = listOf(example), historyCount = 1,
        ))
        show(station, fontScale = 2f)

        compose.onNodeWithText("Capture once").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("today-font-200.png")
        compose.onNode(hasText("Ask") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("What would you like to ask?").performScrollTo().assertIsDisplayed()

        compose.onNode(hasText("Memory") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Remembered").performScrollTo().performClick()
        compose.onNodeWithText(example.text).performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("memory-font-200.png")
        compose.onNodeWithText("Why this?").performScrollTo().performClick()
        compose.onNode(hasText(example.coverage) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()

        compose.onNode(hasText("Settings") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Capture works without a provider key. Add a key when you want model analysis.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Today") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Capture once").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, station.providerRequests) }
    }

    @Test fun savedQuestionOpensAndReviewDoesNotSendAtLargeText() {
        val recipe = SavedQuestion("saved", "Morning check", "What changed?", searchHistory = true)
        val station = LearningUiStation(baseState().copy(settings = readySettings(), configuredProviders = setOf("openai"), savedQuestions = listOf(recipe)))
        show(station, fontScale = 2f)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Morning check"))
        compose.onNodeWithText("Morning check").performClick()
        compose.onNodeWithText("Question about saved history").performScrollTo().assertExists()
        compose.onNodeWithText("Review context").performScrollTo().performClick()
        compose.onNodeWithText("Review before sending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show exact message text").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, station.providerRequests); assertEquals(0, station.captures); assertEquals(0, station.permissionRequests) }
        compose.onNodeWithText("Send question").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, station.providerRequests) }
    }

    private fun show(station: LearningUiStation, fontScale: Float? = null) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setLearningTestContent {
                if (fontScale == null) SignalScreen(station, standalone = true)
                else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    SignalScreen(station, standalone = true)
                }
            }
        }
        compose.waitForIdle()
    }

    /** These screenshots contain only the synthetic fixture above, captured inside emulator instrumentation. */
    private fun captureFixtureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("signal-ui-review"))
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, name)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Emulator screenshot unavailable" }
        try {
            FileOutputStream(file).use { output -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally { screenshot.recycle() }
        Log.i("SignalLearningUiTest", "Fixture screenshot: ${file.absolutePath}")
        // Test APK teardown removes app-scoped files; retain synthetic images in the test log.
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP).chunked(3000).forEachIndexed { index, chunk ->
            Log.i("SignalLearningUiTest", "SIGNAL_SCREENSHOT:$name:$index:$chunk")
        }
    }

    private fun readySettings() = SignalSettings(onboardingComplete = true, enabled = setOf(BATTERY))
    private fun baseState() = SignalState(
        initialized = true, historyReady = true, settings = readySettings(),
        sources = listOf(SignalSource(BATTERY, "Phone battery", "Phone")),
    )
    private fun memory(id: String, text: String) = SignalMemory(
        id = id, fingerprint = id, kind = "baseline", text = text, state = "confirmed",
        createdAt = NOW, evaluatedAt = NOW, confirmedAt = NOW, sourceKeys = setOf(BATTERY),
        coverage = "Ten separate measurements across five observed days.",
    )

    companion object {
        private const val BATTERY = "phone.battery"
        private const val NOW = 1_789_000_000_000L
    }
}

private fun MainActivity.setLearningTestContent(content: @Composable () -> Unit) {
    ComposeView(this).also { it.setContent(content); setContentView(it) }
}

private class LearningUiStation(initial: SignalState) : SignalStation {
    override val available = true
    override val state = MutableStateFlow(initial)
    var permissionRequests = 0
    var captures = 0
    var providerRequests = 0
    var observationStops = 0
    val observationStarts = mutableListOf<Pair<Int, Set<String>>>()
    val retainedNotes = mutableListOf<Set<String>>()

    override fun updateSettings(settings: SignalSettings) { state.value = state.value.copy(settings = settings) }
    override fun requestPermissions() { permissionRequests++ }
    override fun capture() { captures++ }
    override fun applyDeletion(keepAsNotes: Set<String>) {
        retainedNotes += keepAsNotes.toSet()
        state.value = state.value.copy(deletionPreview = null)
    }
    override fun dismissDeletion() { state.value = state.value.copy(deletionPreview = null) }
    override fun setUseMemory(enabled: Boolean) { state.value = state.value.copy(useMemory = enabled) }
    override fun startObservation(minutes: Int, sources: Set<String>) {
        observationStarts += minutes to sources.toSet()
        state.value = state.value.copy(observationSession = SignalObservationSession(
            id = "test-session", startedAt = 1_789_000_000_000L,
            endsAt = 1_789_000_000_000L + minutes * 60_000L, sourceKeys = sources,
        ))
    }
    override fun stopObservation() {
        observationStops++
        state.value = state.value.copy(observationSession = state.value.observationSession?.copy(state = "stopped", status = "Stopped by you."))
    }
    override fun saveProvider(model: String, endpoint: String, key: String) = Unit
    override fun saveKey(provider: String, key: String) = Unit
    override fun testProvider() { providerRequests++ }
    override fun ask(text: String, searchHistory: Boolean) { state.value = state.value.copy(questionReview = SignalQuestionReview(text, listOf("user" to text), 0, 0, 0)) }
    override fun sendReviewedQuestion() { providerRequests++ }
    override fun dismissQuestionReview() { state.value = state.value.copy(questionReview = null) }
    override fun openSavedQuestion(id: String) { state.value = state.value.copy(savedQuestionDraft = state.value.savedQuestions.first { it.id == id }, savedQuestionOpenToken = "opened", threadId = "saved-draft") }
    override fun analyzeRecord(id: String) { providerRequests++ }
    override fun survey() { providerRequests++ }
    override fun installWatchApp() = Unit
    override fun openPermissionSettings() = Unit
    override fun recordOnWatch() = Unit
    override fun cancel() = Unit
    override fun newThread() = Unit
    override fun selectRecord(id: String) = Unit
    override fun resumeThread(id: String) = Unit
    override fun attachRecord(id: String) = Unit
    override fun compareRecords(first: String, second: String) = Unit
    override fun deleteRecord(id: String) = Unit
    override fun deleteThread(id: String) = Unit
    override fun clearHistory() = Unit
    override fun shareHistory(format: String) = Unit
    override fun searchWeatherPlaces(query: String) = Unit
    override fun startWakeListening() = Unit
    override fun stopWakeListening() = Unit
    override fun reviewWakeOnWatch() = Unit
    override fun saveFieldTest(trial: SignalFieldTest) = Unit
    override fun summarizeChanges(id: String) = Unit
    override fun dismissWakeDraft() = Unit
    override fun scanPresence() = Unit
    override fun enrollPresenceTarget(candidate: SignalRadioCandidate, label: String) = Unit
    override fun removePresenceTarget(id: String) = Unit
    override fun savePlaceFence(fence: SignalPlaceFence) = Unit
    override fun removePlaceFence(id: String) = Unit
    override fun lookupNearbyPlace() = Unit
    override suspend fun exportHistory() = "[]"
}
