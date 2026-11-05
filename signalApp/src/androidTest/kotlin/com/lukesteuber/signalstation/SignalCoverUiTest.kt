package com.lukesteuber.signalstation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import coredevices.pebble.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SignalCoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var fixtureImeBottom = 0

    @Test fun wideFrameNavigationKeepsAllDestinationsAndCollectionOff() {
        val station = CoverUiStation()
        show(station, 379.dp, { 336.dp })
        compose.onNodeWithTag("signal-compact-navigation").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        for (destination in listOf("Ask", "Home", "History", "Now")) navigate(destination)
        compose.onNodeWithText("Right now").assertIsDisplayed()
        scrollTo("Capture now").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, station.actions) }
    }

    @Test fun wideFrameQuestionReviewIsReachable() = questionJourney(379.dp, 336.dp, 1f)
    @Test fun fullCoverQuestionReviewIsReachableAtDoubleText() = questionJourney(399.dp, 441.dp, 2f)

    @Test fun resizingPreservesTheQuestionAndRestoresPhoneNavigation() {
        val station = CoverUiStation()
        val height = mutableStateOf(336.dp)
        show(station, 379.dp, { height.value })
        navigate("Ask")
        scrollTo("Your question").performTextReplacement("Synthetic draft survives resizing")
        compose.runOnIdle { height.value = 700.dp }
        compose.onNodeWithTag("signal-compact-navigation").assertDoesNotExist()
        compose.onNodeWithText("Synthetic draft survives resizing").assertExists()
        compose.runOnIdle { height.value = 441.dp }
        compose.onNodeWithTag("signal-compact-navigation").assertIsDisplayed()
        scrollTo("Synthetic draft survives resizing").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, station.actions) }
    }

    private fun questionJourney(width: Dp, height: Dp, scale: Float) {
        val station = CoverUiStation()
        show(station, width, { height }, scale)
        try {
            navigate("Ask")
            scrollTo("Your question").performTextReplacement("Synthetic cover question")
            scrollTo("Review question").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle { assertEquals(1, station.reviews, "The visible Review control must receive the touch") }
            scrollTo("Review before sending").assertIsDisplayed()
            scrollTo("Send question once").assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.runOnIdle { assertEquals(1, station.reviews); assertEquals(0, station.sends); assertEquals(0, station.actions) }
        } catch (failure: Throwable) {
            captureFailure("question-${width.value.toInt()}x${height.value.toInt()}")
            throw failure
        }
    }

    private fun show(station: CoverUiStation, width: Dp, height: () -> Dp, scale: Float = 1f) {
        val session = SignalUiSession()
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        val density = LocalDensity.current
                        val imeBottom = WindowInsets.ime.getBottom(density)
                        SideEffect { fixtureImeBottom = imeBottom }
                        val inset = signalCoverBottomInset("samsung", "SM-F776U1",
                            with(density) { width.roundToPx() }, with(density) { height().roundToPx() }, emptyList())
                        // Fixed viewports model available app space; the real IME is tested on the cover.
                        InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                            Box(Modifier.requiredSize(width, height())) {
                                SignalCoverContent(inset) { SignalScreen(station, standalone = true, uiSession = session) }
                            }
                        }
                    }
                }
                activity.setContentView(view)
            }
        }
    }

    @Test fun insetOnlyChangesUpdateTheAndroidBoundary() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        compose.activityRule.scenario.onActivity { activity ->
            val frame = SignalCoverFrame(activity, "samsung", "SM-F776U1")
            frame.layout(0, 0, 948, 1048)
            assertEquals(209, frame.bottomInset)
            val bounds = android.graphics.Rect(428, 1000, 948, 1048)
            val cutout = android.view.DisplayCutout(android.graphics.Insets.of(0, 0, 0, 48),
                null, null, null, bounds)
            val insets = android.view.WindowInsets.Builder().setDisplayCutout(cutout).build()
            assertEquals(listOf(bounds), insets.displayCutout?.boundingRects, "Synthetic insets must carry the bottom rectangle")
            val forwarded = frame.dispatchApplyWindowInsets(insets)
            assertEquals(48, frame.bottomInset)
            assertEquals(listOf(bounds), forwarded.displayCutout?.boundingRects, "The boundary must forward the cutout to Compose")
            frame.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().build())
            assertEquals(209, frame.bottomInset)
        }
    }

    @Test fun harmlessCoverDialogCanBeCancelledWithoutConfirming() {
        val open = mutableStateOf(true)
        var confirmed = 0
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    val inset = with(LocalDensity.current) { 88.dp.roundToPx() }
                    Box(Modifier.requiredSize(399.dp, 441.dp)) {
                        SignalCoverContent(inset) {
                            if (open.value) SignalAlertDialog(onDismissRequest = { open.value = false },
                                title = { Text("Synthetic cover dialog") }, text = { Text("Nothing connected. No action will run.") },
                                confirmButton = { TextButton({ confirmed++ }, Modifier.sizeIn(minHeight = 48.dp)) { Text("Confirm fixture") } },
                                dismissButton = { TextButton({ open.value = false }, Modifier.sizeIn(minHeight = 48.dp)) { Text("Cancel") } })
                        }
                    }
                }
                activity.setContentView(view)
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Synthetic cover dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmed) }
    }

    private fun navigate(destination: String) {
        compose.onNodeWithTag("signal-compact-navigation").performClick()
        compose.onNodeWithText(destination).performClick()
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun captureFailure(name: String) {
        println("COVER_FIXTURE failure=$name imeBottomPx=$fixtureImeBottom")
        println(compose.onRoot(useUnmergedTree = true).printToString())
        runCatching {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val output = java.io.File(compose.activity.getExternalFilesDir(null), "cover-fixtures").apply { mkdirs() }
            java.io.File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("COVER_FIXTURE screenshot=${it.javaClass.simpleName}") }
    }
}

