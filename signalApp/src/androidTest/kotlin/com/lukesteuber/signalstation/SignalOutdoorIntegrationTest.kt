package com.lukesteuber.signalstation

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.pebble.signal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real station/coordinator/encrypted store; public HTTP and foreground state are synthetic. */
class SignalOutdoorIntegrationTest {
    @Test fun consentSaveCaptureAndPlaceReplacementStayIndependent(): Unit = runBlocking {
        fixture { f ->
            f.main { previewOutdoorConnection(f.draft, false) }
            until { !f.station.state.value.homeBusy }
            assertNull(f.station.state.value.homePreview); assertTrue(f.calls.isEmpty())
            val first = f.connect()
            assertEquals("", first.credentialKey)
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            assertTrue(f.station.state.value.home.grants.isEmpty())
            assertTrue(f.station.state.value.home.captureTargets.isEmpty())
            assertTrue(context.getSharedPreferences("${f.name}_private", 0).all.keys.none { it.startsWith("key:") })
            f.main { saveHomeTile(HomeTile("outside", first.id, "weather.uv.uv_index", "UV index", watchFavorite = true)) }
            until { f.station.state.value.home.tiles.size == 1 }
            assertTrue(f.station.state.value.home.captureTargets.isEmpty())
            f.main { selectHomeCapture(first.id, "weather.uv.uv_index", true) }
            until { f.station.state.value.home.captureTargets.size == 1 }
            f.main { updateSettings(state.value.settings.copy(enabled = setOf("home.readings"))) }
            until { f.station.state.value.settings.enabled == setOf("home.readings") }
            f.main { capture() }
            until { !f.station.state.value.busy && f.station.state.value.records.any { it.kind == "capture" && it.state == "ready" } }
            val record = f.station.state.value.records.first { it.kind == "capture" && it.state == "ready" }
            val original = assertNotNull(f.store.record(record.id)).observations.single { "original_reading" in it.fields }
            val reading = Json.decodeFromString<OutdoorReading>(original.fields.getValue("original_reading"))
            assertEquals("2", original.value); assertNull(original.measuredAt)
            assertEquals("Open-Meteo / CAMS", original.source); assertEquals(OutdoorBasis.MODEL, reading.basis)
            assertEquals(reading.fetchedAt, original.collectedAt)
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            val replacement = f.connect(first.copy(outdoor = first.outdoor!!.copy(place = SignalPlace("Another place", 40.7, -74.0))))
            assertEquals(first.id, replacement.id)
            assertTrue(f.station.state.value.home.captureTargets.isEmpty(), "Changing the requested place requires selecting capture targets again")
            assertEquals(1, f.station.state.value.home.tiles.size)
            assertTrue(f.station.state.value.home.grants.isEmpty())
            f.main { removeHomeConnection(first.id) }
            until { f.station.state.value.home.connections.isEmpty() }
            assertTrue(f.station.state.value.home.tiles.isEmpty()); assertTrue(f.station.state.value.home.snapshot.entities.isEmpty())
            assertTrue(f.calls.all { it == "air-quality-api.open-meteo.com" })
        }
    }

    @Test fun cancelAndBackgroundStopPreviewAndDoNotSaveLateResults(): Unit = runBlocking {
        fixture { f ->
            for (background in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
                f.hook = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                f.foreground = true
                f.main { previewOutdoorConnection(f.draft, true) }
                withTimeout(5_000) { entered.await() }
                if (background) f.foreground = false else f.main { cancelHomePreview() }
                withTimeout(5_000) { cancelled.await() }
                until { !f.station.state.value.homeBusy }
                assertNull(f.station.state.value.homePreview); assertTrue(f.station.state.value.home.connections.isEmpty())
            }
            f.hook = null
            val count = f.calls.size
            f.main { previewOutdoorConnection(f.draft, true) }
            until { !f.station.state.value.homeBusy }
            assertEquals(count, f.calls.size, "Background previews must not issue public HTTP requests")
        }
    }

