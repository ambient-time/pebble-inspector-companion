package com.lukesteuber.signalstation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalAnswerReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val answer = "The battery fell from 100 to 82 percent between the two captures."
    private val record = SignalRecord("report-fixture", "thread", 1, "What changed?", answer = answer,
        provider = "openai", model = "fixture", state = "ready")
    private val hidden = "You reported this answer. It is hidden on this device."

    private fun station() = ReportStation().also {
        it.state.value = it.state.value.copy(records = listOf(record), threadId = record.threadId,
            settings = it.state.value.settings.copy(provider = "openai", model = "fixture"), configuredProviders = setOf("openai"))
    }

    @Test fun reportHidesTheAnswerKeepsTheConfirmationAndCanShowItAgain() {
        val station = station()
        val opened = mutableListOf<String>()
        show(station, opened)
        node("Report").performClick()
        compose.onNodeWithText("Email report").assertIsNotEnabled()
        compose.onNodeWithText("False or misleading").performClick()
        screenshot("report-form")
        compose.onNodeWithText("Email report").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(setOf(record.id), station.state.value.reportedAnswers)
            val link = opened.single()
            assertTrue(link.startsWith("mailto:reports@example.com?subject=Signal%20Station%20answer%20report"))
            assertTrue(link.contains("False%20or%20misleading"))
        }
        // The answer hides while the dialog is open; the confirmation must survive that.
        compose.onNodeWithText("Report ready").assertIsDisplayed()
        compose.onNodeWithText("Copy report").performClick()
        compose.onNodeWithText("Report copied.").assertIsDisplayed()
        screenshot("report-ready")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText(hidden).assertIsDisplayed()
        compose.onNodeWithText(answer, substring = true).assertDoesNotExist()
        screenshot("report-hidden")
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithText(answer, substring = true).assertIsDisplayed()
        node("Hide again").performClick()
        compose.onNodeWithText(hidden).assertIsDisplayed()
    }

    @Test fun capturesAndLocalRecordsHaveNoReportControl() {
        val station = station()
        station.state.value = station.state.value.copy(records = listOf(record.copy(provider = "local", kind = "changes")))
        show(station, mutableListOf())
        compose.onNodeWithText("Report").assertDoesNotExist()
    }

    private fun show(station: ReportStation, opened: MutableList<String>) {
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    CompositionLocalProvider(LocalUriHandler provides handler) {
                        SignalScreen(station, standalone = true, appInfo = SignalAppInfo(contactEmail = "reports@example.com"))
                    }
                }
                activity.setContentView(view)
            }
        }
        compose.onNodeWithText("Ask", substring = false).performClick()
    }

    private fun node(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text, substring = false))
        return compose.onNodeWithText(text, substring = false)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "report-ui").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class ReportStation : EnvironmentUiStation() {
        override fun setAnswerReported(id: String, reported: Boolean) {
            state.value = state.value.copy(reportedAnswers = if (reported) state.value.reportedAnswers + id else state.value.reportedAnswers - id)
        }
    }
}
