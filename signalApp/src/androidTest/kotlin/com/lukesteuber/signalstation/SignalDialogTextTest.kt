package com.lukesteuber.signalstation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import coredevices.pebble.signal.SignalAlertDialog
import coredevices.pebble.signal.SignalDialogText
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalDialogTextTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longDialogTextScrollsToItsLastLineAtDoubleFontSize() {
        val message = (1..40).joinToString(" ") { "Sentence $it of a long confirmation." } + " This cannot be undone."
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        SignalAlertDialog(onDismissRequest = {}, confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                            title = { Text("Erase all app data?") }, text = { SignalDialogText(message) })
                    }
                }
                activity.setContentView(view)
            }
        }
        val text = compose.onNodeWithText(message)
        fun range() = text.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range().maxValue() > 0f, "The message is taller than the dialog at this font size")
        assertEquals(0f, range().value())
        text.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
        assertEquals(range().maxValue(), range().value(), "The last line can be scrolled into view")
        compose.onNodeWithText("Confirm").assertIsDisplayed()
    }
}
