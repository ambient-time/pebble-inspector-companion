package coredevices.pebble.signal

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal val outdoorWeatherLabels = mapOf(
    "temperature_2m" to "Outdoor temperature", "apparent_temperature" to "Feels like",
    "relative_humidity_2m" to "Outdoor humidity", "precipitation" to "Precipitation",
    "weather_code" to "Conditions", "wind_speed_10m" to "Wind", "wind_direction_10m" to "Wind direction",
    "wind_gusts_10m" to "Wind gusts", "pressure_msl" to "Sea-level pressure",
    "surface_pressure" to "Surface pressure", "dew_point_2m" to "Dewpoint",
    "visibility" to "Visibility", "cloud_cover" to "Cloud cover", "us_aqi" to "US air quality index",
    "pm2_5" to "PM2.5", "pm10" to "PM10", "uv_index" to "UV index",
    "temperature_2m_max" to "Daily high", "temperature_2m_min" to "Daily low",
    "precipitation_probability" to "Rain probability",
) + SignalWeather.pollenFields.associateWith { it.replace('_', ' ').replaceFirstChar(Char::uppercase) }

internal fun outdoorWeatherValue(key: String, raw: String?, unit: String, at: Long?): OutdoorValue {
    val n = raw?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val expectedUnits = when (key) {
        "temperature_2m", "apparent_temperature", "dew_point_2m", "temperature_2m_max", "temperature_2m_min" -> setOf("°C")
        "relative_humidity_2m", "cloud_cover", "precipitation_probability" -> setOf("%")
        "wind_direction_10m" -> setOf("°")
        "wind_speed_10m", "wind_gusts_10m" -> setOf("km/h")
        "pressure_msl", "surface_pressure" -> setOf("hPa")
        "visibility" -> setOf("m")
        "precipitation" -> setOf("mm")
        "pm2_5", "pm10" -> setOf("μg/m³", "µg/m³")
        "us_aqi" -> setOf("USAQI", "")
        "weather_code" -> setOf("wmo code", "")
        "uv_index" -> setOf("")
        in SignalWeather.pollenFields -> setOf("grains/m³")
        else -> emptySet()
    }
    val valid = n?.takeIf { unit in expectedUnits && when (key) {
        "relative_humidity_2m", "cloud_cover", "precipitation_probability" -> it in 0.0..100.0
        "wind_direction_10m" -> it in 0.0..360.0
        "temperature_2m", "apparent_temperature", "dew_point_2m", "temperature_2m_max", "temperature_2m_min" -> it in -150.0..100.0 && unit == "°C"
        "weather_code" -> it in 0.0..99.0 && it % 1 == 0.0
        else -> it >= 0.0
    } }
    val text = if (key == "weather_code") valid?.toInt()?.let { code -> when (code) {
        0 -> "Clear"; 1 -> "Mainly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
        45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"; 66, 67 -> "Freezing rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"; 95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"; else -> "Weather code $code"
    } } else valid?.toString()?.removeSuffix(".0")
    return OutdoorValue(key, outdoorWeatherLabels[key] ?: key, text, if (key == "weather_code") "" else unit, at)
}

