package com.lukesteuber.signalstation

import android.graphics.Bitmap
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class SignalOutdoorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun publicSetupRequiresSourcesAndSeparateDisclosure() = setup(1f)
    @Test fun publicSetupIsReachableAtDoubleText() = setup(2f)

    private fun setup(scale: Float) {
        val station = OutdoorUiStation().apply { configure() }
        show(station, scale)
        grid(hasText("Connections", substring = false)).performClick()
        grid(hasText("Add weather & environment")).performClick()
        compose.onNodeWithText("Weather & environment", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Preview selected sources").performScrollTo().assertIsNotEnabled()
        field("City and country").performScrollTo().performTextReplacement("Example")
        compose.runOnIdle { assertEquals(0, station.searches); assertTrue(station.previews.isEmpty()) }
        compose.onNodeWithText("Search Open-Meteo").performScrollTo().performClick()
        compose.onNode(hasText("Example city", substring = true) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Current weather").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Preview selected sources").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("I agree to request these public sources").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Preview selected sources").performScrollTo().assertIsDisplayed()
        screenshot("outdoor-disclosure-${scale.toInt()}x")
        compose.onNodeWithText("Preview selected sources").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, station.searches); assertEquals(1, station.previews.size)
            val connection = station.previews.single()
            assertEquals("", connection.baseUrl); assertEquals("", connection.credentialKey)
            assertEquals(setOf("weather.current"), connection.outdoor!!.sources)
            assertEquals(OutdoorUiStation.PLACE, connection.outdoor!!.place)
            assertTrue(station.state.value.home.captureTargets.isEmpty()); assertTrue(station.state.value.homeAccess.isEmpty())
            station.finishPreview()
        }
        compose.onNodeWithText("Weather & environment", substring = false).assertDoesNotExist()
        grid(hasText("Catalog preview · Outdoors")).assertIsDisplayed()
        grid(hasText("Modeled conditions")).assertIsDisplayed()
        screenshot("outdoor-preview-${scale.toInt()}x")
        grid(hasText("Save connection")).performClick()
        grid(hasText("Public environment · Example city")).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(station.state.value.home.grants.isEmpty()); assertTrue(station.state.value.homeAccess.isEmpty())
            assertTrue(station.state.value.home.captureTargets.isEmpty())
        }
    }

    @Test fun outdoorFavoriteShowsModelBasisAndConvertsWithoutChangingOriginal() {
        val station = OutdoorUiStation().apply { configure(favorite = true) }
        show(station, 2f)
        grid(hasText("Modeled conditions")).assertIsDisplayed()
        grid(hasText("20 °C", substring = true)).assertIsDisplayed()
        grid(hasText("Temperature units: Source units")).performClick()
        compose.onNodeWithText("°F", substring = false).performClick()
        grid(hasText("68 °F", substring = true)).assertIsDisplayed()
        grid(hasText("Details & source")).performClick()
        grid(hasText("Modeled outdoor conditions; not a room measurement.")).assertIsDisplayed()
        screenshot("outdoor-favorite-details-2x")
        compose.runOnIdle { assertEquals("20", station.state.value.home.snapshot.entities.single().outdoor!!.values.single().value) }
    }

    @Test fun failedPreviewRetainsPlaceAndConsentAndCancelAbandonsIt() {
        val station = OutdoorUiStation().apply { configure(favorite = true) }
        show(station)
        grid(hasText("Connections", substring = false)).performClick()
        grid(hasText("Change place or sources")).performClick()
        compose.onNodeWithContentDescription("I agree to request these public sources").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Preview selected sources").performScrollTo().performClick()
        compose.runOnIdle { station.state.value = station.state.value.copy(homeBusy = false, homeStatus = "Synthetic provider timeout. Try again.") }
        compose.onNode(hasText("Synthetic provider timeout. Try again.") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Example city", substring = true) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, station.cancels); assertNull(station.state.value.homePreview) }
    }

    @Test fun connectionAttentionShowsFailedSourceAndClearsAfterRecovery() {
        val warning = "Next six hours: Unavailable."
        val station = OutdoorUiStation().apply {
            configure(favorite = true)
            state.value = state.value.copy(homeStatus = "1 devices. 1 connections need attention.",
                home = state.value.home.copy(snapshot = state.value.home.snapshot.copy(errors = mapOf("outside" to warning))))
        }
        show(station)
        grid(hasText("1 devices. 1 connections need attention.")).assertIsDisplayed()
        grid(hasText("Connections", substring = false)).performClick()
        grid(hasText(warning)).assertIsDisplayed()
        screenshot("outdoor-connection-attention")
        compose.runOnIdle {
            station.state.value = station.state.value.copy(homeStatus = "1 devices. 0 connections need attention.",
                home = station.state.value.home.copy(snapshot = station.state.value.home.snapshot.copy(errors = emptyMap())))
        }
        compose.onNodeWithText(warning).assertDoesNotExist()
        grid(hasText("1 devices. 0 connections need attention.")).assertIsDisplayed()
    }

    private fun show(station: OutdoorUiStation, scale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity ->
            // Dialogs own a separate Compose view. Scale the Android context so
            // both the page and modal use the requested font size.
            val context = ContextThemeWrapper(activity, 0).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = scale })
            }
            assertEquals(scale, context.resources.configuration.fontScale)
            activity.setContentView(ComposeView(context).apply { setContent {
                SignalScreen(station, standalone = true)
            } })
        }
        compose.onNodeWithText("Home").performClick()
    }
    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())
    private fun grid(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "outdoor-ui").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

