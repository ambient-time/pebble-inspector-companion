package coredevices.pebble.signal

import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
enum class HomeTemperatureUnit { SOURCE, CELSIUS, FAHRENHEIT }

data class HomeDisplayReading(
    val key: String,
    val label: String,
    val value: String,
    val unit: String,
    val prominent: Boolean = false,
)

private enum class EnvironmentMetric { TEMPERATURE, HUMIDITY, OTHER }
private enum class TemperatureScale { CELSIUS, FAHRENHEIT, KELVIN }
private data class EnvironmentValue(
    val raw: HomeValue,
    val metric: EnvironmentMetric,
    val scale: TemperatureScale? = null,
    val target: Boolean = false,
    val conflictingUnit: Boolean = false,
)

private fun EnvironmentValue.temperatureGroup(): String = when {
    target -> "target_temperature"
    raw.key.lowercase().startsWith("current_temperature") -> "current_temperature"
    raw.key.lowercase().startsWith("ambient_temperature") -> "ambient_temperature"
    else -> "temperature"
}

private fun temperatureScale(unit: String?): TemperatureScale? = when (unit?.trim()?.lowercase()) {
    "°c", "℃", "c", "celsius", "degc" -> TemperatureScale.CELSIUS
    "°f", "℉", "f", "fahrenheit", "degf" -> TemperatureScale.FAHRENHEIT
    "k", "kelvin" -> TemperatureScale.KELVIN
    else -> null
}

private fun environmentalValue(entity: HomeEntity, value: HomeValue): EnvironmentValue {
    val key = value.key.lowercase()
    val deviceClass = entity.attributes["device_class"]?.lowercase()
    val explicitScale = when {
        key in setOf("temperature_c", "current_temperature_c", "ambient_temperature_c", "target_temperature_c") -> TemperatureScale.CELSIUS
        key in setOf("temperature_f", "current_temperature_f", "ambient_temperature_f", "target_temperature_f") -> TemperatureScale.FAHRENHEIT
        else -> null
    }
    val declaredScale = temperatureScale(value.unit)
    val actual = key.startsWith("current_temperature") || key.startsWith("ambient_temperature")
    val target = key.startsWith("target_temperature") || key == "setpoint" ||
        entity.domain.lowercase() in setOf("climate", "thermostat", "water_heater") && !actual
    val temperature = key in setOf("temperature", "current_temperature", "ambient_temperature", "target_temperature") || key == "setpoint" && declaredScale != null ||
        explicitScale != null || key == "state" && (deviceClass == "temperature" || declaredScale != null || entity.domain == "Number:Temperature")
    val humidity = key in setOf("humidity", "relative_humidity") || key == "state" && deviceClass == "humidity"
    return when {
        temperature -> EnvironmentValue(value, EnvironmentMetric.TEMPERATURE, declaredScale ?: explicitScale, target,
            explicitScale != null && !value.unit.isNullOrBlank() && declaredScale != explicitScale)
        humidity -> EnvironmentValue(value, EnvironmentMetric.HUMIDITY)
        else -> EnvironmentValue(value, EnvironmentMetric.OTHER)
    }
}

private fun EnvironmentValue.number(): Double? {
    val suffix = raw.unit?.trim().orEmpty()
    val text = raw.value.trim().let { if (suffix.isNotEmpty() && it.endsWith(suffix)) it.removeSuffix(suffix).trim() else it }
    val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (conflictingUnit) return null
    return value.takeIf {
        when (metric) {
            EnvironmentMetric.HUMIDITY -> it in 0.0..100.0 && (raw.unit.isNullOrBlank() || raw.unit?.trim() == "%")
            EnvironmentMetric.TEMPERATURE -> when (scale) {
                TemperatureScale.CELSIUS -> it >= -273.15
                TemperatureScale.FAHRENHEIT -> it >= -459.67
                TemperatureScale.KELVIN -> it >= 0.0
                null -> true
            }
            EnvironmentMetric.OTHER -> true
        }
    }
}

private fun environmentNumber(value: Double): String {
    val rounded = if (kotlin.math.abs(value) < 1e15) round(value * 10.0) / 10.0 else value
    return (if (rounded == 0.0) 0.0 else rounded).toString().removeSuffix(".0")
}

