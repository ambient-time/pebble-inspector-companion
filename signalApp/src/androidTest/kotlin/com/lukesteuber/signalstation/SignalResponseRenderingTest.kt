package com.lukesteuber.signalstation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coredevices.pebble.signal.SignalResponse
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SignalResponseRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun rendersHeadingsListsTablesCodeAndOnlyOpensTappedWebLinks() {
        val opened = mutableListOf<String>()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContentForTest {
                MaterialTheme {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    }) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { SignalResponse("""
                            # Local report
                            **Battery** is *steady*.
                            - First reading
                            - Second reading

                            | Sensor | Reading |
                            | --- | --- |
                            | Battery | 80% |

                            ```json
                            {"ready":true}
                            ```
                            [Open report](https://example.org/report)

                            [Blocked link](javascript:alert(1))

                            ![Remote image](https://example.org/tracker.png)
                        """.trimIndent()) }
                    }
                }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Local report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Local report").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Battery is steady.").assertExists()
        compose.onNodeWithText("First reading", substring = true).assertExists()
        compose.onNodeWithText("80%", substring = true).assertExists()
        compose.onNodeWithText("{\"ready\":true}", substring = true).assertExists()
        compose.onNodeWithText("**Battery**", substring = true).assertDoesNotExist()
        assertEquals(emptyList(), opened)
        compose.onNodeWithText("Blocked link").performScrollTo().performClick()
        assertEquals(emptyList(), opened)
        compose.onNodeWithText("Open report").performScrollTo().performClick()
        assertEquals(listOf("https://example.org/report"), opened)
    }

    @Test fun emptyAnswersKeepPlainStatusVisible() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContentForTest { MaterialTheme { Column { SignalResponse("", "Waiting for reply…") } } }
        }
        compose.onNodeWithText("Waiting for reply…").assertExists()
    }
}

private fun MainActivity.setContentForTest(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.ui.platform.ComposeView(this).also { it.setContent(content); setContentView(it) }
}
