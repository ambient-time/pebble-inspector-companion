package com.lukesteuber.signalstation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalEnvironmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun environmentalFavoritesWorkWithoutWatchOrActions() = readingsJourney(1f)

    @Test fun environmentalFavoritesRemainReachableAtDoubleText() = readingsJourney(2f)

    private fun readingsJourney(scale: Float) {
        val station = EnvironmentUiStation()
        show(station, scale)
        gridNode(hasText("Favorites", substring = false)).assertIsDisplayed()
        gridNode(reading("22", "°C")).assertIsDisplayed()
        gridNode(reading("45", "%")).assertIsDisplayed()
        screenshot("readings-source-${scale.toInt()}x")
        gridNode(hasText("Temperature units: Source units")).assertHasClickAction().performClick()
        compose.onNodeWithText("°F", substring = false).performClick()
        gridNode(reading("71.6", "°F")).assertIsDisplayed()
        gridNode(reading("45", "%")).assertIsDisplayed()
        screenshot("readings-fahrenheit-${scale.toInt()}x")
        compose.runOnIdle {
            assertEquals(listOf(HomeTemperatureUnit.FAHRENHEIT), station.unitSelections)
            assertEquals("22", station.state.value.home.snapshot.entities.single().values.first().value)
            assertEquals("°C", station.state.value.home.snapshot.entities.single().values.first().unit)
        }
        gridNode(hasText("Edit Example room")).performClick()
        compose.onNodeWithText("Configure shortcut").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show on watch").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Shortcut action: Reading only").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save shortcut").assertHasClickAction().performClick()
        gridNode(hasText("Watch favorite")).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(station.saved.single().watchFavorite)
            assertEquals(null, station.saved.single().capabilityId)
        }
        assertNoActions(station)
    }

    @Test fun staleAndRetainedReadingsRemainExplicit() {
        val station = EnvironmentUiStation()
        station.replaceEntity { it.copy(expiresAt = System.currentTimeMillis() - 60_000) }
        show(station)
        gridNode(hasText("Stale", substring = true, ignoreCase = true)).assertIsDisplayed()
        gridNode(reading("22", "°C")).assertIsDisplayed()
        screenshot("readings-stale")
        compose.runOnIdle {
            station.replaceEntity {
                it.copy(observedAt = 0, expiresAt = null, retained = true,
                    observationBasis = "retained", values = it.values.map { value -> value.copy(measuredAt = null) })
            }
        }
        gridNode(hasText("Retained", substring = true, ignoreCase = true)).assertIsDisplayed()
        gridNode(hasText("age unknown", substring = true, ignoreCase = true)).assertIsDisplayed()
        screenshot("readings-retained-unknown-age")
        assertNoActions(station)
    }

    @Test fun mqttSetupNamesBrokerAndReadingMappings() = mqttSetupJourney(1f)

    @Test fun mqttSetupRemainsReachableAtDoubleText() = mqttSetupJourney(2f)

    @Test fun failedMqttPreviewRetainsDraftAndAllowsRetry() {
        val station = EnvironmentUiStation()
        show(station)
        gridNode(hasText("Connections", substring = false)).performClick()
        gridNode(hasText("Add connection")).performClick()
        field("Connection name").performScrollTo().performTextReplacement("Retry fixture")
        compose.onNodeWithText("System: Home Assistant").performScrollTo().performClick()
        compose.onNodeWithText("MQTT over WebSockets", substring = false).performClick()
        field("Broker URL").performScrollTo().performTextReplacement("wss://broker.example/mqtt")
        field("Reading name").performScrollTo().performTextReplacement("Example greenhouse")
        field("Topic").performScrollTo().performTextReplacement("fixtures/greenhouse")
        field("Temperature field").performScrollTo().performTextReplacement("climate.temp")
        field("Humidity field").performScrollTo().performTextReplacement("climate.humidity")
        compose.onNodeWithText("Test & preview").assertIsEnabled().performClick()
        compose.onNodeWithText("Testing connection and reading selected topics…").assertIsDisplayed()
        compose.onNodeWithText("Test & preview").assertIsNotEnabled()
        field("Topic").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, station.connectionTests)
            station.failConnectionTest()
        }
        compose.onNodeWithText("Home connection").assertIsDisplayed()
        compose.onNode(hasText(EnvironmentUiStation.CONNECTION_FAILURE) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed()
        val expected = mapOf(
            "Connection name" to "Retry fixture",
            "Broker URL" to "wss://broker.example/mqtt",
            "Reading name" to "Example greenhouse",
            "Topic" to "fixtures/greenhouse",
            "Temperature field" to "climate.temp",
            "Humidity field" to "climate.humidity",
        )
        expected.forEach { (label, value) -> field(label).performScrollTo().assert(hasText(value)) }
        screenshot("mqtt-draft-after-failed-preview")
        compose.onNodeWithText("Test & preview").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, station.connectionTests)
            assertEquals(station.testedConnections.first(), station.testedConnections.last())
            val mapping = checkNotNull(station.testedConnections.last().mqtt).topics.single()
            assertEquals("fixtures/greenhouse", mapping.topic)
            assertEquals("climate.temp", mapping.temperaturePath)
            assertEquals("climate.humidity", mapping.humidityPath)
            station.completeConnectionTest()
        }
        compose.onNodeWithText("Home connection").assertDoesNotExist()
        gridNode(hasText("Catalog preview · Retry fixture")).assertIsDisplayed()
        assertNoActions(station)
    }

    private fun mqttSetupJourney(scale: Float) {
        val station = EnvironmentUiStation()
        show(station, scale)
        gridNode(hasText("Connections", substring = false)).performClick()
        gridNode(hasText("Add connection")).performClick()
        compose.onNodeWithText("System: Home Assistant").performScrollTo().performClick()
        compose.onNodeWithText("MQTT over WebSockets", substring = false).performClick()
        compose.onNode(hasText("Broker URL") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Anonymous broker").performScrollTo().assertIsOn().performClick()
        compose.onNode(hasText("Username") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Password") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        screenshot("mqtt-credentials-${scale.toInt()}x")
        for (label in listOf("Topic", "Reading name", "Temperature field", "Humidity field")) {
            compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        }
        screenshot("mqtt-mappings-${scale.toInt()}x")
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, station.connectionTests) }
        assertNoActions(station)
    }

    private fun reading(number: String, unit: String) =
        hasText(number, substring = true) and hasText(unit, substring = true)

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun show(station: EnvironmentUiStation, scale: Float = 1f) {
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

    private fun assertNoActions(station: EnvironmentUiStation) {
        compose.onNodeWithText("Confirm Home action").assertDoesNotExist()
        compose.onNodeWithText("Send once").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(station.requested.isEmpty())
            assertTrue(station.confirmed.isEmpty())
            assertTrue(station.state.value.home.grants.isEmpty())
            assertTrue(station.state.value.home.ledger.isEmpty())
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "environment-ui").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private class EnvironmentUiStation : SignalStation {
    override val available = true
    private val now = System.currentTimeMillis()
    private val entity = HomeEntity("fixture", "room-sensor", "Example room", domain = "sensor", state = "available",
        available = true, observedAt = now - 30_000, expiresAt = now + 3_600_000,
        values = listOf(HomeValue("temperature", "22", "°C", now - 30_000), HomeValue("humidity", "45", "%", now - 30_000)))
    override val state = MutableStateFlow(SignalState(initialized = true, homeReady = true,
        settings = SignalSettings(onboardingComplete = true), homeStatus = "Synthetic readings; no watch or broker connected.",
        home = HomeState(connections = listOf(HomeConnection("fixture", "Example source", HomeConnectorKind.HOME_ASSISTANT, "https://example.test")),
            tiles = listOf(HomeTile("room", "fixture", "room-sensor", "Example room")),
            snapshot = HomeSnapshot(listOf(entity), now))))
    val unitSelections = mutableListOf<HomeTemperatureUnit>()
    val saved = mutableListOf<HomeTile>()
    val requested = mutableListOf<HomeAction>()
    val confirmed = mutableListOf<Pair<String, Boolean>>()
    val testedConnections = mutableListOf<HomeConnection>()
    var connectionTests = 0

    fun failConnectionTest() {
        state.value = state.value.copy(homeBusy = false, homeStatus = CONNECTION_FAILURE)
    }

    fun completeConnectionTest() {
        state.value = state.value.copy(homeBusy = false, homePreview = testedConnections.last(),
            homePreviewEntities = emptyList(), homeStatus = "Connection test passed. Review the catalog, then save.")
    }

    fun replaceEntity(change: (HomeEntity) -> HomeEntity) {
        state.value = state.value.copy(home = state.value.home.let { home ->
            home.copy(snapshot = home.snapshot.copy(entities = home.snapshot.entities.map(change)))
        })
    }

    override fun setHomeTemperatureUnit(unit: HomeTemperatureUnit) {
        unitSelections += unit
        state.value = state.value.copy(home = state.value.home.copy(temperatureUnit = unit))
    }
    override fun saveHomeTile(tile: HomeTile) {
        saved += tile
        state.value = state.value.copy(home = state.value.home.let { home ->
            home.copy(tiles = home.tiles.map { if (it.id == tile.id) tile else it })
        })
    }
    override fun testHomeConnection(connection: HomeConnection, token: String) {
        connectionTests++
        testedConnections += connection
        state.value = state.value.copy(homeBusy = true, homePreview = null, homeStatus = "Testing connection…")
    }
    override fun requestHomeAction(connectionId: String, entityId: String, actionId: String, parameters: Map<String, String>) {
        requested += HomeAction("unexpected", connectionId, entityId, actionId, parameters, System.currentTimeMillis())
    }
    override fun confirmHomeAction(id: String, allowExactAction: Boolean) { confirmed += id to allowExactAction }
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

    companion object {
        const val CONNECTION_FAILURE = "Synthetic broker authentication failed. Correct the credentials and try again."
    }
}
