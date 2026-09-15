package com.lukesteuber.signalstation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import coredevices.pebble.signal.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalHomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun show(station: HomeUiStation, scale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity ->
            ComposeView(activity).also { view ->
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        SignalScreen(station, standalone = true)
                    }
                }
                activity.setContentView(view)
            }
        }
        compose.onNodeWithText("Home").performClick()
    }

    private fun gridNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    @Test fun gridHasNamedScreenReaderActionsAndButtonReordering() = reorderJourney(1f)
    @Test fun gridAndNonDragReorderingWorkAtDoubleText() = reorderJourney(2f)

    private fun reorderJourney(scale: Float) {
        val station = HomeUiStation()
        show(station, scale)
        gridNode(hasText("Living lamp", substring = false)).assertIsDisplayed()
        gridNode(hasContentDescription("Move Living lamp later"))
            .assertHasClickAction().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("living" to 1), station.moves)
            assertEquals(listOf("desk", "living"), station.state.value.home.tiles.sortedBy { it.position }.map { it.id })
            assertTrue(station.confirmed.isEmpty())
            assertTrue(station.requested.isEmpty())
        }
        gridNode(hasContentDescription("Move Desk lamp earlier")).assertIsNotEnabled()
        gridNode(hasContentDescription("Turn on Living lamp")).assertHasClickAction().assertIsEnabled()
        gridNode(hasText("Edit Living lamp")).performClick()
        compose.onNodeWithText("Configure shortcut").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show on watch").assertIsOff().performClick()
        compose.onNodeWithText("Save shortcut").performClick()
        compose.runOnIdle { assertTrue(station.saved.single().watchFavorite); assertTrue(station.requested.isEmpty()) }
    }

    @Test fun consentNamesExactTargetAndSendOnceDoesNotGrantPermission() = consentJourney(1f)
    @Test fun consentRemainsReachableAtDoubleText() = consentJourney(2f)

    private fun consentJourney(scale: Float) {
        val station = HomeUiStation()
        show(station, scale)
        gridNode(hasContentDescription("Turn on Living lamp")).performClick()
        compose.onNodeWithText("Confirm Home action").assertIsDisplayed()
        compose.onNode(hasText("System: Test home", substring = true) and hasText("Device: light.living", substring = true)
            and hasText("Action: turn_on", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Allow this exact action without future confirmation").assertIsOff()
        compose.runOnIdle { assertEquals(1, station.requested.size); assertTrue(station.confirmed.isEmpty()) }
        compose.onNodeWithText("Send once").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf("intent-1" to false), station.confirmed) }
        compose.onNodeWithText("Confirm Home action").assertDoesNotExist()
    }

    @Test fun persistentGrantRequiresExplicitSwitchAndCancelNeverSends() {
        val station = HomeUiStation()
        show(station)
        gridNode(hasContentDescription("Turn on Living lamp")).performClick()
        compose.onNodeWithContentDescription("Allow this exact action without future confirmation").assertIsOff().performClick()
        compose.onNodeWithText("Allow & send").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf("intent-1" to true), station.confirmed) }
        gridNode(hasContentDescription("Turn on Living lamp")).performClick()
        compose.onNodeWithContentDescription("Allow this exact action without future confirmation").assertIsOff()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("intent-2"), station.cancelled); assertEquals(1, station.confirmed.size) }
    }

    @Test fun unavailableControlsStayDisabledAndCaptureSelectionDoesNotSend() {
        val station = HomeUiStation()
        station.state.value = station.state.value.copy(home = station.state.value.home.let { home ->
            home.copy(snapshot = home.snapshot.copy(entities = home.snapshot.entities.map { it.copy(available = false) }))
        })
        show(station)
        gridNode(hasContentDescription("Turn on Living lamp")).assertIsNotEnabled()
        gridNode(hasText("All devices")).performClick()
        gridNode(hasContentDescription("Include Living lamp in captures")).assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("home", "light.living", true)), station.captureSelections)
            assertTrue(station.requested.isEmpty()); assertTrue(station.confirmed.isEmpty())
        }
    }

    @Test fun expiredConfirmationCannotOfferSend() {
        val station = HomeUiStation()
        station.requestHomeAction("home", "light.living", "turn_on", emptyMap())
        station.state.value = station.state.value.copy(home = station.state.value.home.let { home ->
            home.copy(ledger = home.ledger.map { it.copy(confirmationExpiresAt = System.currentTimeMillis() - 1) })
        })
        show(station)
        compose.onNodeWithText("Confirm Home action").assertDoesNotExist()
        compose.onNodeWithText("Send once").assertDoesNotExist()
        compose.runOnIdle { assertTrue(station.confirmed.isEmpty()) }
    }
}