private class CoverUiStation : SignalStation {
    override val available = true
    override val state = MutableStateFlow(SignalState(initialized = true, homeReady = true,
        settings = SignalSettings(onboardingComplete = true, provider = "openai", model = "synthetic"),
        configuredProviders = setOf("openai"), homeStatus = "Synthetic fixture. Nothing connected."))
    var actions = 0
    var reviews = 0
    var sends = 0
    override fun ask(text: String, searchHistory: Boolean) {
        reviews++
        state.value = state.value.copy(questionReview = SignalQuestionReview(text, listOf("user" to text), 0, 0, 0,
            provider = "openai", model = "synthetic", expiresAt = System.currentTimeMillis() + 120_000))
    }
    override fun sendReviewedQuestion() { sends++ }
    override fun dismissQuestionReview() { state.value = state.value.copy(questionReview = null) }
    override fun updateSettings(settings: SignalSettings) { actions++ }
    override fun saveProvider(model: String, endpoint: String, key: String) { actions++ }
    override fun saveKey(provider: String, key: String) { actions++ }
    override fun testProvider() { actions++ }
    override fun capture() { actions++ }
    override fun startLiveSignals() { actions++ }
    override fun requestHomeAction(connectionId: String, entityId: String, actionId: String, parameters: Map<String, String>) { actions++ }
    override fun confirmHomeAction(id: String, allowExactAction: Boolean) { actions++ }
    override fun analyzeRecord(id: String) { actions++ }
    override fun installWatchApp() { actions++ }
    override fun openPermissionSettings() { actions++ }
    override fun survey() { actions++ }
    override fun recordOnWatch() { actions++ }
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
    override fun requestPermissions() { actions++ }
    override fun searchWeatherPlaces(query: String) { actions++ }
    override fun startWakeListening() { actions++ }
    override fun stopWakeListening() = Unit
    override fun reviewWakeOnWatch() = Unit
    override fun saveFieldTest(trial: SignalFieldTest) = Unit
    override fun summarizeChanges(id: String) = Unit
    override fun dismissWakeDraft() = Unit
    override fun scanPresence() { actions++ }
    override fun enrollPresenceTarget(candidate: SignalRadioCandidate, label: String) = Unit
    override fun removePresenceTarget(id: String) = Unit
    override fun savePlaceFence(fence: SignalPlaceFence) = Unit
    override fun removePlaceFence(id: String) = Unit
    override fun lookupNearbyPlace() { actions++ }
    override suspend fun exportHistory() = "[]"
}
