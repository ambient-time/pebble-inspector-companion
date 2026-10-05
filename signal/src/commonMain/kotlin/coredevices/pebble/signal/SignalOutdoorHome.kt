package coredevices.pebble.signal

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlin.math.round
import kotlin.time.Instant

fun validateOutdoorConnection(connection: HomeConnection): OutdoorConfig {
    require(connection.kind == HomeConnectorKind.PUBLIC_ENVIRONMENT && connection.enabled)
    require(connection.baseUrl.isEmpty() && !connection.allowPrivateHttp && connection.mqtt == null) { "Public data uses fixed HTTPS providers." }
    val config = requireNotNull(connection.outdoor) { "Choose a place and environmental sources." }
    SignalOutdoorSources.validate(config)
    require(config.sources.isNotEmpty()) { "Choose at least one environmental source." }
    return config
}

class SignalOutdoorConnector(http: HttpClient, private val connection: HomeConnection, private val clock: () -> Long) : HomeConnector {
    private val api = SignalOutdoorClient(http)
    override suspend fun catalog(): List<HomeEntity> = api.collect(validateOutdoorConnection(connection), clock()).map { it.homeEntity(connection) }
    override suspend fun read(entityId: String): HomeEntity? {
        val config = validateOutdoorConnection(connection)
        val key = config.sources.firstOrNull { entityId == it || entityId.startsWith("$it.") || entityId.startsWith("$it:") } ?: return null
        return api.collect(config.copy(sources = setOf(key)), clock()).firstOrNull { it.id == entityId }?.homeEntity(connection)
    }
    override suspend fun execute(action: HomeAction): HomeDispatchResult = throw HomeException("Public environmental data is read only.")
    override fun close() = api.close()
}

internal fun OutdoorReading.homeEntity(connection: HomeConnection) = HomeEntity(
    connection.id, id, title, domain = "public_environment", state = status.name.lowercase(),
    available = status in setOf(OutdoorStatus.AVAILABLE, OutdoorStatus.EMPTY, OutdoorStatus.PARTIAL),
    observedAt = fetchedAt, identity = "$id|${homeConnectionBinding(connection)}", expiresAt = expiresAt,
    values = values.map { HomeValue(it.key, it.value ?: "Unavailable", it.unit) },
    observationBasis = basis.name.lowercase(), outdoor = this,
)

fun outdoorStatusLabel(reading: OutdoorReading, now: Long): String = when {
    reading.status == OutdoorStatus.UNSUPPORTED -> "Not supported here"
    reading.status == OutdoorStatus.UNAVAILABLE -> "Unavailable"
    reading.status == OutdoorStatus.STALE || now >= reading.expiresAt -> "Stale · refresh to check"
    reading.status == OutdoorStatus.PARTIAL -> "Partial results"
    reading.status == OutdoorStatus.EMPTY -> "No matching reports returned"
    else -> when (reading.basis) {
        OutdoorBasis.MODEL -> "Modeled conditions"
        OutdoorBasis.FORECAST -> "Forecast"
        OutdoorBasis.CALCULATED -> "Calculated"
        OutdoorBasis.OFFICIAL_ALERT -> "Official alert"
        OutdoorBasis.PREDICTION -> "Prediction"
        OutdoorBasis.EVENT -> "Reported event"
        OutdoorBasis.REPORT -> "Provider report"
    }
}

fun outdoorDisplayValues(reading: OutdoorReading, preference: HomeTemperatureUnit): List<OutdoorValue> = reading.values.map { value ->
    val number = value.value?.toDoubleOrNull()
    if (number == null || value.unit != "°C" || preference != HomeTemperatureUnit.FAHRENHEIT) value
    else value.copy(value = (round((number * 1.8 + 32) * 10) / 10).toString().removeSuffix(".0"), unit = "°F")
}

fun outdoorTimeLabel(reading: OutdoorReading, now: Long? = null): String {
    fun stamp(time: Long): String {
        if (now == null || time > now) return Instant.fromEpochMilliseconds(time).toString()
        val minutes = (now - time) / 60_000
        return when { minutes < 1 -> "just now"; minutes < 60 -> "$minutes min ago"; minutes < 1440 -> "${minutes / 60} hr ago"; else -> "${minutes / 1440} days ago" }
    }
    val source = reading.sourceAt?.let { at ->
        val prefix = when (reading.basis) {
            OutdoorBasis.MODEL -> "Model valid"
            OutdoorBasis.EVENT -> "Event"
            OutdoorBasis.OFFICIAL_ALERT -> "Issued"
            else -> "Report updated"
        }
        "$prefix ${stamp(at)} · "
    }.orEmpty()
    return source + "Fetched ${stamp(reading.fetchedAt)}"
}

fun outdoorWatchDetail(reading: OutdoorReading, preference: HomeTemperatureUnit, now: Long): String {
    val values = outdoorDisplayValues(reading, preference).take(3).joinToString("\n") { "${it.label}: ${it.value ?: "Unavailable"} ${it.unit}".trim() }
    val age = ((now - reading.fetchedAt).coerceAtLeast(0) / 60_000)
    val time = reading.sourceAt?.let { "\n${when (reading.basis) { OutdoorBasis.MODEL -> "Model"; OutdoorBasis.EVENT -> "Event"; OutdoorBasis.OFFICIAL_ALERT -> "Issued"; else -> "Updated" }} ${Instant.fromEpochMilliseconds(it)}" }.orEmpty()
    val detail = "${reading.title.take(90)}\n${reading.place.take(70)}\n${outdoorStatusLabel(reading, now)}\n$values\n${reading.provider}\nFetched $age min ago$time\nFull details on phone"
    return if (detail.encodeToByteArray().size <= 850) detail else "${reading.provider}\n${outdoorStatusLabel(reading, now)}\nOpen the phone for this reading and its complete details."
}

internal fun outdoorObservations(entity: HomeEntity, now: Long): List<SignalObservation> {
    val reading = requireNotNull(entity.outdoor)
    val fields = mapOf("connection_id" to entity.connectionId, "entity_id" to entity.id, "place" to reading.place,
        "time_basis" to reading.basis.name.lowercase(), "source_at" to reading.sourceAt?.toString().orEmpty(),
        "fetched_at" to reading.fetchedAt.toString(), "expires_at" to reading.expiresAt.toString(),
        "attribution" to reading.attributionUrl)
    val values = reading.values.ifEmpty { listOf(OutdoorValue("status", "Status", null)) }
    return values.mapIndexed { index, value -> SignalObservation("home.readings", reading.provider, value = value.value.orEmpty(), unit = value.unit,
        collectedAt = reading.fetchedAt, status = if (now >= reading.expiresAt && reading.status in setOf(OutdoorStatus.AVAILABLE, OutdoorStatus.EMPTY, OutdoorStatus.PARTIAL)) "stale" else reading.status.name.lowercase(),
        period = reading.basis.name.lowercase(), windowStart = value.validAt ?: reading.validFrom, windowEnd = reading.validUntil,
        identity = homeTargetKey(entity.connectionId, entity.id) + ":" + value.key + (value.validAt?.let { ":$it" } ?: ""), number = value.value?.toDoubleOrNull(), metric = value.key,
        fields = if (index == 0) fields + ("original_reading" to Json.encodeToString(reading)) else fields) }
}
