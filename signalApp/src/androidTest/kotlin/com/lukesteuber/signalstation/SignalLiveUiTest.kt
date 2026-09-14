package com.lukesteuber.signalstation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Synthetic signal fixtures only: this tests navigation and explicit collection/send boundaries. */
class SignalLiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun liveSignalsAreExplicitInspectableAndStopOnLeaving() = journey(1f)
    @Test fun liveSignalsRemainUsableAtDoubleText() = journey(2f)

    @Test fun radioSectionsAndWifiFiltersDoNotChangeCollection() {
        val station = LiveUiStation()
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view -> view.setContent { SignalScreen(station, standalone = true) }; activity.setContentView(view) }
        }
        compose.onNodeWithText("Around me").performScrollTo().performClick()
        compose.onNodeWithText("Start scanning").performClick()
        liveNode("No password").performClick()
        liveNode("Test access point").assertIsDisplayed()
        compose.onNodeWithText("Protected access point").assertDoesNotExist()
        liveNode("Wi-Fi").performClick()
        compose.onNodeWithText("Test access point").assertDoesNotExist()
        liveNode("Wi-Fi").performClick()
        liveNode("Test access point").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, station.starts); assertEquals(0, station.stops); assertEquals(4, station.state.value.live.entries.size) }
    }

    @Test fun namedDevicesStartCompactAndKeepControlsInsideDetails() {
        val station = LiveUiStation()
        station.state.value = station.state.value.copy(settings = station.state.value.settings.copy(
            enabled = setOf("presence.bluetooth"), presenceTargets = listOf(SignalPresenceTarget("desk", "bluetooth", "fixture-address", "Desk beacon"))))
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view -> view.setContent { SignalScreen(station, standalone = true) }; activity.setContentView(view) }
        }
        compose.onNodeWithText("Around me").performScrollTo().performClick()
        compose.onNodeWithText("My devices").performClick()
        compose.onNodeWithText("Desk beacon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Forget device").assertDoesNotExist()
        screenshot("devices-compact.png")
        compose.onNodeWithText("Desk beacon").performClick()
        compose.onNodeWithText("Forget device").performScrollTo().assertIsDisplayed()
        screenshot("devices-expanded.png")
        compose.onNodeWithText("Desk beacon").performScrollTo().performClick()
        compose.onNodeWithText("Forget device").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, station.starts); assertEquals(1, station.state.value.settings.presenceTargets.size) }
    }

    @Test fun inspectionSurvivesExpiryAndChangingPanesDoesNotRestart() {
        val station = LiveUiStation()
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view -> view.setContent { SignalScreen(station, standalone = true) }; activity.setContentView(view) }
        }
        compose.onNodeWithText("Around me").performScrollTo().performClick()
        compose.onNodeWithText("Start scanning").performClick()
        liveNode("Test beacon").performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(live = station.state.value.live.copy(entries = emptyList())) }
        compose.onNodeWithText("Signal history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Places").performClick()
        compose.runOnIdle { assertFalse(station.state.value.live.running) }
        compose.onNodeWithText("Signals").performClick()
        compose.onNodeWithText("Start scanning").assertExists()
        compose.runOnIdle { assertEquals(1, station.starts) }
    }

    private fun journey(scale: Float) {
        val station = LiveUiStation()
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    val fixture by station.state.collectAsState()
                    LaunchedEffect(fixture.live.running) {
                        if (fixture.live.running) withContext(Dispatchers.Default) {
                            while (true) { delay(1_000); station.refresh() }
                        }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) { SignalScreen(station, standalone = true) }
                }
                activity.setContentView(view)
            }
        }
        compose.onNodeWithText("Around me").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, station.starts) }
        liveNode("Save snapshot").assertIsNotEnabled()
        compose.onNodeWithText("Start scanning").performClick()
        compose.runOnIdle { assertEquals(1, station.starts) }
        liveNode("Recently seen").assertIsDisplayed()
        screenshot("live-overview-$scale.png")
        liveNode("Test beacon").performClick()
        compose.onNodeWithText("Signal history").performScrollTo().assertIsDisplayed()
        screenshot("live-detail-$scale.png")
        compose.runOnIdle { station.refresh() }
        compose.onNodeWithText("Label for this session").performScrollTo().performTextInput("My fixture")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Set session label").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("My fixture", station.label) }
        compose.onNodeWithText("Done").performClick()
        liveNode("Bluetooth").assertIsDisplayed()
        screenshot("live-sections-$scale.png")
        compose.runOnIdle { station.refresh() }
        liveNode("Save snapshot").performClick()
        compose.runOnIdle { assertEquals(listOf(false), station.saves) }
        liveNode("Ask about this scene").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), station.saves) }
        compose.onNodeWithText("Stop scanning").performClick()
        compose.runOnIdle { assertFalse(station.state.value.live.running) }
        compose.onNodeWithText("Start scanning").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(2, station.stops); assertFalse(station.state.value.live.running) }
        compose.onNodeWithText("Right now").assertExists()
    }

    private fun liveNode(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("live-signals-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("signal-ui-review"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
    }
}

