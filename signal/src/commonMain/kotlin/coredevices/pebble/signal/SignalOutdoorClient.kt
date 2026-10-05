package coredevices.pebble.signal

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Instant

class SignalOutdoorClient(http: HttpClient) {
    private val client = HttpClient(http.engine) { followRedirects = false; expectSuccess = false }
    private val weather = SignalWeather(http)
    fun close() { weather.close(); client.close() }
    suspend fun searchPlaces(query: String): List<SignalPlace> = weather.search(query)

    suspend fun collect(config: OutdoorConfig, now: Long = Clock.System.now().toEpochMilliseconds()): List<OutdoorReading> = supervisorScope {
        SignalOutdoorSources.validate(config)
        if (config.sources.isEmpty()) return@supervisorScope emptyList()
        val weatherKeys = config.sources.intersect(SignalWeather.keys)
        val jobs = mutableListOf<Deferred<List<OutdoorReading>>>()
        if (weatherKeys.isNotEmpty()) jobs += async {
            weather.collect(weatherKeys, config.place, now = now).flatMap { outdoorWeatherReadings(it, config.place) }
        }
        listOf("environment.alerts", "environment.tides", "environment.earthquakes").filter { it in config.sources }.forEach { key ->
            jobs += async { guarded(key, config, now) { when (key) {
                "environment.alerts" -> alerts(config, now)
                "environment.tides" -> tides(config, now)
                else -> earthquakes(config, now)
            } } }
        }
        jobs.awaitAll().flatten()
    }