private class OutdoorUiStation : EnvironmentUiStation() {
    var searches = 0
    var cancels = 0
    val previews = mutableListOf<HomeConnection>()
    private val now = System.currentTimeMillis()
    private val config = OutdoorConfig(PLACE, setOf("weather.current"))
    private val connection = HomeConnection("outside", "Outdoors", HomeConnectorKind.PUBLIC_ENVIRONMENT, "", credentialKey = "", outdoor = config)
    private val reading = OutdoorReading("weather.current.temperature_2m", "weather.current", "Outdoor temperature", "Open-Meteo", "https://open-meteo.com/en/docs", OutdoorBasis.MODEL,
        OutdoorStatus.AVAILABLE, now, now + 3_600_000, PLACE.name, listOf(OutdoorValue("temperature_2m", "Outdoor temperature", "20", "°C", now)), now,
        details = "Modeled outdoor conditions; not a room measurement.")
    private val entity = HomeEntity(connection.id, reading.id, reading.title, domain = "public_environment", available = true, observedAt = now, expiresAt = reading.expiresAt, outdoor = reading)
    fun configure(favorite: Boolean = false) {
        state.value = state.value.copy(home = if (favorite) HomeState(connections = listOf(connection), tiles = listOf(HomeTile("temperature", connection.id, reading.id, reading.title)), snapshot = HomeSnapshot(listOf(entity), now)) else HomeState())
    }
    override fun searchWeatherPlaces(query: String) {
        searches++; state.value = state.value.copy(weatherPlaces = listOf(PLACE), weatherSearchStatus = "Choose the place", weatherSearching = false)
    }
    override fun previewOutdoorConnection(connection: HomeConnection, disclosureAccepted: Boolean) {
        assertTrue(disclosureAccepted); previews += connection
        state.value = state.value.copy(homeBusy = true, homePreview = null)
    }
    fun finishPreview() { state.value = state.value.copy(homeBusy = false, homePreview = previews.last(), homePreviewEntities = listOf(entity)) }
    override fun saveHomeConnection() { state.value = state.value.copy(homePreview = null, homePreviewEntities = emptyList(), home = HomeState(connections = listOf(connection), snapshot = HomeSnapshot(listOf(entity), now))) }
    override fun cancelHomePreview() { cancels++; state.value = state.value.copy(homeBusy = false, homePreview = null, homePreviewEntities = emptyList()) }
    companion object { val PLACE = SignalPlace("Example city", 45.52, -122.68) }
}