private class LiveUiStation : SignalStation {
    override val available = true
    override val state = MutableStateFlow(SignalState(initialized = true, settings = SignalSettings(onboardingComplete = true, enabled = setOf("bluetooth", "wifi"))))
    var starts = 0
    var stops = 0
    var label = ""
    val saves = mutableListOf<Boolean>()
    override fun startLiveSignals() {
        starts++
        val now = System.currentTimeMillis()
        state.value = state.value.copy(live = SignalLiveState(running = true, now = now, startedAt = now, updatedAt = now, entries = listOf(
            SignalLiveEntry(id = "a", radio = "bluetooth", label = "Test beacon", rssi = -45, firstSeenAt = now, lastSeenAt = now, samples = listOf(-65, -52, -45), sampleCount = 3, band = "strong", trend = "strengthening"),
            SignalLiveEntry(id = "b", radio = "wifi", label = "Test access point", rssi = -68, firstSeenAt = now, lastSeenAt = now, samples = listOf(-68), band = "medium", trend = "unknown", security = "open advertised; internet and captive portal unknown"),
            SignalLiveEntry(id = "d", radio = "wifi", label = "Protected access point", rssi = -58, firstSeenAt = now, lastSeenAt = now, samples = listOf(-58), band = "strong", security = "security advertised"),
            SignalLiveEntry(id = "c", radio = "bluetooth", label = "Older beacon", rssi = -82, firstSeenAt = now - 25_000, lastSeenAt = now - 20_000, samples = listOf(-82), band = "faint", trend = "unknown")
        ), newCount = 3))
    }
    fun refresh() {
        // Synthetic scan arrivals use real time, independently of Compose's accelerated test clock.
        val now = System.currentTimeMillis()
        state.update { current ->
            if (!current.live.running) current else current.copy(live = current.live.copy(now = now, updatedAt = now,
                entries = current.live.entries.map { it.copy(lastSeenAt = if (it.id == "c") now - 20_000 else now) }))
        }
    }
    override fun stopLiveSignals() { stops++; state.value = state.value.copy(live = state.value.live.copy(running = false)) }
    override fun saveLiveScene(ask: Boolean) { saves += ask }
    override fun requestLivePermissions() = Unit
    override fun labelLiveSignal(id: String, label: String) { this.label = label }
    override fun updateSettings(settings: SignalSettings) = Unit
    override fun saveProvider(model: String, endpoint: String, key: String) = Unit
    override fun saveKey(provider: String, key: String) = Unit
    override fun testProvider() = Unit
    override fun ask(text: String, searchHistory: Boolean) = Unit
    override fun capture() = Unit
    override fun analyzeRecord(id: String) = Unit
    override fun installWatchApp() = Unit
    override fun openPermissionSettings() = Unit
    override fun survey() = Unit
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
    override fun requestPermissions() = Unit
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
