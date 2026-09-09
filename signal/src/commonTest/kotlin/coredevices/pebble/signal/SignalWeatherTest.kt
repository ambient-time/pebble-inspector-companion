package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SignalWeatherTest {
    private val now = 1_800_000_000_000L
    private val place = SignalPlace("Test city", 45.0, -122.0)

    @Test fun disabledSourcesMakeNoRequests() = runBlocking {
        val http = HttpClient(MockEngine { error("No request expected") })
        val weather = SignalWeather(http)
        try { assertTrue(weather.collect(emptySet(), place).isEmpty()) }
        finally { weather.close(); http.close() }
    }

    @Test fun uvAloneDoesNotFetchWeatherOrPollutants() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("air-quality-api.open-meteo.com", request.url.host)
            assertEquals("uv_index", request.url.parameters["current"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"current":{"time":1800000000,"uv_index":3.2,"us_aqi":45},"current_units":{"uv_index":""}}""")
        })
        val weather = SignalWeather(http)
        try {
            val rows = weather.collect(setOf("weather.uv"), place, now = now)
            assertEquals(1, calls); assertEquals(listOf("weather.uv"), rows.map { it.key })
            assertEquals(now, rows.single().measuredAt)
            assertFalse(rows.single().value.contains("latitude")); assertFalse(rows.single().value.contains("us_aqi"))
        } finally { weather.close(); http.close() }
    }

    @Test fun missingValuesStayMissingAndOldConditionsStayStale() {
        val data = Json.parseToJsonElement("""{"current":{"time":1799900000,"temperature_2m":0,"apparent_temperature":null},"current_units":{"temperature_2m":"°C"}}""").jsonObject
        val row = SignalWeather.parse(data, setOf("weather.current"), "City", "chosen place", now).single()
        assertEquals("stale_model", row.status)
        assertTrue(row.value.contains("temperature_2m=0.0 °C"))
        assertTrue(row.value.contains("apparent_temperature=unavailable"))
        assertFalse(row.value.contains("apparent_temperature=0"))
        val missing = SignalWeather.parse(JsonObject(emptyMap()), setOf("weather.current"), "City", "chosen place", now).single()
        assertEquals("unavailable", missing.status); assertNull(missing.measuredAt)
    }

    @Test fun polarDaylightDoesNotInventSunriseOrRemainingTime() {
        val data = Json.parseToJsonElement("""{"daily":{"sunrise":[null],"sunset":[null],"daylight_duration":[86400]}}""").jsonObject
        val row = SignalWeather.parse(data, setOf("weather.daylight"), "City", "chosen place", now).single()
        assertEquals("partial_or_polar", row.status)
        assertTrue(row.value.contains("daylight_seconds=86400.0"))
        assertTrue(row.value.contains("sunrise=unavailable"))
        assertTrue(row.value.contains("daylight_remaining_seconds=unavailable"))
        assertEquals("unavailable", SignalWeather.parse(JsonObject(emptyMap()), setOf("weather.daylight"), "City", "chosen place", now).single().status)
    }

    @Test fun forecastFiltersPastHoursAndRetainsCoverageAndUnits() {
        val data = Json.parseToJsonElement("""{"hourly":{"time":[1799996400,1800003600,1800007200,1800090000],"temperature_2m":[10,12,null,90],"precipitation_probability":[0,40,60,99]},"hourly_units":{"temperature_2m":"°C","precipitation_probability":"%"}}""").jsonObject
        val row = SignalWeather.parse(data, setOf("weather.forecast"), "City", "chosen place", now).single()
        assertEquals("forecast", row.status); assertNull(row.measuredAt)
        assertEquals(now + 3_600_000, row.windowStart)
        assertTrue(row.value.contains("forecast hours=2/6"))
        assertTrue(row.value.contains("precipitation_probability=40.0 %"))
        assertFalse(row.value.contains("90.0"))
    }

    @Test fun errorsDoNotEchoResponsesOrFollowRedirects() = runBlocking {
        for (body in listOf("private location", "{}", "x".repeat(65538))) {
            var calls = 0
            val http = HttpClient(MockEngine { calls++; respond(body, HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test")) })
            val weather = SignalWeather(http)
            try {
                val row = weather.collect(setOf("weather.current"), place).single()
                assertEquals(1, calls); assertEquals("service_unavailable", row.status); assertEquals("", row.value)
            } finally { weather.close(); http.close() }
        }
    }

    @Test fun malformedAndOversizedSuccessBodiesFailClosed() = runBlocking {
        for (body in listOf("{", "x".repeat(65538), "[]")) {
            val http = HttpClient(MockEngine { respond(body) })
            val weather = SignalWeather(http)
            try { assertEquals("service_unavailable", weather.collect(setOf("weather.current"), place).single().status) }
            finally { weather.close(); http.close() }
        }
    }

    @Test fun searchReturnsChoicesWithoutSilentlySelecting() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("geocoding-api.open-meteo.com", request.url.host)
            assertEquals("Portland", request.url.parameters["name"])
            respond("""{"results":[{"name":"Portland","admin1":"Oregon","country":"United States","latitude":45.5,"longitude":-122.6},{"name":"Invalid","latitude":100,"longitude":0}]}""")
        })
        val weather = SignalWeather(http)
        try {
            assertEquals(listOf(SignalPlace("Portland, Oregon, United States", 45.5, -122.6)), weather.search("Portland"))
            assertNull(SignalSettings().weatherPlace)
        } finally { weather.close(); http.close() }
    }

    @Test fun cancellationReachesTransport() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { entered.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } })
        val weather = SignalWeather(http)
        try {
            val job = launch { weather.collect(setOf("weather.current"), place) }
            entered.await(); job.cancelAndJoin(); stopped.await(); assertTrue(job.isCancelled)
        } finally { weather.close(); http.close() }
    }

    @Test fun oldSettingsDoNotEnableNewSources() {
        val settings = Json.decodeFromString<SignalSettings>("""{"enabled":["location"]}""")
        assertEquals(setOf("location"), settings.enabled)
        assertEquals("place", settings.weatherLocation); assertNull(settings.weatherPlace)
        val record = SignalRecord("r", "t", now, "survey", provider = "openai", model = "m", sourceKeys = setOf("weather.uv"))
        assertFalse(SignalHistory.allowed(record, settings.enabled))
    }

    @Test fun historyWeatherQueriesSelectRelevantSavedEvidence() {
        val keys = setOf("weather.current", "weather.uv")
        val record = SignalRecord("r", "t", now, "survey", provider = "openai", model = "m", state = "ready", sourceKeys = keys,
            observations = listOf(SignalObservation("weather.current", "Open-Meteo", "temperature_2m=12", collectedAt = now), SignalObservation("weather.uv", "Open-Meteo / CAMS", "uv_index=3", collectedAt = now)))
        val result = SignalHistory.retrieve(listOf(record), "What was the UV?", keys, now = now).single()
        assertEquals(listOf("weather.uv"), result.observations.map { it.key })
        assertEquals("", result.answer)
        assertTrue(SignalHistory.retrieve(listOf(record), "UV", setOf("weather.current"), now = now).isEmpty())
    }
}