private class HomeUiStation : SignalStation {
    override val available = true
    private val now = System.currentTimeMillis()
    private val cap = HomeCapability("turn_on", "Turn on")
    private val entities = listOf(
        HomeEntity("home", "light.living", "Living lamp", available = true, state = "off", observedAt = now, capabilities = listOf(cap)),
        HomeEntity("home", "light.desk", "Desk lamp", available = true, state = "off", observedAt = now, capabilities = listOf(cap))
    )
    override val state = MutableStateFlow(SignalState(initialized = true,
        settings = SignalSettings(onboardingComplete = true), homeStatus = "Synthetic fixtures; nothing connected.",
        home = HomeState(connections = listOf(HomeConnection("home", "Test home", HomeConnectorKind.HOME_ASSISTANT, "https://example.test")),
            tiles = listOf(HomeTile("living", "home", "light.living", "Living lamp", "turn_on", position = 0),
                HomeTile("desk", "home", "light.desk", "Desk lamp", "turn_on", position = 1)),
            snapshot = HomeSnapshot(entities, now))))
    val moves = mutableListOf<Pair<String, Int>>()
    val requested = mutableListOf<HomeAction>()
    val confirmed = mutableListOf<Pair<String, Boolean>>()
    val cancelled = mutableListOf<String>()
    val saved = mutableListOf<HomeTile>()
    val captureSelections = mutableListOf<Triple<String, String, Boolean>>()
    override fun moveHomeTile(id: String, offset: Int) {
        moves += id to offset
        val tiles = state.value.home.tiles.sortedBy { it.position }.toMutableList()
        val index = tiles.indexOfFirst { it.id == id }
        val target = (index + offset).coerceIn(0, tiles.lastIndex)
        tiles.add(target, tiles.removeAt(index))
        state.value = state.value.copy(home = state.value.home.copy(tiles = tiles.mapIndexed { position, tile -> tile.copy(position = position) }))
    }
    override fun saveHomeTile(tile: HomeTile) { saved += tile }
    override fun selectHomeCapture(connectionId: String, entityId: String, selected: Boolean) {
        captureSelections += Triple(connectionId, entityId, selected)
    }
    override fun requestHomeAction(connectionId: String, entityId: String, actionId: String, parameters: Map<String, String>) {
        val current = System.currentTimeMillis()
        val action = HomeAction("intent-${requested.size + 1}", connectionId, entityId, actionId, parameters, current)
        requested += action
        val entry = HomeLedgerEntry(action, HomeActionStatus.AWAITING_CONFIRMATION, current, current + 120_000)
        state.value = state.value.copy(home = state.value.home.copy(ledger = state.value.home.ledger + entry))
    }
    override fun confirmHomeAction(id: String, allowExactAction: Boolean) {
        confirmed += id to allowExactAction
        state.value = state.value.copy(home = state.value.home.copy(ledger = state.value.home.ledger.map {
            if (it.action.id == id) it.copy(status = HomeActionStatus.ACCEPTED) else it
        }))
    }
    override fun cancelHomeAction(id: String) {
        cancelled += id
        state.value = state.value.copy(home = state.value.home.copy(ledger = state.value.home.ledger.map {
            if (it.action.id == id) it.copy(status = HomeActionStatus.CANCELLED) else it
        }))
    }
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
