package com.lukesteuber.signalstation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalSpeechUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val record = SignalRecord("speech-fixture", "thread", 1, "What do these readings mean?",
        answer = "Outside: 22 °C, fresh. Room: 18 °C, stale. Humidity unavailable.",
        summary = "This summary must not be spoken.", provider = "openai", model = "fixture", state = "ready")

    @Test fun explicitPlaybackAndStopPreserveReply() = journey(1f)
    @Test fun explicitPlaybackAndStopWorkAtDoubleText() = journey(2f)

    private fun journey(scale: Float) {
        val station = station()
        val speech = FakeSpeech()
        show(station, speech, scale)
        compose.runOnIdle { assertTrue(speech.played.isEmpty()) }
        replyNode("Listen on phone").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(record), speech.played)
            station.state.value = station.state.value.copy(busy = true)
        }
        replyNode("Stop reading").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        screenshot("speech-preparing-${scale.toInt()}x")
        compose.onNodeWithText("Stop reading").performClick()
        compose.runOnIdle {
            assertFalse(speech.state.value.active)
            assertEquals(record.answer, station.state.value.records.single().answer)
            assertEquals(0, station.asks)
            assertTrue(station.requested.isEmpty())
        }
        replyNode("Listen on phone").assertIsEnabled()
        screenshot("speech-stopped-${scale.toInt()}x")
        replyNode("Reading stopped.").assertIsDisplayed()
        replyNode("Reads this saved reply through your Android speech engine with a voice marked offline. No new model request.").assertIsDisplayed()
        screenshot("speech-disclosure-${scale.toInt()}x")
    }

    @Test fun openingOptionsStopsAndDoesNotResume() {
        val speech = FakeSpeech()
        show(station(), speech)
        replyNode("Listen on phone").performClick()
        compose.onNodeWithText("Options").performClick()
        compose.runOnIdle { assertFalse(speech.state.value.active) }
        compose.onNodeWithText("Back to question").performClick()
        replyNode("Listen on phone").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, speech.played.size) }
    }

    @Test fun changingRouteStopsAndDetailCanPlayExplicitly() {
        val speech = FakeSpeech()
        show(station(), speech)
        replyNode("Listen on phone").performClick()
        replyNode("Readings and details").performClick()
        compose.runOnIdle { assertFalse(speech.state.value.active) }
        replyNode("Listen on phone").performClick()
        compose.runOnIdle { assertEquals(2, speech.played.size) }
        compose.onNodeWithText("Home", substring = false).performClick()
        compose.runOnIdle { assertFalse(speech.state.value.active) }
    }

    @Test fun removedOrChangedAnswerStopsPlayback() {
        val station = station()
        val speech = FakeSpeech()
        show(station, speech)
        replyNode("Listen on phone").performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(records = listOf(record.copy(answer = "Corrected saved answer."))) }
        compose.runOnIdle { assertFalse(speech.state.value.active) }
        replyNode("Listen on phone").performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(records = emptyList()) }
        compose.runOnIdle { assertFalse(speech.state.value.active) }
        compose.onNodeWithText("Listen on phone").assertDoesNotExist()
    }

    @Test fun recordingAndThreadSwitchStopPlayback() {
        val station = station()
        val speech = FakeSpeech()
        show(station, speech)
        replyNode("Listen on phone").performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(wakePhase = "recording") }
        compose.runOnIdle { assertFalse(speech.state.value.active) }
        replyNode("Listen on phone").performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(threadId = "different") }
        compose.runOnIdle { assertFalse(speech.state.value.active) }
    }

    private fun station() = SpeechUiStation().also {
        it.state.value = it.state.value.copy(records = listOf(record), threadId = record.threadId,
            settings = it.state.value.settings.copy(provider = "openai", model = "fixture"), configuredProviders = setOf("openai"))
    }

    private fun show(station: SpeechUiStation, speech: SignalSpeech, scale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        SignalScreen(station, standalone = true, speech = speech)
                    }
                }
                activity.setContentView(view)
            }
        }
        compose.onNodeWithText("Ask", substring = false).performClick()
    }

    private fun replyNode(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = false))
        return compose.onNodeWithText(text, substring = false)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "speech-ui").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class SpeechUiStation : EnvironmentUiStation() {
        var asks = 0
        override fun ask(text: String, searchHistory: Boolean) { asks++ }
    }

    private class FakeSpeech : SignalSpeech {
        override val state = MutableStateFlow(SignalSpeechState())
        val played = mutableListOf<SignalRecord>()
        override fun play(record: SignalRecord) {
            played += record
            state.value = SignalSpeechState(record.id, true, "Preparing installed offline voice…")
        }
        override fun stop() { state.value = state.value.copy(active = false, status = "Reading stopped.") }
    }
}
