package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/** On-demand public model data. No keys, device identifiers, or raw response bodies enter reports. */
class SignalWeather(http: HttpClient) {
    private val client = HttpClient(http.engine) { followRedirects = false; expectSuccess = false }
    fun close() = client.close()

    suspend fun search(query: String): List<SignalPlace> {
        require(query.trim().length in 2..100)
        val data = get("https://geocoding-api.open-meteo.com/v1/search", mapOf("name" to query.trim(), "count" to "5", "language" to "en"))
        return (data["results"] as? JsonArray).orEmpty().take(5).mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val lat = row.number("latitude") ?: return@mapNotNull null
            val lon = row.number("longitude") ?: return@mapNotNull null
            val label = listOf("name", "admin1", "country").mapNotNull { (row[it] as? JsonPrimitive)?.contentOrNull?.take(100) }.distinct().joinToString(", ")
            SignalPlace(label, lat, lon).takeIf { validPlace(it) }
        }
    }

    suspend fun collect(enabled: Set<String>, place: SignalPlace, locationNote: String = "chosen place", now: Long = Clock.System.now().toEpochMilliseconds()): List<SignalObservation> = supervisorScope {
        val selected = enabled.intersect(keys)
        if (selected.isEmpty()) return@supervisorScope emptyList()
        if (!validPlace(place)) return@supervisorScope missing(selected, "place_unavailable", now)
        val base = mapOf("latitude" to place.latitude.toString(), "longitude" to place.longitude.toString(), "timezone" to "auto", "timeformat" to "unixtime")
        val weatherKeys = selected - setOf("weather.air_quality", "weather.uv")
        val airKeys = selected - weatherKeys
        listOf(async {
            guarded(weatherKeys, now) {
                val params = base + buildMap {
                    put("forecast_days", "2")
                    if ("weather.current" in weatherKeys) put("current", "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m")
                    if ("weather.forecast" in weatherKeys) { put("hourly", "temperature_2m,precipitation_probability,precipitation"); put("forecast_hours", "7") }
                    if ("weather.daylight" in weatherKeys) put("daily", "sunrise,sunset,daylight_duration")
                }
                parse(get("https://api.open-meteo.com/v1/forecast", params), weatherKeys, place.name, locationNote, now)
            }
        }, async {
            guarded(airKeys, now) {
                val fields = buildList {
                    if ("weather.air_quality" in airKeys) addAll(listOf("us_aqi", "pm2_5", "pm10"))
                    if ("weather.uv" in airKeys) add("uv_index")
                }
                parse(get("https://air-quality-api.open-meteo.com/v1/air-quality", base + mapOf("current" to fields.joinToString(","))), airKeys, place.name, locationNote, now)
            }
        }).awaitAll().flatten()
    }

    private suspend fun guarded(keys: Set<String>, now: Long, block: suspend () -> List<SignalObservation>): List<SignalObservation> {
        if (keys.isEmpty()) return emptyList()
        return try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { missing(keys, "service_unavailable", now) }
    }

    private suspend fun get(url: String, parameters: Map<String, String>): JsonObject =
        withTimeoutOrNull(7_000) {
            client.prepareGet(url) { parameters.forEach { (key, value) -> parameter(key, value) } }.execute { response ->
                check(response.status.value in 200..299)
                val channel = response.bodyAsChannel()
                val bytes = ByteArray(64 * 1024 + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = channel.readAvailable(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 64 * 1024)
                Json.parseToJsonElement(bytes.decodeToString(0, count)).jsonObject.also { check(it["error"] != JsonPrimitive(true)) }
            }
        } ?: error("Weather request timed out")

    companion object {
        val sources = listOf(
            SignalSource("weather.current", "Current weather", "Weather"),
            SignalSource("weather.forecast", "Next six hours", "Weather"),
            SignalSource("weather.daylight", "Sunrise, sunset and daylight", "Weather"),
            SignalSource("weather.air_quality", "Air quality", "Weather"),
            SignalSource("weather.uv", "UV index", "Weather"),
        )
        val keys = sources.map { it.key }.toSet()
        fun validPlace(place: SignalPlace) = place.name.isNotBlank() && place.latitude.isFinite() && place.longitude.isFinite() && place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0
        fun missing(keys: Collection<String>, status: String, now: Long = Clock.System.now().toEpochMilliseconds()) = keys.map { SignalObservation(it, "Open-Meteo", collectedAt = now, status = status) }
        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        private fun seconds(value: JsonElement?): Long? = (value as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..32_503_680_000L }?.times(1000)
        private fun stamp(time: Long) = Instant.fromEpochMilliseconds(time).toString()

        fun parse(data: JsonObject, enabled: Set<String>, placeName: String, locationNote: String, now: Long): List<SignalObservation> {
            val output = mutableListOf<SignalObservation>()
            val current = data["current"] as? JsonObject ?: JsonObject(emptyMap())
            val at = seconds(current["time"])
            val currentUnits = data["current_units"] as? JsonObject ?: JsonObject(emptyMap())
            val label = "place=${placeName.take(300)}; location=$locationNote; data=modeled conditions"
            fun fields(row: JsonObject, names: List<String>, units: JsonObject): String = names.joinToString("; ") { name ->
                "$name=${row.number(name)?.toString() ?: "unavailable"} ${(units[name] as? JsonPrimitive)?.contentOrNull.orEmpty().take(30)}".trim()
            }
            fun addCurrent(key: String, names: List<String>) {
                if (key !in enabled) return
                if (at == null || names.none { current.number(it) != null }) { output += missing(listOf(key), "unavailable", now); return }
                val status = if (at > now + 15 * 60_000 || now - at > 2 * 60 * 60_000) "stale_model" else "modeled"
                output += SignalObservation(key, "Open-Meteo" + if (key == "weather.air_quality" || key == "weather.uv") " / CAMS" else "", "$label; ${fields(current, names, currentUnits)}", collectedAt = now, measuredAt = at, status = status)
            }
            addCurrent("weather.current", listOf("temperature_2m", "apparent_temperature", "relative_humidity_2m", "precipitation", "weather_code", "wind_speed_10m"))
            addCurrent("weather.air_quality", listOf("us_aqi", "pm2_5", "pm10"))
            addCurrent("weather.uv", listOf("uv_index"))
            if ("weather.forecast" in enabled) {
                val hourly = data["hourly"] as? JsonObject ?: JsonObject(emptyMap())
                val times = hourly["time"] as? JsonArray ?: JsonArray(emptyList())
                val units = data["hourly_units"] as? JsonObject ?: JsonObject(emptyMap())
                val names = listOf("temperature_2m", "precipitation_probability", "precipitation")
                val rows = times.mapIndexedNotNull { index, time ->
                    val t = seconds(time)?.takeIf { it >= now && it <= now + 6 * 60 * 60_000 } ?: return@mapIndexedNotNull null
                    val row = JsonObject(names.associateWith { (hourly[it] as? JsonArray)?.getOrNull(index) ?: JsonNull })
                    if (names.none { row.number(it) != null }) null else t to "${stamp(t)}: ${fields(row, names, units)}"
                }.distinctBy { it.first }.sortedBy { it.first }.take(6)
                if (rows.isEmpty()) output += missing(listOf("weather.forecast"), "unavailable", now)
                else output += SignalObservation("weather.forecast", "Open-Meteo", "$label; forecast hours=${rows.size}/6; ${rows.joinToString(" | ") { it.second }}", collectedAt = now, status = "forecast", period = "next_6_hours", windowStart = rows.first().first, windowEnd = rows.last().first)
            }
            if ("weather.daylight" in enabled) {
                val daily = data["daily"] as? JsonObject ?: JsonObject(emptyMap())
                val sunrise = seconds((daily["sunrise"] as? JsonArray)?.firstOrNull())
                val sunset = seconds((daily["sunset"] as? JsonArray)?.firstOrNull())
                val duration = ((daily["daylight_duration"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..86400.0 }
                // Polar dates can have no rise/set. Do not invent either boundary or remaining daylight.
                val normalDay = sunrise != null && sunset != null && sunrise < sunset && sunset - sunrise <= 86_400_000 && kotlin.math.abs(sunrise - now) < 86_400_000
                val remaining = if (normalDay) ((sunset!! - maxOf(now, sunrise!!)).coerceAtLeast(0) / 1000).toString() else "unavailable"
                val value = "$label; sunrise=${sunrise?.let(::stamp) ?: "unavailable"}; sunset=${sunset?.let(::stamp) ?: "unavailable"}; daylight_seconds=${duration ?: "unavailable"}; daylight_remaining_seconds=$remaining"
                output += SignalObservation("weather.daylight", "Open-Meteo", value, collectedAt = now, status = if (normalDay) "calculated" else if (duration == null && sunrise == null && sunset == null) "unavailable" else "partial_or_polar", period = "daylight", windowStart = sunrise, windowEnd = sunset)
            }
            return output.filter { it.key in enabled }
        }
    }
}
