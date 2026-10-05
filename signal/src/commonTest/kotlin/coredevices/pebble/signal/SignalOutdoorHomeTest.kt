package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class SignalOutdoorHomeTest {
    private val now = 1_800_000_000_000L
    private val place = SignalPlace("Example", 45.52, -122.68)
    private val config = OutdoorConfig(place, setOf("weather.current", "weather.uv"))
    private val connection = HomeConnection("outside", "Outdoors", HomeConnectorKind.PUBLIC_ENVIRONMENT, "", credentialKey = "", outdoor = config)
    private val reading = OutdoorReading("weather.current.temperature_2m", "weather.current", "Outdoor temperature", "Open-Meteo", "https://open-meteo.com/en/docs", OutdoorBasis.MODEL,
        OutdoorStatus.AVAILABLE, now, now + 60_000, place.name, listOf(OutdoorValue("temperature_2m", "Outdoor temperature", "20", "°C", now - 60_000)), now - 60_000)

    @Test fun oldHomeDocumentsDoNotAcquirePublicConnectionOrReadings() {
        val old = Json.decodeFromString<HomeState>("""{"connections":[{"id":"home","name":"Home","kind":"OPENHAB","baseUrl":"https://example.test"}],"snapshot":{"entities":[{"connectionId":"home","id":"room","name":"Room"}]}}""")
        assertNull(old.connections.single().outdoor); assertNull(old.snapshot.entities.single().outdoor)
        assertEquals(old, Json.decodeFromString<HomeState>(Json.encodeToString(old)))
    }

    @Test fun publicConfigBindingsAreCanonicalAndRequestSensitive() {
        assertEquals(homeConnectionBinding(connection), homeConnectionBinding(connection.copy(outdoor = config.copy(sources = config.sources.reversed().toSet()))))
        listOf(config.copy(place = place.copy(latitude = 45.5)), config.copy(sources = setOf("weather.current")), config.copy(tideStation = "9414290"), config.copy(earthquakeRadiusKm = 50)).forEach {
            assertNotEquals(homeConnectionBinding(connection), homeConnectionBinding(connection.copy(outdoor = it)))
        }
        assertEquals(connection, Json.decodeFromString<HomeConnection>(Json.encodeToString(connection)))
    }

    @Test fun fixedPublicTransportRejectsCustomServerAndMqttConfig() {
        assertFails { validateOutdoorConnection(connection.copy(baseUrl = "https://evil.test")) }
        assertFails { validateOutdoorConnection(connection.copy(allowPrivateHttp = true)) }
        assertFails { validateOutdoorConnection(connection.copy(outdoor = config.copy(sources = emptySet()))) }
    }

    @Test fun publicFactoryNeverReadsCredentialsAndSingleReadFetchesOnlyItsCategory(): Unit = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("air-quality-api.open-meteo.com", request.url.host); assertEquals("uv_index", request.url.parameters["current"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"current":{"time":${now / 1000},"uv_index":2},"current_units":{"uv_index":""}}""")
        })
        val secrets = object : SignalSecrets {
            override suspend fun get(provider: String): String? = error("Must not access credentials")
            override suspend fun put(provider: String, key: String) = error("Must not write credentials")
        }
        val connector = SignalHomeConnectorFactory(http, secrets) { now }.create(connection)
        try {
            assertNull(connector.read("environment.tides")); assertEquals(0, calls)
            val entity = assertNotNull(connector.read("weather.uv.uv_index"))
            assertEquals(1, calls); assertTrue(entity.capabilities.isEmpty()); assertNotNull(entity.outdoor)
            assertFailsWith<HomeException> { connector.execute(HomeAction("forged", connection.id, entity.id, "switch", createdAt = now)) }
        } finally { connector.close(); http.close() }
    }

    @Test fun engineRejectsForgedCapabilityForPublicConnection(): Unit = runBlocking {
        val store = object : HomePersistence {
            override suspend fun load() = HomeState(connections = listOf(connection))
            override suspend fun save(state: HomeState) = error("No action may be saved")
        }
        val engine = SignalHomeEngine(store, { error("No transport may be created") }, { now }, { "forged" })
        val forged = reading.homeEntity(connection).copy(capabilities = listOf(HomeCapability("switch")))
        assertFailsWith<IllegalArgumentException> { engine.prepare(connection, forged, "switch") }
    }

    @Test fun catalogReportsFailedSourcesWithoutDiscardingSuccessfulReadings(): Unit = runBlocking {
        val http = HttpClient(MockEngine { request ->
            if (request.url.host == "air-quality-api.open-meteo.com")
                respond("""{"current":{"time":${now / 1000},"uv_index":2},"current_units":{"uv_index":""}}""")
            else respond("{}", HttpStatusCode.ServiceUnavailable)
        })
        val connector = SignalOutdoorConnector(http, connection) { now }
        try {
            val rows = connector.catalog()
            assertTrue(rows.any { it.id == "weather.uv.uv_index" && it.available })
            assertTrue(rows.filter { it.id.startsWith("weather.current.") }.all { !it.available })
            val warning = connector.warnings.single()
            assertTrue(warning.contains("Unavailable"), warning)
            assertTrue(warning.contains(SignalOutdoorSources.sources.first { it.key == "weather.current" }.name), warning)
        } finally { connector.close(); http.close() }
    }

    @Test fun catalogClearsWarningsAfterProviderRecovery(): Unit = runBlocking {
        var fail = true
        val http = HttpClient(MockEngine {
            if (fail) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond("""{"current":{"time":${now / 1000},"uv_index":0},"current_units":{"uv_index":""}}""")
        })
        val connector = SignalOutdoorConnector(http, connection.copy(outdoor = config.copy(sources = setOf("weather.uv")))) { now }
        try {
            assertFalse(connector.catalog().single().available)
            assertEquals(1, connector.warnings.size)
            fail = false
            assertTrue(connector.catalog().single().available)
            assertTrue(connector.warnings.isEmpty())
        } finally { connector.close(); http.close() }
    }

    @Test fun catalogWarnsForUnsupportedCoverageButNotEmptyEventReports(): Unit = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"type":"FeatureCollection","metadata":{"generated":$now},"features":[]}""")
        })
        val connector = SignalOutdoorConnector(http, connection.copy(outdoor = config.copy(sources = setOf("environment.earthquakes", "environment.tides")))) { now }
        try {
            val rows = connector.catalog()
            assertEquals(OutdoorStatus.EMPTY, rows.first { it.id == "environment.earthquakes" }.outdoor!!.status)
            assertEquals(OutdoorStatus.UNSUPPORTED, rows.first { it.id == "environment.tides" }.outdoor!!.status)
            val warning = connector.warnings.single()
            assertTrue(warning.contains("Not supported here"), warning)
            assertTrue(warning.contains("Tide"), warning)
        } finally { connector.close(); http.close() }
    }

    @Test fun homeConversionPreservesOriginalTypedProvenanceAndUnits() {
        val entity = reading.homeEntity(connection)
        assertNull(entity.values.single().measuredAt); assertNull(entity.updatedAt)
        val converted = homeDisplayReadings(entity, HomeTemperatureUnit.FAHRENHEIT).single()
        assertEquals("68", converted.value); assertEquals("°F", converted.unit)
        assertEquals("20", entity.outdoor!!.values.single().value)
        assertTrue(homeReadingAge(entity, now).startsWith("Model valid")); assertFalse(homeReadingAge(entity, now).contains("Measured"))
        val capture = outdoorObservations(entity, now).single()
        assertNull(capture.measuredAt); assertEquals("20", capture.value); assertEquals("Open-Meteo", capture.source)
        assertEquals(reading, Json.decodeFromString<OutdoorReading>(capture.fields.getValue("original_reading")))
        assertEquals(now, capture.collectedAt)
        assertEquals("stale", outdoorObservations(entity, now + 60_000).single().status)
    }

    @Test fun staleAndOfflineViewsDoNotClaimFreshAvailability() {
        val entity = reading.homeEntity(connection)
        assertFalse(homeEntityAvailable(entity, now + 60_000))
        assertTrue(homeReadingStatus(entity, now + 60_000).startsWith("Stale"))
        assertTrue(homeReadingStatus(entity.copy(available = false), now).startsWith("Unavailable"))
    }

    @Test fun watchDetailHasUnitsProviderFreshnessAndBoundedUtf8() {
        val normal = outdoorWatchDetail(reading, HomeTemperatureUnit.FAHRENHEIT, now)
        assertTrue(normal.contains("68 °F")); assertTrue(normal.contains("Open-Meteo")); assertTrue(normal.contains("Model")); assertTrue(normal.contains("Fetched"))
        val huge = reading.copy(title = "🌦".repeat(150), place = "界".repeat(200), values = listOf(OutdoorValue("x", "Long", "界".repeat(600))))
        assertTrue(outdoorWatchDetail(huge, HomeTemperatureUnit.SOURCE, now).encodeToByteArray().size <= 850)
        assertTrue(outdoorWatchDetail(reading, HomeTemperatureUnit.SOURCE, now + 60_000).contains("Stale"))
    }

    @Test fun multipleTidePredictionsHaveDistinctIdentitiesAndKeepMetric() {
        val tides = reading.copy(basis = OutdoorBasis.PREDICTION, values = listOf(
            OutdoorValue("high", "High tide", "1.2", "m", now + 60_000),
            OutdoorValue("low", "Low tide", "0.2", "m", now + 6 * 3_600_000),
            OutdoorValue("high", "High tide", "1.4", "m", now + 12 * 3_600_000)))
        val rows = outdoorObservations(tides.homeEntity(connection), now)
        assertEquals(3, rows.map { it.identity }.distinct().size)
        assertEquals(listOf("high", "low", "high"), rows.map { it.metric })
        assertEquals(tides.values.map { it.validAt }, rows.map { it.windowStart })
    }
}