    @Test fun searchCancellationAndBackgroundRejectLateResults(): Unit = runBlocking {
        fixture { f ->
            for (background in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
                f.searchHook = {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                f.foreground = true
                f.main { searchWeatherPlaces("Example") }
                withTimeout(5_000) { entered.await() }
                if (background) f.foreground = false else f.main { cancelHomePreview() }
                withTimeout(5_000) { cancelled.await() }
                until { !f.station.state.value.weatherSearching }
                assertTrue(f.station.state.value.weatherPlaces.isEmpty())
                assertEquals("", f.station.state.value.weatherSearchStatus)
            }
            f.searchHook = { error("Background search must not run") }
            f.main { searchWeatherPlaces("Example") }
            assertFalse(f.station.state.value.weatherSearching)
        }
    }

    @Test fun nativeWatchFavoriteIsReadOnlyAndDoesNotGrantModelAccess(): Unit = runBlocking {
        fixture { f ->
            val c = f.connect()
            f.main { saveHomeTile(HomeTile("outside", c.id, "weather.uv.uv_index", "UV index", watchFavorite = true)) }
            until { f.station.state.value.home.tiles.size == 1 }
            f.main { updateSettings(state.value.settings.copy(watchId = session.watchId)) }
            until { f.station.state.value.settings.watchId == session.watchId }
            assertEquals(200, f.station.handleWatchRequest(AndroidSignalStation.PREFIX + "capabilities", "GET", null, session).status)
            f.foreground = false // An explicit watch request is allowed; no phone polling is added.
            val reply = f.station.handleWatchRequest(AndroidSignalStation.PREFIX + "home", "POST",
                """{"kind":"home-open","request_id":101,"favorite_id":"outside"}""", session)
            assertEquals(200, reply.status)
            val data = Json.parseToJsonElement(reply.result).jsonObject
            assertEquals("detail", data["mode"]!!.jsonPrimitive.content)
            val text = data["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("Open-Meteo / CAMS")); assertTrue(text.contains("Modeled")); assertTrue(text.contains("Fetched")); assertTrue(text.contains("UV index: 2"))
            assertTrue(text.toByteArray().size <= 850); assertNull(data["action_id"])
            assertTrue(f.station.state.value.homeAccess.isEmpty()); assertTrue(f.station.state.value.home.ledger.isEmpty())
        }
    }

    @Test fun failedPublicFeedUpdatesAttentionCountAndRecoveryClearsIt(): Unit = runBlocking {
        fixture { f ->
            val connection = f.connect()
            f.hook = { throw java.io.IOException("Synthetic public provider failure") }
            f.main { refreshHome() }
            until { !f.station.state.value.homeBusy && f.station.state.value.home.snapshot.errors.isNotEmpty() }
            val failed = f.station.state.value
            assertEquals("1 devices. 1 connections need attention.", failed.homeStatus)
            assertTrue(failed.home.snapshot.errors.getValue(connection.id).contains("Unavailable"))
            assertFalse(failed.home.snapshot.entities.single().available)
            f.hook = null
            f.main { refreshHome() }
            until { !f.station.state.value.homeBusy && f.station.state.value.home.snapshot.errors.isEmpty() }
            assertEquals("1 devices. 0 connections need attention.", f.station.state.value.homeStatus)
            assertTrue(f.station.state.value.home.snapshot.entities.single().available)
            assertTrue(f.station.state.value.homeAccess.isEmpty())
            assertTrue(f.station.state.value.home.grants.isEmpty())
        }
    }

    private inner class Fixture {
        val name = "outdoor-integration-${UUID.randomUUID()}"
        val store = SignalStore(context, name)
        @Volatile var foreground = true
        var hook: (suspend () -> Unit)? = null
        var searchHook: (suspend () -> List<SignalPlace>)? = null
        val calls = CopyOnWriteArrayList<String>()
        val provider = HttpClient(MockEngine { error("No model requests are authorized by these tests") })
        val home = HttpClient(MockEngine { request ->
            calls += request.url.host
            assertEquals(HttpMethod.Get, request.method); assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("uv_index", request.url.parameters["current"])
            hook?.invoke()
            respond("""{"current":{"time":${System.currentTimeMillis() / 1000},"uv_index":2},"current_units":{"uv_index":""}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val draft = HomeConnection("outside", "Outdoors", HomeConnectorKind.PUBLIC_ENVIRONMENT, "", outdoor = OutdoorConfig(SignalPlace("Example", 45.52, -122.68), setOf("weather.uv")))
        val station = AndroidSignalStation(context, link, provider, name, homeClient = home, foregroundState = { foreground }, placeSearch = { searchHook?.invoke() ?: emptyList() })
        suspend fun main(block: AndroidSignalStation.() -> Unit) = withContext(Dispatchers.Main) { station.block() }
        suspend fun connect(connection: HomeConnection = draft): HomeConnection {
            main { previewOutdoorConnection(connection, true) }
            until { !station.state.value.homeBusy }
            val preview = assertNotNull(station.state.value.homePreview, station.state.value.homeStatus)
            main { saveHomeConnection() }
            until { station.state.value.home.connections.any { it == preview } && station.state.value.homeStatus.startsWith("Connection saved") }
            return preview
        }
    }
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try {
            f.store.settings(SignalSettings(onboardingComplete = true, enabled = emptySet(), watchId = ""))
            f.main { initialize() }
            until { f.station.state.value.initialized && f.station.state.value.historyReady && f.station.state.value.homeReady }
            test(f)
        } finally {
            f.main { close() }; f.store.close(); f.provider.close(); f.home.close()
            context.deleteDatabase("${f.name}-history.db"); context.deleteSharedPreferences("${f.name}_private")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${f.name}-station-v1") }
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(25) }
    private val session = object : SignalWatchSession {
        override val watchId = "outdoor-fixture-watch"
        override val connectionId = "outdoor-fixture-link"
        override val ready = true
        override suspend fun sendConfigMessage(message: String) = Unit
    }
    private val link = object : SignalWatchLink {
        override val watches = MutableStateFlow(listOf(SignalWatch(session.watchId, "Fixture", true, session.connectionId, true)))
        override val capabilities = SignalWatchCapabilities(messages = true)
        override fun initialize(scope: CoroutineScope) = Unit
        override suspend fun isTrusted(session: SignalWatchSession) = session.connectionId == "outdoor-fixture-link"
        override suspend fun launch(watchId: String) = Unit
        override suspend fun install(watchId: String) = error("No device installation")
    }
}