private fun EnvironmentValue.display(preference: HomeTemperatureUnit): HomeDisplayReading {
    if (metric == EnvironmentMetric.OTHER) return HomeDisplayReading(raw.key,
        raw.key.replace('_', ' ').replaceFirstChar { it.uppercase() }, raw.value, raw.unit.orEmpty())
    val label = when {
        metric == EnvironmentMetric.HUMIDITY -> "Humidity"
        target -> "Target temperature"
        else -> "Temperature"
    }
    val number = number()
    if (number == null) return HomeDisplayReading(raw.key, label, "Unavailable · ${raw.value}", raw.unit.orEmpty())
    if (metric == EnvironmentMetric.HUMIDITY) return HomeDisplayReading(raw.key, label, environmentNumber(number), "%")
    val destination = when (preference) {
        HomeTemperatureUnit.SOURCE -> scale
        HomeTemperatureUnit.CELSIUS -> TemperatureScale.CELSIUS
        HomeTemperatureUnit.FAHRENHEIT -> TemperatureScale.FAHRENHEIT
    }
    if (scale == null) return HomeDisplayReading(raw.key, label, raw.value, raw.unit?.takeIf { it.isNotBlank() } ?: "unit unknown")
    val celsius = when (scale) {
        TemperatureScale.CELSIUS -> number
        TemperatureScale.FAHRENHEIT -> (number - 32.0) / 1.8
        TemperatureScale.KELVIN -> number - 273.15
    }
    val converted = if (destination == scale) number else when (destination) {
        TemperatureScale.CELSIUS -> celsius
        TemperatureScale.FAHRENHEIT -> celsius * 1.8 + 32.0
        TemperatureScale.KELVIN -> celsius + 273.15
        null -> number
    }
    if (!converted.isFinite()) return HomeDisplayReading(raw.key, label, "Unavailable · ${raw.value}", raw.unit.orEmpty())
    val unit = when (destination) {
        TemperatureScale.CELSIUS -> "°C"
        TemperatureScale.FAHRENHEIT -> "°F"
        TemperatureScale.KELVIN -> "K"
        null -> raw.unit.orEmpty()
    }
    return HomeDisplayReading(raw.key, label, environmentNumber(converted), unit, prominent = !target)
}

fun homeDisplayReadings(entity: HomeEntity, unit: HomeTemperatureUnit): List<HomeDisplayReading> {
    entity.outdoor?.let { reading -> return outdoorDisplayValues(reading, unit).map { value ->
        HomeDisplayReading(value.key, value.label, value.value ?: "Unavailable", value.unit,
            prominent = value.key == "temperature_2m")
    } }
    val readings = homeReadings(entity).map { environmentalValue(entity, it) }
    val preferred = when (unit) {
        HomeTemperatureUnit.CELSIUS -> TemperatureScale.CELSIUS
        HomeTemperatureUnit.FAHRENHEIT -> TemperatureScale.FAHRENHEIT
        HomeTemperatureUnit.SOURCE -> null
    }
    val emittedTemperatures = mutableSetOf<String>()
    return readings.mapNotNull { reading ->
        if (reading.metric != EnvironmentMetric.TEMPERATURE) return@mapNotNull reading.display(unit)
        if (!emittedTemperatures.add(reading.temperatureGroup())) return@mapNotNull null
        val same = readings.filter { it.metric == EnvironmentMetric.TEMPERATURE && it.temperatureGroup() == reading.temperatureGroup() }
        val selected = same.firstOrNull { it.number() != null && it.scale != null && (preferred == null || it.scale == preferred) }
            ?: same.firstOrNull { it.number() != null } ?: reading
        selected.display(unit)
    }
}

private fun HomeEntity.hasSourceTime(): Boolean = updatedAt?.let { it > 0 } == true || values.any { it.measuredAt?.let { time -> time > 0 } == true }

fun homeEntityAvailable(entity: HomeEntity, now: Long): Boolean = entity.available &&
    (entity.expiresAt == null || now < entity.expiresAt) && !(entity.retained && !entity.hasSourceTime())

fun homeReadingStatus(entity: HomeEntity, now: Long): String = when {
    entity.outdoor != null && !entity.available && entity.outdoor.status in setOf(OutdoorStatus.AVAILABLE, OutdoorStatus.EMPTY, OutdoorStatus.PARTIAL) -> "Unavailable · last provider result"
    entity.outdoor != null -> outdoorStatusLabel(entity.outdoor, now)
    entity.observationBasis == "mqtt_waiting" -> "Waiting for a reading"
    entity.retained && !entity.hasSourceTime() -> "Retained value · age unknown"
    entity.expiresAt != null && now >= entity.expiresAt -> "Stale reading"
    !entity.available -> "Unavailable · last reported value"
    entity.retained -> "Retained value"
    else -> "Available"
}

private fun environmentAge(time: Long, now: Long): String {
    if (time > now) return "time is in the future"
    val seconds = ((now.toDouble() - time.toDouble()) / 1000.0).toLong().coerceAtLeast(0)
    return when {
        seconds == 0L -> "just now"
        seconds < 60 -> "$seconds sec ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} hr ago"
        else -> "${seconds / 86400} days ago"
    }
}

fun homeReadingAge(entity: HomeEntity, now: Long): String {
    entity.outdoor?.let { return outdoorTimeLabel(it, now) }
    val readings = homeReadings(entity)
    val measured = readings.mapNotNull { it.measuredAt?.takeIf { time -> time > 0 } }
    if (measured.isNotEmpty()) {
        val prefix = if (measured.distinct().size > 1) "Oldest measurement" else "Measured"
        val suffix = if (measured.size < readings.size) " · other measurement times unknown" else ""
        return "$prefix ${environmentAge(measured.min(), now)}$suffix"
    }
    entity.updatedAt?.takeIf { it > 0 }?.let { return "Reported ${environmentAge(it, now)} · measurement time unknown" }
    if (entity.retained) return "Measurement time unknown"
    return if (entity.observedAt > 0) "Received ${environmentAge(entity.observedAt, now)} · measurement time unknown" else "Measurement and receipt times unknown"
}