internal fun outdoorWeatherReadings(row: SignalObservation, place: SignalPlace): List<OutdoorReading> {
    val now = row.collectedAt
    val title = SignalWeather.sources.first { it.key == row.key }.name
    val provider = if (row.key in setOf("weather.air_quality", "weather.uv", "weather.pollen")) "Open-Meteo / CAMS" else "Open-Meteo"
    val link = if (provider.endsWith("CAMS")) "https://open-meteo.com/en/docs/air-quality-api" else "https://open-meteo.com/en/docs"
    val status = when (row.status) {
        "modeled", "forecast", "calculated" -> OutdoorStatus.AVAILABLE
        "stale_model" -> OutdoorStatus.STALE
        "partial_or_polar" -> OutdoorStatus.PARTIAL
        "unsupported_region" -> OutdoorStatus.UNSUPPORTED
        else -> OutdoorStatus.UNAVAILABLE
    }
    fun reading(id: String, name: String, basis: OutdoorBasis, values: List<OutdoorValue>, at: Long? = row.measuredAt,
                from: Long? = null, until: Long? = null, details: String = ""): OutdoorReading {
        val quality = when {
            status in setOf(OutdoorStatus.UNAVAILABLE, OutdoorStatus.UNSUPPORTED, OutdoorStatus.STALE) -> status
            values.none { it.value != null } -> OutdoorStatus.UNAVAILABLE
            values.any { it.value == null } -> OutdoorStatus.PARTIAL
            else -> status
        }
        val expiry = minOf(now + 30 * 60_000, if (basis == OutdoorBasis.MODEL && at != null) at + 2 * 60 * 60_000 else Long.MAX_VALUE,
            until ?: Long.MAX_VALUE)
        return OutdoorReading(id, row.key, name, provider, link, basis, quality, now, expiry,
            place.name, values, at, from, until, details)
    }
    fun field(key: String, prefix: String = "", at: Long? = row.measuredAt) =
        outdoorWeatherValue(key, row.fields["$prefix$key"], row.fields["$prefix$key.unit"].orEmpty(), at)

    return when (row.key) {
        "weather.forecast" -> (0..5).mapNotNull { index ->
            val prefix = "hour.$index."
            val at = row.fields["${prefix}time"]?.toLongOrNull() ?: return@mapNotNull null
            reading("${row.key}.$index", "Hourly forecast · slot ${index + 1}", OutdoorBasis.FORECAST,
                listOf("temperature_2m", "precipitation_probability", "precipitation").map { field(it, prefix, at) },
                at = null, from = at, until = at + 60 * 60_000,
                details = "Forecast for ${Instant.fromEpochMilliseconds(at)}. Rolling slot ${index + 1}; coverage: ${row.fields["returned_hours"] ?: "unknown"} of 6 hours returned.")
                .let { if (row.fields["returned_hours"]?.toIntOrNull() != 6 && it.status == OutdoorStatus.AVAILABLE) it.copy(status = OutdoorStatus.PARTIAL) else it }
        }.ifEmpty { listOf(reading(row.key, title, OutdoorBasis.FORECAST, emptyList())) }
        "weather.daily" -> (0..1).mapNotNull { index ->
            val prefix = "day.$index."
            val at = row.fields["${prefix}time"]?.toLongOrNull() ?: return@mapNotNull null
            val zone = runCatching { TimeZone.of(row.fields["timezone"].orEmpty()) }.getOrNull()
            val day = if (zone == null) "${Instant.fromEpochMilliseconds(at)}" else Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date.toString()
            reading("${row.key}.$index", "High / low · forecast day ${index + 1}", OutdoorBasis.FORECAST,
                listOf("temperature_2m_max", "temperature_2m_min").map { field(it, prefix, at) }, at = null,
                from = at, details = "Local forecast day $day · ${row.fields["timezone"] ?: "timezone unavailable"}. Rolling forecast day ${index + 1}.")
        }.ifEmpty { listOf(reading(row.key, title, OutdoorBasis.FORECAST, emptyList())) }
        "weather.daylight" -> {
            val values = listOf("sunrise" to "Sunrise (UTC)", "sunset" to "Sunset (UTC)").map { (key, name) ->
                OutdoorValue(key, name, row.fields[key]?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it).toString() })
            } + listOf("daylight_seconds" to "Daylight", "daylight_remaining_seconds" to "Daylight remaining at refresh").map { (key, name) ->
                OutdoorValue(key, name, row.fields[key]?.toDoubleOrNull()?.div(60)?.toInt()?.toString(), "min")
            }
            listOf(reading(row.key, title, OutdoorBasis.CALCULATED, values, at = null, from = row.windowStart,
                details = if (row.status == "partial_or_polar") "Sunrise or sunset may be absent on polar dates." else "Calculated daylight; remaining time is as of the last refresh."))
        }
        else -> {
            val fields = when (row.key) {
                "weather.current" -> SignalWeather.currentFields
                "weather.air_quality" -> listOf("us_aqi", "pm2_5", "pm10")
                "weather.uv" -> listOf("uv_index")
                "weather.pollen" -> SignalWeather.pollenFields
                else -> emptyList()
            }
            fields.map { key -> reading("${row.key}.$key", outdoorWeatherLabels[key] ?: title, OutdoorBasis.MODEL, listOf(field(key)),
                details = if (row.key == "weather.pollen") "Seasonal European coverage. Missing values do not mean no pollen." else "Modeled outdoor conditions; not a phone or room measurement.") }
        }
    }
}
