package com.lukesteuber.signalstation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
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

    @Test fun emptyEvidenceRecoveryKeepsQuestionAndDoesNotSend() {
        val station = LearningUiStation(baseState().copy(settings = readySettings(), configuredProviders = setOf("openai")))
        show(station)
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Your question").performTextInput("What can I learn here?")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Review question").performClick()
        compose.onNodeWithText("Add evidence").performScrollTo().performClick()
        compose.onNodeWithText("No capture attached.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to question").performScrollTo().performClick()
        compose.onNodeWithText("What can I learn here?").assertExists()
        compose.runOnIdle { assertEquals(0, station.captures); assertEquals(0, station.providerRequests) }
    }

    @Test fun historyQuickDatesApplyWithoutOpeningCustomFields() {
        val station = LearningUiStation(baseState())
        show(station)
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.onNodeWithText("Last 7 days").performScrollTo().performClick()
        compose.runOnIdle {
            val today = java.time.LocalDate.now()
            assertEquals(today.minusDays(6).toString(), station.state.value.scopedHistory.query.fromDate)
            assertEquals(today.toString(), station.state.value.scopedHistory.query.throughDate)
            assertEquals(0, station.providerRequests)
        }
        compose.onNodeWithText("Today").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(station.state.value.scopedHistory.query.fromDate, station.state.value.scopedHistory.query.throughDate) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("More actions"))
        compose.onNodeWithText("More actions").performClick()
        compose.onNodeWithText("Review deletion of these results").performScrollTo().assertExists()
        captureFixtureScreenshot("history-quick-dates.png")
    }

    @Test fun onboardingCanReachAndCaptureWithoutWatchOrProvider() {
        val station = LearningUiStation(baseState().copy(settings = SignalSettings()))
        show(station)

        compose.onNodeWithText("Capture now").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Phone battery").performScrollTo().performClick()
        compose.onNodeWithText("Continue with these sources").performScrollTo().performClick()
        compose.onNodeWithText("Review collection permissions").performScrollTo().performClick()
        compose.onNodeWithText("Open Now").performScrollTo().performClick()
        compose.onNodeWithText("Right now").assertExists()
        compose.onNodeWithText("Capture now").performScrollTo().performClick()

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

    @Test fun setupAnswersPreservesSourcesWithoutStartingCollection() {
        val settings = readySettings().copy(onboardingComplete = false)
        val station = LearningUiStation(baseState().copy(settings = settings))
        show(station)
        compose.onNodeWithText("Set up answers").performScrollTo().performClick()
        compose.onNodeWithText("Model ID").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(settings.copy(onboardingComplete = true), station.state.value.settings)
            assertEquals(0, station.permissionRequests)
            assertEquals(0, station.captures)
            assertEquals(0, station.providerRequests)
        }
    }

    @Test fun setupConnectPebbleReachesGuideWithoutTakingOverPairing() {
        val station = LearningUiStation(baseState().copy(settings = readySettings().copy(onboardingComplete = false)))
        show(station, 2f)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Connect a Pebble"))
        compose.onNodeWithText("Connect a Pebble").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("setup-pebble-font-200.png")
        compose.onNodeWithText("Connect a Pebble").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("About and watch connection"))
        compose.onNodeWithText("About and watch connection").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Download page and guide"))
        compose.onNodeWithText("Download page and guide").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("setup-devices-font-200.png")
        compose.runOnIdle {
            assertTrue(station.state.value.settings.onboardingComplete)
            assertEquals(setOf(BATTERY), station.state.value.settings.enabled)
            assertTrue(station.state.value.watches.isEmpty())
            assertEquals(0, station.permissionRequests)
            assertEquals(0, station.captures)
            assertEquals(0, station.providerRequests)
        }
    }

    @Test fun configuredSetupAskOpensComposerWithoutSending() {
        val station = LearningUiStation(baseState().copy(
            settings = readySettings().copy(onboardingComplete = false, model = "example-model"),
            configuredProviders = setOf("openai"),
        ))
        show(station)
        compose.onNodeWithText("Ask a question").performScrollTo().performClick()
        compose.onNodeWithText("Your question").assertExists()
        compose.runOnIdle {
            assertTrue(station.state.value.settings.onboardingComplete)
            assertEquals(0, station.providerRequests)
            assertEquals(0, station.permissionRequests)
        }
    }

    @Test fun watchHandoffOpensExactOlderReplyAndPreservesPhoneDraft() {
        val earlier = SignalRecord("watch-reply-a", "watch-thread", NOW, "Earlier watch question",
            answer = "The full older reply belongs to request A.", summary = "Short A", provider = "openai", model = "example-model", state = "ready")
        val newer = earlier.copy(id = "watch-reply-b", createdAt = NOW + 1_000, question = "Newer watch question",
            answer = "Newer reply B must not replace requested A.", summary = "Short B")
        val station = LearningUiStation(baseState().copy(records = listOf(newer, earlier), threadId = "phone-thread"))
        show(station)
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Your question").performTextInput("Keep my unsent phone question")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnIdle { station.state.value = station.state.value.copy(watchHandoffRecordId = earlier.id) }
        compose.onNodeWithText("Open full reply").assertIsDisplayed()
        compose.onNodeWithText("Keep my unsent phone question").assertExists()
        compose.runOnIdle { assertEquals(emptyList(), station.selectedRecordIds); assertEquals(0, station.resumes) }
        compose.onNodeWithText("Open full reply").performClick()
        compose.onNodeWithText(earlier.answer).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(newer.answer).assertDoesNotExist()
        captureFixtureScreenshot("watch-handoff-exact-reply.png")
        compose.runOnIdle {
            assertEquals(listOf(earlier.id), station.selectedRecordIds)
            assertEquals(null, station.state.value.watchHandoffRecordId)
            assertEquals("phone-thread", station.state.value.threadId)
            assertEquals(0, station.resumes)
            assertEquals(0, station.providerRequests)
            assertEquals(0, station.cancellations)
        }
        compose.onNodeWithText("Back to records").performScrollTo().performClick()
        compose.onNodeWithText("Keep my unsent phone question").assertExists()
    }

    @Test fun captureAttachesAndRemovalPreservesDraftAndConversation() {
        val station = LearningUiStation(baseState())
        show(station)
        compose.onNodeWithText("Capture now").performScrollTo().performClick()
        compose.onNodeWithText("Latest observation").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("now-capture.png")
        compose.runOnIdle { assertEquals(1, station.captures); assertEquals(0, station.providerRequests) }
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Evidence · 1 attached").assertIsDisplayed()
        compose.onNodeWithText("Your question").performTextInput("What does this capture show?")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Evidence · 1 attached").performClick()
        compose.onNodeWithText("Remove observation", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(station.state.value.attachedRecords.isEmpty(), "Remove must update the station without starting another thread") }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("No capture attached."))
        compose.onNodeWithText("No capture attached.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to question").performScrollTo().performClick()
        compose.onNodeWithText("What does this capture show?").assertExists()
        captureFixtureScreenshot("ask-draft.png")
        compose.runOnIdle {
            assertTrue(station.state.value.attachedRecords.isEmpty())
            assertEquals(1, station.state.value.records.size)
            assertEquals(0, station.newThreads)
            assertEquals(0, station.providerRequests)
        }
    }

    @Test fun latestObservationPreparesAttachedQuestionAndOnlySendContactsProvider() = captureReview(1f)
    @Test fun latestObservationReviewAtLargeText() = captureReview(2f)

    private fun captureReview(fontScale: Float) {
        val station = LearningUiStation(baseState().copy(settings = readySettings(), configuredProviders = setOf("openai")))
        show(station, fontScale)
        compose.onNodeWithText("Capture now").performScrollTo().performClick()
        compose.onNodeWithText("Right now").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("now-overview-${fontScale.toInt()}.png")
        compose.onNodeWithText("Ask about this").performScrollTo().performClick()
        compose.onNodeWithText("Evidence · 1 attached").assertIsDisplayed()
        compose.onNodeWithText("What does this observation show?").assertExists()
        compose.runOnIdle { assertEquals(0, station.providerRequests); assertEquals(1, station.state.value.attachedRecords.size) }
        captureFixtureScreenshot("ask-attached-${fontScale.toInt()}.png")
        compose.onNodeWithText("Review question").performClick()
        compose.onNodeWithText("Review before sending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 capture · 1 reading · 0 earlier turns").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, station.providerRequests) }
        compose.onNodeWithText("Send question").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, station.providerRequests) }
        captureFixtureScreenshot("capture-review-${fontScale.toInt()}.png")
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open record"))
        compose.onNodeWithText("Open record").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("history-overview-${fontScale.toInt()}.png")
    }

    @Test fun disabledSourceMemoryLeavesComposerButRemainsInspectable() {
        val memory = memory("battery-pattern", "Battery readings are usually steady")
        val station = LearningUiStation(baseState().copy(
            settings = readySettings().copy(learningEnabled = true),
            memories = listOf(memory), memorySuggestions = listOf(memory),
        ))
        show(station)

        compose.onNodeWithText("Ask", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your question").performTextInput("What do my battery readings show?")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Evidence · 0 attached").performClick()
        compose.onNodeWithText("1 relevant memory selected for this question.").performScrollTo().assertExists()
        compose.onNodeWithText(memory.text).assertExists()

        compose.runOnIdle {
            station.state.value = station.state.value.copy(settings = station.state.value.settings.copy(enabled = emptySet()))
        }
        compose.onNodeWithText("0 relevant memories selected for this question.").performScrollTo().assertExists()
        compose.onNodeWithText(memory.text).assertDoesNotExist()
        compose.onNodeWithText("Review suggested context").assertDoesNotExist()

        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.onNode(hasText("Patterns") and hasClickAction()).performScrollTo().performClick()
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

        compose.onNodeWithText("Record over time").performScrollTo().performClick()
        compose.onNodeWithText("Session length: 1 hour").performScrollTo().assertExists()
        compose.onNodeWithText("Choose session sources · 1 selected").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Phone battery").performScrollTo().assertIsOn()
        compose.onNodeWithText("Start recording").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(60 to setOf(BATTERY)), station.observationStarts) }

        compose.onNodeWithText("Stop recording").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, station.observationStops)
            assertEquals("stopped", station.state.value.observationSession?.state)
            assertEquals(0, station.providerRequests)
            assertFalse(station.state.value.settings.learningEnabled)
        }
    }

    @Test fun recordingAllowsCustomIntervalOngoingAndSeparateLocalAnalysis() {
        val station = LearningUiStation(baseState())
        show(station)
        compose.onNodeWithText("Record over time").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Phone battery").assertDoesNotExist()
        compose.onNodeWithText("Collection schedule: Adaptive").performScrollTo().performClick()
        compose.onNodeWithText("Fixed interval").performClick()
        compose.onNodeWithText("Minutes between scans").performScrollTo().performTextReplacement("7")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Session length: 1 hour").performScrollTo().performClick()
        compose.onNodeWithText("Ongoing · until stopped").performClick()
        compose.onNodeWithContentDescription("Local change analysis").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Scheduled model analysis").performScrollTo().assertIsOff()
        compose.onNodeWithText("Save schedule and analysis").performScrollTo().performClick()
        compose.onNodeWithText("Start recording").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(0 to setOf(BATTERY)), station.observationStarts)
            assertEquals(null, station.state.value.observationSession?.endsAt)
            assertEquals(7, station.state.value.settings.observationIntervalMinutes)
            assertTrue(station.state.value.settings.observationLocalAnalysis)
            assertFalse(station.state.value.settings.observationModelAnalysis)
            assertEquals(0, station.providerRequests)
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

        compose.onNodeWithText("Capture now").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("today-font-200.png")
        compose.onNode(hasText("Ask") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Ask about your observations").performScrollTo().assertIsDisplayed()

        compose.onNode(hasText("History") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNode(hasText("Patterns") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Remembered").performScrollTo().performClick()
        compose.onNodeWithText(example.text).performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("memory-font-200.png")
        compose.onNodeWithText("Why this?").performScrollTo().performClick()
        compose.onNode(hasText(example.coverage) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()

        compose.onNode(hasText("Settings") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Answers").performScrollTo().performClick()
        compose.onNodeWithText("Capture works without a provider key. Add a key when you want model analysis.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Now") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNodeWithText("Capture now").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, station.providerRequests) }
    }

    @Test fun savedQuestionOpensAndReviewDoesNotSendAtLargeText() {
        val recipe = SavedQuestion("saved", "Morning check", "What changed?", searchHistory = true)
        val station = LearningUiStation(baseState().copy(settings = readySettings(), configuredProviders = setOf("openai"), savedQuestions = listOf(recipe)))
        show(station, fontScale = 2f)
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Saved questions").performScrollTo().performClick()
        compose.onNodeWithText("Open question").performScrollTo().performClick()
        compose.onNodeWithText("Your question").assertExists()
        compose.onNodeWithText("Review question").performClick()
        compose.onNodeWithText("Review before sending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show exact message text").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, station.providerRequests); assertEquals(0, station.captures); assertEquals(0, station.permissionRequests) }
        compose.onNodeWithText("Send question").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, station.providerRequests) }
    }

    @Test fun sensingEvidenceAndReviewRemainReachable() = sensingReview(1f)
    @Test fun sensingEvidenceAndReviewRemainReachableAtLargeText() = sensingReview(2f)

    private fun sensingReview(fontScale: Float) {
        val now = System.currentTimeMillis()
        val records = (0..2).map { index ->
            val time = now - (2 - index) * 60_000
            SignalRecord("sensing-$index", "sensing", time, "Captured readings", provider = "local", model = "", state = "ready", kind = "capture",
                sourceKeys = setOf(BATTERY, "location"), sessionId = "sensing-session",
                observations = listOf(
                    SignalObservation(BATTERY, "phone", (80 - index).toString(), "%", time, measuredAt = time, status = "fresh", number = (80 - index).toDouble(), id = "battery-$index"),
                    SignalObservation("location", "phone", collectedAt = time, measuredAt = time, status = "fresh", id = "fix-$index", fields = mapOf("latitude" to "45.5", "longitude" to "-122.6", "accuracyMeters" to "10"))))
        }
        val station = LearningUiStation(baseState().copy(records = records, historyCount = records.size.toLong(),
            settings = readySettings().copy(enabled = setOf(BATTERY, "location", "places.nearby"), lookups = SignalLookupSettings(nearbyPlaces = true)),
            sessions = listOf(SignalObservationSession("sensing-session", now - 120_000, now, setOf(BATTERY, "location"), state = "completed", captures = 3, attempts = 4))))
        show(station, fontScale)
        compose.onNodeWithText("Changes and numeric history").performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Numeric history · local"))
        compose.onNodeWithText("Numeric history · local").performScrollTo().performClick()
        compose.onNodeWithText("Exact values and evidence").performScrollTo().performClick()
        compose.onNodeWithText("Measurement table · phone local time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Evidence · sensing-2").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("sensing-table-${fontScale.toInt()}.png")
        compose.onNode(hasText("Now") and hasClickAction()).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Around me") and hasClickAction())
        compose.onNode(hasText("Around me") and hasClickAction()).performScrollTo()
        captureFixtureScreenshot("nearby-target-${fontScale.toInt()}.png")
        Log.i("SignalScrollDebug", compose.onRoot().printToString())
        compose.onNode(hasText("Around me") and hasClickAction()).assertIsDisplayed().performClick()
        captureFixtureScreenshot("nearby-navigation-${fontScale.toInt()}.png")
        compose.onNodeWithText("Places").performClick()
        compose.onNodeWithText("Nearby places").performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Look up nearby places…"))
        compose.onNodeWithText("Look up nearby places…").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Review external lookup").assertIsDisplayed()
        compose.onNodeWithText("Exact outgoing request").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("sensing-lookup-${fontScale.toInt()}.png")
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.onNode(hasText("Recordings") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Inspect session evidence"))
        compose.onNodeWithText("Inspect session evidence").performScrollTo().performClick()
        compose.onNodeWithText("Inspect record · sensing-0").performScrollTo().assertIsDisplayed()
        captureFixtureScreenshot("sensing-session-${fontScale.toInt()}.png")
        compose.runOnIdle { assertEquals(0, station.providerRequests); assertEquals(0, station.captures); assertEquals(0, station.lookupSends) }
    }

    @Test fun providerSetupReturnsToQuestionAndKeepsDraft() {
        val station = LearningUiStation(baseState())
        show(station)
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Your question").performTextInput("Keep this question through setup")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Set up answers").performScrollTo().performClick()
        compose.onNodeWithText("Model ID").performScrollTo().assertExists()
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.onNodeWithText("Keep this question through setup").assertExists()
        compose.runOnIdle { assertEquals(0, station.providerRequests) }
    }

    @Test fun retainedSessionSurvivesActivityRecreationAndKeyboardAtDoubleText() {
        val station = LearningUiStation(baseState())
        show(station, 2f)
        compose.onNode(hasText("Ask") and hasClickAction()).performClick()
        compose.onNodeWithText("Your question").performClick().performTextInput("A draft that survives rotation")
        val activity = compose.activity
        val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()
        var previousHeight = -1
        var settledChecks = 0
        compose.waitUntil(10_000) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
            val height = insets?.getInsets(imeType)?.bottom ?: 0
            settledChecks = if (height > 0 && height == previousHeight) settledChecks + 1 else 0
            previousHeight = height
            insets?.isVisible(imeType) == true && settledChecks >= 3
        }
        compose.waitForIdle()
        compose.onNodeWithText("Review question").assertIsDisplayed()
        val reviewBounds = compose.onNodeWithText("Review question").fetchSemanticsNode().boundsInWindow
        compose.runOnIdle {
            val decor = activity.window.decorView
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
            val keyboardTop = decor.height - insets.getInsets(imeType).bottom
            assertTrue(reviewBounds.bottom <= keyboardTop, "Review button must be entirely above the visible keyboard: $reviewBounds / $keyboardTop")
        }
        captureFixtureScreenshot("ask-keyboard-320-font-200.png")
        // Pointer input, not a semantics action: an overlaid IME would intercept this tap.
        compose.onNodeWithText("Review question").performTouchInput { click() }
        compose.waitUntil(5_000) { station.state.value.questionReview != null }
        compose.onNodeWithText("Review before sending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to question").performScrollTo().performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Set up answers").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        show(station, 2f)
        compose.onNodeWithText("Model ID").performScrollTo().assertExists()
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.onNodeWithText("A draft that survives rotation").assertExists()
        compose.runOnIdle { assertEquals(0, station.providerRequests) }
    }

    @Test fun historyFiltersSurviveSettingsAndActionsUseFrozenQuery() {
        val station = LearningUiStation(baseState())
        show(station)
        compose.onNodeWithText("Capture now").performScrollTo().performClick()
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.onNodeWithText("Search questions, answers, and readings").performScrollTo().performTextInput("battery")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Apply filters").performScrollTo().performClick()
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.onNodeWithText("battery").performScrollTo().assertExists()
        compose.onNodeWithText("Ask about these results").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("battery", station.askedScope?.text); assertEquals(0, station.providerRequests) }
    }

    private fun show(station: LearningUiStation, fontScale: Float? = null) {
        compose.activityRule.scenario.onActivity { activity ->
            val retained = androidx.lifecycle.ViewModelProvider(activity)[SignalUiViewModel::class.java].session
            activity.setLearningTestContent {
                if (fontScale == null) SignalScreen(station, standalone = true, uiSession = retained)
                else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.then(if (fontScale == 2f) androidx.compose.ui.Modifier.widthIn(max = 320.dp) else androidx.compose.ui.Modifier)) { SignalScreen(station, standalone = true, uiSession = retained) }
                }
            }
        }
        compose.waitForIdle()
    }

    /** These screenshots contain only the synthetic fixture above, captured inside emulator instrumentation. */
    private fun captureFixtureScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Dialog window/ripple animations can continue after Compose's test clock becomes idle.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
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
    var newThreads = 0
    var providerRequests = 0
    var observationStops = 0
    var lookupSends = 0
    var resumes = 0
    var cancellations = 0
    val selectedRecordIds = mutableListOf<String>()
    var askedScope: SignalHistoryQuery? = null
    override fun openHistoryQuestion(ids: Set<String>?, compare: Boolean) { askedScope = state.value.scopedHistory.query }
    val observationStarts = mutableListOf<Pair<Int, Set<String>>>()
    val retainedNotes = mutableListOf<Set<String>>()

    override fun queryHistory(query: SignalHistoryQuery) {
        val rows = state.value.records
        state.value = state.value.copy(scopedHistory = SignalScopedHistory(query = query, records = rows, recordIds = rows.map { it.id }.toSet(), matchingCount = rows.size, updatedAt = System.currentTimeMillis()))
    }
    override fun updateSettings(settings: SignalSettings) { state.value = state.value.copy(settings = settings) }
    override fun prepareLookup(kind: String, recordId: String) {
        state.value = state.value.copy(lookupReview = SignalLookupProviders.prepare(kind, state.value.records.first { it.id == recordId }, state.value.settings, System.currentTimeMillis()).review)
    }
    override fun sendReviewedLookup() { lookupSends++ }
    override fun dismissLookupReview() { state.value = state.value.copy(lookupReview = null) }
    override fun requestPermissions() { permissionRequests++ }
    override fun capture() {
        captures++
        val record = SignalRecord("ui-capture-$captures", "capture-thread", 1_789_000_000_000L, "Captured readings",
            provider = "local", model = "", state = "ready", kind = "capture", sourceKeys = setOf("phone.battery"),
            observations = listOf(SignalObservation("phone.battery", "phone", "78", "%", 1_789_000_000_000L)))
        state.value = state.value.copy(records = state.value.records + record, attachedRecords = listOf(record), historyCount = captures.toLong())
    }
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
            endsAt = if (minutes == 0) null else 1_789_000_000_000L + minutes * 60_000L, sourceKeys = sources,
            mode = state.value.settings.observationMode, intervalMinutes = state.value.settings.observationIntervalMinutes,
            localAnalysis = state.value.settings.observationLocalAnalysis, modelAnalysis = state.value.settings.observationModelAnalysis,
        ))
    }
    override fun stopObservation() {
        observationStops++
        state.value = state.value.copy(observationSession = state.value.observationSession?.copy(state = "stopped", status = "Stopped by you."))
    }
    override fun saveProvider(model: String, endpoint: String, key: String) = Unit
    override fun saveKey(provider: String, key: String) = Unit
    override fun testProvider() { providerRequests++ }
    override fun ask(text: String, searchHistory: Boolean) {
        val records = state.value.attachedRecords
        val message = text + records.joinToString(separator = "", prefix = "") { record ->
            "\nCapture ${record.id}: " + record.observations.joinToString { "${it.key} ${it.value} ${it.unit}" }
        }
        state.value = state.value.copy(questionReview = SignalQuestionReview(text, listOf("user" to message), records.size, 0, 0,
            captureCount = records.count { it.kind == "capture" }, observationCount = records.sumOf { it.observations.size }))
    }
    override fun sendReviewedQuestion() { providerRequests++ }
    override fun dismissQuestionReview() { state.value = state.value.copy(questionReview = null) }
    override fun openSavedQuestion(id: String) { state.value = state.value.copy(savedQuestionDraft = state.value.savedQuestions.first { it.id == id }, savedQuestionOpenToken = "opened", threadId = "saved-draft") }
    override fun analyzeRecord(id: String) {
        attachRecord(id)
        state.value = state.value.copy(questionDraft = "What does this observation show?", questionDraftToken = "draft-$id")
    }
    override fun survey() { capture(); analyzeRecord(state.value.records.last().id) }
    override fun installWatchApp() = Unit
    override fun openPermissionSettings() = Unit
    override fun recordOnWatch() = Unit
    override fun cancel() { cancellations++ }
    override fun newThread() { newThreads++; state.value = state.value.copy(threadId = "new-$newThreads", attachedRecords = emptyList(), questionReview = null) }
    override fun selectRecord(id: String) {
        selectedRecordIds += id
        state.value = state.value.copy(selectedRecordId = id, selectedRecord = state.value.records.firstOrNull { it.id == id }, selectedRecordLoading = false)
    }
    override fun dismissWatchHandoff() { state.value = state.value.copy(watchHandoffRecordId = null) }
    override fun resumeThread(id: String) { resumes++ }
    override fun attachRecord(id: String) { state.value = state.value.copy(attachedRecords = listOf(state.value.records.first { it.id == id })) }
    override fun removeAttachment(id: String) { state.value = state.value.copy(attachedRecords = state.value.attachedRecords.filterNot { it.id == id }, questionReview = null) }
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