    private suspend fun guarded(key: String, config: OutdoorConfig, now: Long, block: suspend () -> List<OutdoorReading>): List<OutdoorReading> = try {
        withTimeoutOrNull(16_000) { block() } ?: listOf(unavailable(key, config, now, "Provider timed out. Refresh to try again."))
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { listOf(unavailable(key, config, now, "Provider unavailable. No current conclusion can be drawn.")) }

    private suspend fun get(url: String, parameters: Map<String, String> = emptyMap(), allowNotFound: Boolean = false): JsonObject? =
        withTimeout(7_000) {
            client.prepareGet(url) {
                header("User-Agent", "SignalStation (https://dr.eamer.dev/downloads/apps/signal-station/)")
                header("Accept", "application/geo+json, application/json")
                parameters.forEach { (key, value) -> parameter(key, value) }
            }.execute { response ->
                if (allowNotFound && response.status.value == 404) return@execute null
                check(response.status.value in 200..299)
                val bytes = ByteArray(512 * 1024 + 1)
                val channel = response.bodyAsChannel()
                var count = 0
                while (count < bytes.size) {
                    val read = channel.readAvailable(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 512 * 1024)
                val text = bytes.decodeToString(0, count)
                check(outdoorJsonDepth(text) <= 32)
                (Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid public response")).also {
                    check("error" !in it && it.number("status")?.let { status -> status >= 400 } != true)
                }
            }
        }

    private suspend fun alerts(config: OutdoorConfig, now: Long): List<OutdoorReading> {
        val point = "${coordinate(config.place.latitude)},${coordinate(config.place.longitude)}"
        val coverage = get("https://api.weather.gov/points/$point", allowNotFound = true)
            ?: return listOf(unavailable("environment.alerts", config, now, "NWS does not cover this location. Other regional alert providers are not configured.", OutdoorStatus.UNSUPPORTED))
        check(coverage["properties"] is JsonObject && coverage.obj("properties").text("gridId").isNotBlank())
        val data = get("https://api.weather.gov/alerts", mapOf("active" to "true", "point" to point, "limit" to "20"))!!
        return parseAlerts(data, config, now)
    }

    private suspend fun tides(config: OutdoorConfig, now: Long): List<OutdoorReading> {
        if (config.tideStation.isEmpty()) return listOf(unavailable("environment.tides", config, now, "Choose a NOAA tide prediction station. No station is selected automatically.", OutdoorStatus.UNSUPPORTED))
        val data = get("https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations/${config.tideStation}.json", allowNotFound = true)
            ?: return listOf(unavailable("environment.tides", config, now, "This NOAA station is not available.", OutdoorStatus.UNSUPPORTED))
        val station = (data["stations"] as? JsonArray)?.singleOrNull() as? JsonObject ?: error("Station unavailable")
        check(station.text("id") == config.tideStation && station.text("name").isNotBlank())
        if ((station["tidal"] as? JsonPrimitive)?.booleanOrNull != true)
            return listOf(unavailable("environment.tides", config, now, "This station does not provide tidal data.", OutdoorStatus.UNSUPPORTED))
        val parameters = mapOf("station" to config.tideStation, "product" to "predictions", "begin_date" to date(now), "end_date" to date(now + 48 * 60 * 60_000),
            "datum" to "MLLW", "time_zone" to "gmt", "interval" to "hilo", "units" to "metric", "format" to "json", "application" to "SignalStation")
        return parseTides(get("https://api.tidesandcurrents.noaa.gov/api/prod/datagetter", parameters)!!, station, config, now)
    }

    private suspend fun earthquakes(config: OutdoorConfig, now: Long): List<OutdoorReading> {
        val params = mapOf("format" to "geojson", "latitude" to coordinate(config.place.latitude), "longitude" to coordinate(config.place.longitude),
            "maxradiuskm" to config.earthquakeRadiusKm.toString(), "starttime" to stamp(now - 24 * 60 * 60_000), "endtime" to stamp(now),
            "orderby" to "time", "limit" to "20", "eventtype" to "earthquake")
        return parseEarthquakes(get("https://earthquake.usgs.gov/fdsnws/event/1/query", params)!!, config, now)
    }

    companion object {
        private fun coordinate(value: Double) = (round(value * 100) / 100).toString()
        private fun stamp(time: Long) = Instant.fromEpochMilliseconds(time).toString()
        private fun date(time: Long) = stamp(time).take(10).replace("-", "")
        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        private fun clean(text: String, limit: Int) = text.filter { it == '\n' || it == '\t' || it.code >= 32 && it.code != 127 }.take(limit)
        private fun base(key: String, config: OutdoorConfig, now: Long, status: OutdoorStatus, details: String = ""): OutdoorReading {
            val (provider, link, basis) = when (key) {
                "environment.alerts" -> Triple("National Weather Service", "https://www.weather.gov/", OutdoorBasis.OFFICIAL_ALERT)
                "environment.tides" -> Triple("NOAA CO-OPS", "https://tidesandcurrents.noaa.gov/", OutdoorBasis.PREDICTION)
                else -> Triple("USGS", "https://earthquake.usgs.gov/", OutdoorBasis.EVENT)
            }
            return OutdoorReading(key, key, SignalOutdoorSources.sources.first { it.key == key }.name, provider, link, basis,
                status, now, now + if (key == "environment.tides") 60 * 60_000 else 5 * 60_000,
                config.place.name, details = details)
        }
        internal fun unavailable(key: String, config: OutdoorConfig, now: Long, details: String, status: OutdoorStatus = OutdoorStatus.UNAVAILABLE) = base(key, config, now, status, details)

        internal fun parseAlerts(data: JsonObject, config: OutdoorConfig, now: Long): List<OutdoorReading> {
            check(data.text("type") == "FeatureCollection")
            val features = data["features"] as? JsonArray ?: error("Missing alerts")
            check(features.size <= 20)
            val updated = homeTime(data.text("updated"))
            check(updated != null && updated <= now + 60_000 && now - updated <= 30 * 60_000)
            val truncated = data.obj("pagination").text("next").isNotEmpty() || features.size == 20
            var invalid = 0
            val active = features.mapNotNull { feature ->
                val row = (feature as? JsonObject)?.get("properties") as? JsonObject
                if (row == null) { invalid++; return@mapNotNull null }
                if (row.text("status") in setOf("Test", "Exercise", "Draft") || row.text("messageType") == "Cancel") return@mapNotNull null
                val id = row.text("id"); val title = row.text("event")
                val issued = homeTime(row.text("sent")); val expires = homeTime(row.text("expires"))
                if (id.isBlank() || title.isBlank() || issued == null || issued > now + 60_000 || expires == null || row.text("status") != "Actual") { invalid++; return@mapNotNull null }
                if (expires <= now) return@mapNotNull null
                val starts = homeTime(row.text("onset")) ?: homeTime(row.text("effective"))
                base("environment.alerts", config, now, OutdoorStatus.AVAILABLE).copy(
                    id = "environment.alerts:$id", title = clean(title, 120), sourceAt = issued, validFrom = starts, validUntil = expires,
                    expiresAt = minOf(expires, now + 5 * 60_000), values = listOf(
                        OutdoorValue("severity", "Severity", clean(row.text("severity"), 60).ifBlank { "Unknown" }),
                        OutdoorValue("area", "Affected area", clean(row.text("areaDesc"), 600).ifBlank { null }),
                        OutdoorValue("expires", "Expires (UTC)", stamp(expires)),
                    ), details = listOf("Official issuer: ${clean(row.text("senderName"), 150)}", clean(row.text("headline"), 600), clean(row.text("description"), 10_000), clean(row.text("instruction"), 10_000),
                        if (row.text("description").length > 10_000 || row.text("instruction").length > 10_000) "Long alert text is abbreviated here. Consult the official service for the complete alert." else "").filter { it.isNotBlank() }.joinToString("\n\n"))
            }.distinctBy { it.id }
            val partial = invalid > 0 || truncated
            val summary = base("environment.alerts", config, now, if (partial) OutdoorStatus.PARTIAL else if (active.isEmpty()) OutdoorStatus.EMPTY else OutdoorStatus.AVAILABLE,
                if (partial) "Partial alert results. Check the official service for complete information." else "On-demand official reports, not emergency notifications. Absence of a report is not an all-clear.")
                .copy(sourceAt = updated, basis = OutdoorBasis.REPORT, values = listOf(OutdoorValue("reported", "Active reports returned", active.size.toString())),
                    title = if (partial) "Weather alerts · incomplete" else if (active.isEmpty()) "No active NWS alerts returned" else "${active.size} active NWS alerts")
            return listOf(summary) + active
        }

        internal fun parseTides(data: JsonObject, station: JsonObject, config: OutdoorConfig, now: Long): List<OutdoorReading> {
            val predictions = data["predictions"] as? JsonArray ?: error("Missing predictions")
            check(predictions.size <= 32)
            val lat = station.number("lat") ?: error("Missing station latitude")
            val lon = station.number("lng") ?: error("Missing station longitude")
            check(SignalContext.validCoordinate(lat, lon))
            val distance = (SignalContext.distance(config.place.latitude, config.place.longitude, lat, lon) / 1000).toInt()
            var invalid = 0
            val rows = predictions.mapNotNull { entry ->
                val row = entry as? JsonObject
                val at = row?.text("t")?.let { homeTime(it.replace(' ', 'T') + ":00Z") }
                val height = row?.number("v")
                val type = row?.text("type")
                if (at == null || height == null || height !in -20.0..30.0 || type !in setOf("H", "L")) { invalid++; return@mapNotNull null }
                if (at !in now..now + 48 * 60 * 60_000) return@mapNotNull null
                OutdoorValue(if (type == "H") "high" else "low", "${if (type == "H") "High" else "Low"} · ${stamp(at)}", height.toString(), "m MLLW", at)
            }.distinctBy { it.validAt }.sortedBy { it.validAt }.take(8)
            return listOf(base("environment.tides", config, now, if (rows.isEmpty()) OutdoorStatus.UNAVAILABLE else if (invalid > 0) OutdoorStatus.PARTIAL else OutdoorStatus.AVAILABLE,
                "Station ${config.tideStation}: ${clean(station.text("name"), 120)} · $distance km from chosen place. Predictions for this station only; metres above Mean Lower Low Water (MLLW). Times UTC. Not for navigation.")
                .copy(title = "Tides · ${clean(station.text("name"), 100)}", values = rows, validFrom = rows.firstOrNull()?.validAt, validUntil = rows.lastOrNull()?.validAt))
        }

        internal fun parseEarthquakes(data: JsonObject, config: OutdoorConfig, now: Long): List<OutdoorReading> {
            check(data.text("type") == "FeatureCollection")
            val features = data["features"] as? JsonArray ?: error("Missing events")
            check(features.size <= 20)
            val generated = (data.obj("metadata")["generated"] as? JsonPrimitive)?.longOrNull
            check(generated != null && generated <= now + 60_000 && now - generated <= 30 * 60_000)
            var invalid = 0
            val rows = features.mapNotNull { entry ->
                val feature = entry as? JsonObject
                val row = feature?.get("properties") as? JsonObject
                val coordinates = feature?.obj("geometry")?.get("coordinates") as? JsonArray
                val at = (row?.get("time") as? JsonPrimitive)?.longOrNull
                val magnitude = row?.number("mag")
                val lat = (coordinates?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                val lon = (coordinates?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val depth = (coordinates?.getOrNull(2) as? JsonPrimitive)?.doubleOrNull
                if (feature == null || row == null || feature.text("id").isBlank() || at == null || magnitude == null || magnitude !in -3.0..10.0 ||
                    lat == null || lon == null || !SignalContext.validCoordinate(lat, lon) || depth == null || !depth.isFinite() || depth !in -100.0..1000.0) { invalid++; return@mapNotNull null }
                val distance = SignalContext.distance(config.place.latitude, config.place.longitude, lat, lon) / 1000
                if (at !in now - 24 * 60 * 60_000..now || distance > config.earthquakeRadiusKm + 2) return@mapNotNull null
                base("environment.earthquakes", config, now, OutdoorStatus.AVAILABLE).copy(
                    id = "environment.earthquakes:${clean(feature.text("id"), 120)}", title = "M$magnitude · ${clean(row.text("place"), 120)}",
                    sourceAt = at, values = listOf(OutdoorValue("magnitude", "Magnitude", magnitude.toString(), clean(row.text("magType"), 16)),
                        OutdoorValue("depth", "Depth", depth.toString(), "km"), OutdoorValue("distance", "Distance", distance.toInt().toString(), "km")),
                    details = "Event ${clean(feature.text("id"), 120)} · ${stamp(at)} · USGS catalog. Values may be revised. This is not an earthquake warning service.")
            }.distinctBy { it.id }
            val partial = invalid > 0 || features.size == 20
            val summary = base("environment.earthquakes", config, now, if (partial) OutdoorStatus.PARTIAL else if (rows.isEmpty()) OutdoorStatus.EMPTY else OutdoorStatus.AVAILABLE,
                "Last 24 hours within ${config.earthquakeRadiusKm} km; at most 20 latest events. ${if (partial) "Results incomplete. " else ""}No returned events does not guarantee no seismic activity.")
                .copy(sourceAt = generated, basis = OutdoorBasis.REPORT, title = if (partial) "Recent earthquakes · incomplete" else if (rows.isEmpty()) "No matching earthquakes returned" else "${rows.size} recent earthquakes",
                    values = listOf(OutdoorValue("reported", "Events returned", rows.size.toString())))
            return listOf(summary) + rows
        }
    }
}

private fun outdoorJsonDepth(text: String): Int {
    var depth = 0; var maximum = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; maximum = maxOf(maximum, depth); if (maximum > 32) return maximum }; '}', ']' -> depth-- }
    }
    return maximum
}
