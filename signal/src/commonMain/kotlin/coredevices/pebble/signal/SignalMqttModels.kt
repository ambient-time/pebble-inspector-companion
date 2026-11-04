package coredevices.pebble.signal

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class HomeMqttConfig(val topics: List<HomeMqttTopic> = emptyList())
@Serializable data class HomeMqttTopic(
    val topic: String = "",
    val name: String = "",
    val temperaturePath: String = "temperature",
    val humidityPath: String = "humidity",
    val timestampPath: String = "",
    val temperatureUnit: String = "°C",
    val staleAfterSeconds: Int = 7200,
)
@Serializable data class HomeMqttCredentials(val username: String = "", val password: String = "")

fun validateMqttConnection(connection: HomeConnection): Url {
    val raw = connection.baseUrl.trim()
    require(raw.length in 1..2048 && raw.none { it.isWhitespace() || it.code < 32 } && '\\' !in raw) { "Invalid MQTT WebSocket URL." }
    val url = Url(raw)
    require(url.protocol == URLProtocol.WSS || url.protocol == URLProtocol.WS) { "Use wss:// or explicitly trusted private ws:// MQTT 5." }
    require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Keep credentials, queries and fragments out of the broker URL." }
    require(url.encodedPath.split('/').none { it == "." || it == ".." || '%' in it }) { "Invalid MQTT WebSocket path." }
    if (url.protocol == URLProtocol.WS) require(connection.allowPrivateHttp && SignalHomeUrlPolicy.isPrivateHost(url.host)) { "Unencrypted MQTT requires explicit trust for a private broker." }
    val topics = requireNotNull(connection.mqtt) { "Add MQTT topics and field mappings." }.topics
    require(topics.size in 1..20 && topics.map { it.topic }.distinct().size == topics.size) { "Use one to twenty unique exact topics." }
    topics.forEach { mapping ->
        require(mapping.topic.isNotBlank() && mapping.topic.encodeToByteArray().size <= 512 && mapping.topic.none { it.code < 32 || it.code == 127 || it == '#' || it == '+' } && !mapping.topic.startsWith("\$share/")) { "Use exact topics without wildcards or control characters." }
        require(mapping.name.length <= 100 && mapping.name.none { it.code < 32 }) { "Invalid reading label." }
        require(mapping.temperatureUnit in setOf("°C", "°F", "K")) { "Choose °C, °F or K for temperature." }
        require(mapping.staleAfterSeconds in 10..604800) { "Reading age must be between ten seconds and seven days." }
        require(mapping.temperaturePath.isNotEmpty() || mapping.humidityPath.isNotEmpty()) { "Map temperature or humidity." }
        listOf(mapping.temperaturePath, mapping.humidityPath, mapping.timestampPath).forEach { path ->
            require(path.isEmpty() || path == "\$" || path.length <= 128 && path.split('.').size <= 8 && path.split('.').all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }) { "Use a dotted JSON field path, or $ for a scalar payload." }
        }
    }
    return url
}

internal fun mqttCredentials(raw: String?): HomeMqttCredentials {
    require(raw != null && raw.length <= 8192) { "Add MQTT credentials or choose anonymous access." }
    val value = try { Json.decodeFromString<HomeMqttCredentials>(raw) } catch (_: Exception) { throw HomeException("Invalid saved MQTT credentials.") }
    require(value.username.length <= 1024 && value.password.length <= 4096 && value.username.none { it.code < 32 } && value.password.none { it == '\u0000' }) { "Invalid MQTT credentials." }
    require(value.password.isEmpty() || value.username.isNotEmpty()) { "A password requires a username." }
    return value
}

internal fun mqttWaiting(connection: HomeConnection, mapping: HomeMqttTopic): HomeEntity = HomeEntity(
    connection.id, mapping.topic, mapping.name.ifBlank { mapping.topic }, domain = "environment", state = "Waiting for a reading",
    identity = "mqtt:" + Json.encodeToString(mapping), observationBasis = "mqtt_waiting",
)

internal fun mqttReading(connection: HomeConnection, mapping: HomeMqttTopic, payload: ByteArray, retained: Boolean, receivedAt: Long): HomeEntity {
    val base = mqttWaiting(connection, mapping).copy(observedAt = receivedAt, retained = retained, observationBasis = if (retained) "mqtt_retained" else "mqtt_delivery")
    if (payload.size > 16_384) return base.copy(state = "Payload exceeds reading limit")
    val root = try {
        val text = payload.decodeToString(throwOnInvalidSequence = true)
        require(mqttJsonDepthSafe(text))
        Json.parseToJsonElement(text)
    } catch (_: Exception) { return base.copy(state = "Invalid reading payload") }
    fun field(path: String): JsonPrimitive? {
        if (path.isEmpty()) return null
        var node: JsonElement = root
        if (path != "\$") for (key in path.split('.')) node = (node as? JsonObject)?.get(key) ?: return null
        return (node as? JsonPrimitive)?.takeUnless { it is JsonNull }
    }
    val time = if (mapping.timestampPath.isEmpty()) null else field(mapping.timestampPath)?.content?.let { mqttTimestamp(it, receivedAt) }
    val values = listOfNotNull(
        field(mapping.temperaturePath)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= when (mapping.temperatureUnit) { "°F" -> -459.67; "K" -> 0.0; else -> -273.15 } }?.let { HomeValue("temperature", it.toString(), mapping.temperatureUnit, time) },
        field(mapping.humidityPath)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { HomeValue("humidity", it.toString(), "%", time) },
    )
    val basis = when {
        mapping.timestampPath.isNotEmpty() -> time
        retained -> null
        else -> receivedAt
    }
    val expiry = basis?.let { it + mapping.staleAfterSeconds * 1000L }
    val available = values.isNotEmpty() && expiry != null && receivedAt < expiry
    return base.copy(values = values, updatedAt = time, expiresAt = expiry, available = available,
        state = when {
            values.isEmpty() -> "No mapped numeric reading"
            mapping.timestampPath.isNotEmpty() && time == null -> "Measurement time unavailable"
            retained && time == null -> "Retained reading · age unknown"
            !available -> "Stale reading"
            else -> values.joinToString(" · ") { "${it.value} ${it.unit.orEmpty()}" }
        })
}

internal fun mqttTimestamp(raw: String, receivedAt: Long): Long? {
    val numeric = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val timestamp = if (numeric != null) {
        val milliseconds = if (numeric < 100_000_000_000.0) numeric * 1000 else numeric
        milliseconds.takeIf { it <= Long.MAX_VALUE.toDouble() }?.toLong()
    } else homeTime(raw)
    return timestamp?.takeIf { it > 0 && it <= receivedAt + 60_000 }
}

private fun mqttJsonDepthSafe(text: String): Boolean {
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 16) return false }; '}', ']' -> depth-- }
    }
    return true
}
