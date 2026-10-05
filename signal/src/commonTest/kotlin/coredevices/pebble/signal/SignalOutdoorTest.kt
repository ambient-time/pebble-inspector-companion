package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class SignalOutdoorTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilliseconds()
    private val place = SignalPlace("Example place", 45.52, -122.68)
    private val config = OutdoorConfig(place)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun stamp(offset: Long = 0) = Instant.fromEpochMilliseconds(now + offset).toString()
    private fun alerts(features: String = "", extra: String = "") = json("""{"type":"FeatureCollection","updated":"${stamp()}","features":[$features]$extra}""")
    private fun alert(id: String = "urn:alert:1", expires: String = stamp(3_600_000), status: String = "Actual", instruction: String = "Stay indoors") =
        """{"properties":{"id":"$id","event":"Wind Advisory","sent":"${stamp(-60_000)}","effective":"${stamp(-60_000)}","expires":"$expires","status":"$status","messageType":"Alert","severity":"Moderate","senderName":"NWS Example","areaDesc":"Example County","description":"Official details","instruction":"$instruction"}}"""
    private fun quakes(features: String = "") = json("""{"type":"FeatureCollection","metadata":{"generated":$now},"features":[$features]}""")
    private fun quake(at: Long = now - 60_000, lat: Double = place.latitude, magnitude: String = "2.1") =
        """{"id":"test1","properties":{"time":$at,"mag":$magnitude,"magType":"ml","place":"Example area"},"geometry":{"type":"Point","coordinates":[${place.longitude},$lat,6.5]}}"""

    @Test fun emptyConfigurationMakesNoNetworkRequests() = runBlocking {
        val http = HttpClient(MockEngine { error("Must remain offline") }); val api = SignalOutdoorClient(http)
        try { assertTrue(api.collect(config, now).isEmpty()); assertTrue(OutdoorConfig(place).sources.isEmpty()) }
        finally { api.close(); http.close() }
    }

    @Test fun configurationRejectsUnknownSourcesInvalidCoordinatesAndUnboundedRadius() {
        listOf(config.copy(place = place.copy(latitude = Double.NaN)), config.copy(sources = setOf("arbitrary.url")),
            config.copy(tideStation = "../secret"), config.copy(earthquakeRadiusKm = 501)).forEach {
            assertFailsWith<IllegalArgumentException> { SignalOutdoorSources.validate(it) }
        }
        assertEquals(config, Json.decodeFromString<OutdoorConfig>(Json.encodeToString(config)))
    }

    @Test fun weatherRequestsRicherFieldsAndPreservesSourceTime() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(SignalWeather.currentFields.joinToString(","), request.url.parameters["current"])
            assertNull(request.url.parameters["daily"])
            respond("""{"current":{"time":${now / 1000},"temperature_2m":20,"pressure_msl":1012,"dew_point_2m":10,"cloud_cover":0},"current_units":{"temperature_2m":"°C","pressure_msl":"hPa","dew_point_2m":"°C","cloud_cover":"%"}}""")
        }); val api = SignalOutdoorClient(http)
        try {
            val rows = api.collect(config.copy(sources = setOf("weather.current")), now)
            assertEquals(1, calls); assertEquals(13, rows.size)
            val temp = rows.first { it.id.endsWith("temperature_2m") }
            assertEquals(OutdoorBasis.MODEL, temp.basis); assertEquals(now, temp.sourceAt)
            assertEquals("20", temp.values.single().value); assertEquals("°C", temp.values.single().unit)
            assertEquals(now + 30 * 60_000, temp.expiresAt)
            assertEquals(OutdoorStatus.UNAVAILABLE, rows.first { it.id.endsWith("visibility") }.status)
            assertEquals("0", rows.first { it.id.endsWith("cloud_cover") }.values.single().value)
        } finally { api.close(); http.close() }
    }

    @Test fun dailyForecastAndDaylightRetainLocalDayAndNoMeasurementClaim() {
        val data = json("""{"timezone":"America/Los_Angeles","daily":{"time":[${now / 1000 - 18_000}],"temperature_2m_max":[21],"temperature_2m_min":[9],"sunrise":[null],"sunset":[null],"daylight_duration":[86400]},"daily_units":{"temperature_2m_max":"°C","temperature_2m_min":"°C"}}""")
        val rows = SignalWeather.parse(data, setOf("weather.daily", "weather.daylight"), place.name, "chosen place", now).flatMap { outdoorWeatherReadings(it, place) }
        val daily = rows.first { it.sourceKey == "weather.daily" }
        assertEquals(OutdoorBasis.FORECAST, daily.basis); assertNull(daily.sourceAt)
        assertTrue(daily.title.contains("2026-10-04")); assertEquals(listOf("21", "9"), daily.values.map { it.value })
        val daylight = rows.first { it.sourceKey == "weather.daylight" }
        assertEquals(OutdoorStatus.PARTIAL, daylight.status); assertNull(daylight.values.first().value)
    }

    @Test fun forecastCarriesEachHoursValidTimeAndMissingRainProbability() {
        val data = json("""{"hourly":{"time":[${now / 1000 + 3600}],"temperature_2m":[12],"precipitation_probability":[null],"precipitation":[0]},"hourly_units":{"temperature_2m":"°C","precipitation_probability":"%","precipitation":"mm"}}""")
        val row = outdoorWeatherReadings(SignalWeather.parse(data, setOf("weather.forecast"), place.name, "chosen place", now).single(), place).single()
        assertEquals(now + 3_600_000, row.validFrom); assertNull(row.sourceAt)
        assertEquals(OutdoorStatus.PARTIAL, row.status); assertNull(row.values[1].value)
        assertTrue(row.values.all { it.validAt == now + 3_600_000 })
    }

    @Test fun pollenRequestsOnlyEuropeanProviderAndReportsUnsupportedCoverage() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("cams_europe", request.url.parameters["domains"])
            assertEquals(SignalWeather.pollenFields.joinToString(","), request.url.parameters["current"])
            respond("""{"error":true,"reason":"No data is available for this location"}""", HttpStatusCode.BadRequest)
        }); val api = SignalOutdoorClient(http)
        try {
            val rows = api.collect(config.copy(sources = setOf("weather.pollen")), now)
            assertEquals(1, calls); assertEquals(6, rows.size)
            assertTrue(rows.all { it.status == OutdoorStatus.UNSUPPORTED && it.values.single().value == null })
        } finally { api.close(); http.close() }
    }

    @Test fun invalidHumidityAndUnknownTemperatureUnitsAreUnavailable() {
        assertNull(outdoorWeatherValue("relative_humidity_2m", "101", "%", now).value)
        assertNull(outdoorWeatherValue("temperature_2m", "20", "K", now).value)
        assertNull(outdoorWeatherValue("visibility", "NaN", "m", now).value)
        assertNull(outdoorWeatherValue("wind_speed_10m", "-1", "km/h", now).value)
    }

    @Test fun validEmptyAlertsAreDistinctFromFailures() {
        val result = SignalOutdoorClient.parseAlerts(alerts(), config, now).single()
        assertEquals(OutdoorStatus.EMPTY, result.status); assertTrue(result.title.startsWith("No active"))
        assertTrue(result.details.contains("not an all-clear"))
        assertFails { SignalOutdoorClient.parseAlerts(json("{}"), config, now) }
        assertFails { SignalOutdoorClient.parseAlerts(alerts().let { JsonObject(it - "updated") }, config, now) }
    }

    @Test fun alertsPreserveOfficialInstructionsExpiryAndIssuer() {
        val result = SignalOutdoorClient.parseAlerts(alerts(alert(expires = stamp(30_000))), config, now).last()
        assertEquals(OutdoorBasis.OFFICIAL_ALERT, result.basis)
        assertEquals(now + 30_000, result.expiresAt); assertTrue(result.details.contains("Stay indoors"))
        assertTrue(result.details.contains("NWS Example")); assertEquals(now - 60_000, result.sourceAt)
    }

    @Test fun expiredAndTestAlertsNeverAppearAsActive() {
        val result = SignalOutdoorClient.parseAlerts(alerts(alert(expires = stamp(-1)) + "," + alert("test", status = "Test")), config, now)
        assertEquals(1, result.size); assertEquals(OutdoorStatus.EMPTY, result.single().status)
    }

    @Test fun malformedAndPaginatedAlertsAreIncompleteNotClear() {
        val malformed = SignalOutdoorClient.parseAlerts(alerts("{}"), config, now).single()
        assertEquals(OutdoorStatus.PARTIAL, malformed.status); assertFalse(malformed.title.startsWith("No active"))
        val paginated = SignalOutdoorClient.parseAlerts(alerts(extra = ",\"pagination\":{\"next\":\"https://untrusted.test\"}"), config, now).single()
        assertEquals(OutdoorStatus.PARTIAL, paginated.status)
    }

    @Test fun unsupportedNwsPointNeverRequestsAlerts() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertTrue(request.url.encodedPath.startsWith("/points/")); respond("{}", HttpStatusCode.NotFound)
        }); val api = SignalOutdoorClient(http)
        try { assertEquals(OutdoorStatus.UNSUPPORTED, api.collect(config.copy(sources = setOf("environment.alerts")), now).single().status); assertEquals(1, calls) }
        finally { api.close(); http.close() }
    }

    @Test fun nwsCoverageThenAlertsUsesFixedEndpointAndNoRedirect() = runBlocking {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("api.weather.gov", request.url.host); assertNull(request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath.startsWith("/points/")) respond("""{"properties":{"gridId":"PQR","forecast":"https://untrusted.test"}}""")
            else { assertEquals("true", request.url.parameters["active"]); assertEquals("20", request.url.parameters["limit"]); respond(alerts().toString()) }
        }); val api = SignalOutdoorClient(http)
        try {
            assertEquals(OutdoorStatus.EMPTY, api.collect(config.copy(sources = setOf("environment.alerts")), now).single().status)
            assertEquals(listOf("/points/45.52,-122.68", "/alerts"), paths)
        } finally { api.close(); http.close() }
    }

    @Test fun selectedTideStationIsVerifiedBeforePredictionRequest() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("api.tidesandcurrents.noaa.gov", request.url.host)
            if (request.url.encodedPath.startsWith("/mdapi/")) respond("""{"stations":[{"id":"9414290","name":"San Francisco","tidal":true,"lat":37.80,"lng":-122.46}]}""")
            else {
                assertEquals("9414290", request.url.parameters["station"]); assertEquals("MLLW", request.url.parameters["datum"])
                assertEquals("gmt", request.url.parameters["time_zone"]); assertEquals("metric", request.url.parameters["units"])
                assertEquals("20261004", request.url.parameters["begin_date"]); assertEquals("20261006", request.url.parameters["end_date"])
                respond("""{"predictions":[{"t":"2026-10-04 16:00","v":"1.5","type":"H"}]}""")
            }
        }); val api = SignalOutdoorClient(http)
        try {
            val result = api.collect(config.copy(sources = setOf("environment.tides"), tideStation = "9414290"), now).single()
            assertEquals(2, calls); assertEquals(OutdoorStatus.AVAILABLE, result.status); assertTrue(result.title.contains("San Francisco"))
        } finally { api.close(); http.close() }
    }

    @Test fun pollenInCoverageMissingSeasonDoesNotBecomeZero() {
        val row = SignalWeather.parse(json("""{"current":{"time":${now / 1000},"grass_pollen":null},"current_units":{"grass_pollen":"grains/m³"}}"""), setOf("weather.pollen"), place.name, "chosen place", now).single()
        assertTrue(outdoorWeatherReadings(row, place).all { it.status == OutdoorStatus.UNAVAILABLE && it.values.single().value == null })
    }

    @Test fun tidesRequireExplicitStationWithoutNetwork() = runBlocking {
        val http = HttpClient(MockEngine { error("No station selected") }); val api = SignalOutdoorClient(http)
        try { assertEquals(OutdoorStatus.UNSUPPORTED, api.collect(config.copy(sources = setOf("environment.tides")), now).single().status) }
        finally { api.close(); http.close() }
    }

    @Test fun tidePredictionsKeepDatumStationDistanceAndFutureTimes() {
        val station = json("""{"name":"Example station","lat":45.52,"lng":-122.68}""")
        val tides = json("""{"predictions":[{"t":"2026-10-04 11:30","v":"0.5","type":"L"},{"t":"2026-10-04 16:00","v":"1.5","type":"H"},{"t":"2026-10-04 22:00","v":"-0.2","type":"L"}]}""")
        val result = SignalOutdoorClient.parseTides(tides, station, config.copy(tideStation = "9414290"), now).single()
        assertEquals(OutdoorBasis.PREDICTION, result.basis); assertNull(result.sourceAt)
        assertEquals(2, result.values.size); assertEquals("m MLLW", result.values.first().unit)
        assertTrue(result.details.contains("0 km")); assertTrue(result.details.contains("9414290"))
        assertTrue(result.values.all { it.validAt!! > now })
    }

    @Test fun emptyOrMalformedTidesDoNotInventPredictions() {
        val station = json("""{"name":"Example","lat":0,"lng":0}""")
        assertFails { SignalOutdoorClient.parseTides(json("{}"), station, config, now) }
        assertEquals(OutdoorStatus.UNAVAILABLE, SignalOutdoorClient.parseTides(json("""{"predictions":[]}"""), station, config, now).single().status)
    }

    @Test fun earthquakesPreserveTimeMagnitudeTypeDepthAndDistance() {
        val result = SignalOutdoorClient.parseEarthquakes(quakes(quake()), config, now)
        assertEquals(2, result.size); assertEquals(now - 60_000, result.last().sourceAt)
        assertEquals("ml", result.last().values[0].unit); assertEquals("6.5", result.last().values[1].value)
        assertEquals("0", result.last().values[2].value)
    }

    @Test fun emptyInvalidStaleAndCappedEarthquakeReportsStayDistinct() {
        assertEquals(OutdoorStatus.EMPTY, SignalOutdoorClient.parseEarthquakes(quakes(), config, now).single().status)
        assertEquals(OutdoorStatus.PARTIAL, SignalOutdoorClient.parseEarthquakes(quakes(quake(magnitude = "null")), config, now).single().status)
        assertFails { SignalOutdoorClient.parseEarthquakes(quakes().let { JsonObject(it + ("metadata" to json("""{"generated":${now - 3_600_000}}"""))) }, config, now) }
        val capped = SignalOutdoorClient.parseEarthquakes(quakes(List(20) { quake() }.joinToString(",")), config, now)
        assertEquals(OutdoorStatus.PARTIAL, capped.first().status)
    }

    @Test fun earthquakeQueriesAreBoundedAndDoNotSendCredentials() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++; assertEquals("earthquake.usgs.gov", request.url.host)
            assertEquals("20", request.url.parameters["limit"]); assertEquals("100", request.url.parameters["maxradiuskm"])
            assertEquals(stamp(-86_400_000), request.url.parameters["starttime"])
            assertNull(request.headers[HttpHeaders.Authorization]); respond(quakes().toString())
        }); val api = SignalOutdoorClient(http)
        try { assertEquals(OutdoorStatus.EMPTY, api.collect(config.copy(sources = setOf("environment.earthquakes")), now).single().status); assertEquals(1, calls) }
        finally { api.close(); http.close() }
    }

    @Test fun redirectMalformedOversizedAndDeepBodiesFailWithoutEchoOrRetry() = runBlocking {
        for ((body, status) in listOf("private response" to HttpStatusCode.Found, "{" to HttpStatusCode.OK,
            "x".repeat(512 * 1024 + 1) to HttpStatusCode.OK, ("[".repeat(34) + "]".repeat(34)) to HttpStatusCode.OK)) {
            var calls = 0
            val http = HttpClient(MockEngine { calls++; respond(body, status, headersOf(HttpHeaders.Location, "https://untrusted.test")) }); val api = SignalOutdoorClient(http)
            try {
                val result = api.collect(config.copy(sources = setOf("environment.earthquakes")), now).single()
                assertEquals(OutdoorStatus.UNAVAILABLE, result.status); assertFalse(result.details.contains("private response")); assertEquals(1, calls)
            } finally { api.close(); http.close() }
        }
    }

    @Test fun cancellationReachesPublicTransport() = runBlocking {
        val started = CompletableDeferred<Unit>(); val ended = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { started.complete(Unit); try { awaitCancellation() } finally { ended.complete(Unit) } }); val api = SignalOutdoorClient(http)
        try {
            val job = launch { api.collect(config.copy(sources = setOf("environment.earthquakes")), now) }
            started.await(); job.cancelAndJoin(); ended.await(); assertTrue(job.isCancelled)
        } finally { api.close(); http.close() }
    }

    @Test fun partialProviderFailureDoesNotDiscardSuccessfulWeather() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            if (request.url.host == "api.open-meteo.com") respond("""{"current":{"time":${now / 1000},"temperature_2m":20},"current_units":{"temperature_2m":"°C"}}""")
            else respond("provider failed", HttpStatusCode.ServiceUnavailable)
        }); val api = SignalOutdoorClient(http)
        try {
            val result = api.collect(config.copy(sources = setOf("weather.current", "environment.earthquakes")), now)
            assertTrue(result.any { it.sourceKey == "weather.current" && it.status == OutdoorStatus.AVAILABLE })
            assertEquals(OutdoorStatus.UNAVAILABLE, result.last().status)
        } finally { api.close(); http.close() }
    }

    @Test fun closingAdapterDoesNotCloseSharedHttpEngine() = runBlocking {
        val http = HttpClient(MockEngine { respond(quakes().toString()) })
        val first = SignalOutdoorClient(http); first.close()
        val next = SignalOutdoorClient(http)
        try { assertEquals(OutdoorStatus.EMPTY, next.collect(config.copy(sources = setOf("environment.earthquakes")), now).single().status) }
        finally { next.close(); http.close() }
    }
}
